package com.elonzh.dshjk.attachment

import com.elonzh.dshjk.cli.HarnessCli
import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.HarnessPlugin
import com.elonzh.dshjk.configuration.ProfileInvocation
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.error.HarnessException
import com.elonzh.dshjk.error.ProtocolException
import com.elonzh.dshjk.protocol.FileAttachment
import com.elonzh.dshjk.protocol.HarnessProtocol
import com.elonzh.dshjk.protocol.ImageAttachment
import com.elonzh.dshjk.protocol.ImageMediaType
import com.elonzh.dshjk.protocol.boundary
import com.elonzh.dshjk.protocol.objectValue
import com.elonzh.dshjk.protocol.string
import com.elonzh.dshjk.runtime.internal.RuntimeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A lookup or attachment service failure, with a machine-readable code. */
class AttachmentException(val code: String, message: String) : HarnessException(message)

/** Resolves the file reference visible to the current request. Called in the caller's context. */
fun interface FileAttachmentLookup {
    fun find(attachmentId: String): FileAttachment?
}

/** A verified file and its metadata. Closing the stream also removes its temporary copy. */
class FileAttachmentStream internal constructor(
    val attachment: FileAttachment,
    input: InputStream,
) : FilterInputStream(input)

/** Optional adapter for a DSH profile's attachments service. */
class AttachmentStore private constructor(
    private val cli: HarnessCli,
    private val invocation: ProfileInvocation,
    private val surfacePatches: List<HarnessPatch>,
) {
    /** Use the attachment service configured in the harness's sdk or sdk-minimal profile. */
    constructor(harness: DeepSeekHarness) :
        this(harness.cli, harness.config.sdkInvocation, sdkSurfacePatches(harness.config.profile))

    /** Use a command profile that provides attachments and leaves arguments and stdio to this adapter. */
    constructor(cli: HarnessCli, invocation: ProfileInvocation) : this(cli, invocation, emptyList())

    init {
        require(invocation.arguments.isEmpty()) { "AttachmentStore supplies the profile's command arguments" }
    }

    /** Saves a normalized image and returns the reference accepted by ContentBlock.Image. */
    @JvmSynthetic suspend fun saveImage(path: Path, mediaType: ImageMediaType, name: String? = null): ImageAttachment =
        withFileInput(path) { saveImage(it, mediaType, name) }

    /** Saves an encoded image from [input]. The caller closes the stream and bounds any blocking reads. */
    @JvmSynthetic suspend fun saveImage(input: InputStream, mediaType: ImageMediaType, name: String? = null): ImageAttachment {
        val result = execute(listOf("save-image", mediaType.wireName) + listOfNotNull(name), input)
        return boundary("DSH image attachment reference") {
            HarnessProtocol.json.decodeFromJsonElement(ImageAttachment.serializer(), result)
        }
    }

    /** Streams the original file bytes into the store and returns a reference for ContentBlock.File. */
    @JvmSynthetic suspend fun saveFile(path: Path, name: String = path.fileName.toString()): FileAttachment =
        withFileInput(path) { saveFile(it, name) }

    /** Streams the original bytes from [input]. The caller closes the stream and bounds any blocking reads. */
    @JvmSynthetic suspend fun saveFile(input: InputStream, name: String): FileAttachment {
        val result = execute(listOf("save-file", name), input)
        return boundary("DSH file attachment reference") {
            HarnessProtocol.json.decodeFromJsonElement(FileAttachment.serializer(), result)
        }
    }

    private suspend fun <T> withFileInput(path: Path, save: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        try {
            Files.newInputStream(path.toAbsolutePath().normalize()).use { save(it) }
        } catch (error: IOException) {
            throw CliException(invocation.profile, "Cannot read attachment file '$path': ${error.message}", error)
        }
    }

    /** Reads a normalized image and replaces the destination after success. */
    @JvmSynthetic suspend fun readImage(attachment: ImageAttachment, destination: Path): Unit =
        read("read-image", HarnessProtocol.json.encodeToString(ImageAttachment.serializer(), attachment), destination)

    /** Streams the verified original file bytes and replaces the destination after success. */
    @JvmSynthetic suspend fun readFile(attachment: FileAttachment, destination: Path): Unit =
        read("read-file", HarnessProtocol.json.encodeToString(FileAttachment.serializer(), attachment), destination)

    /**
     * Opens a verified, normalized image. Use the attachment's media type and byte count
     * for the response headers. Closing the stream removes its temporary copy.
     */
    @JvmSynthetic suspend fun openImage(attachment: ImageAttachment): InputStream =
        open("read-image", HarnessProtocol.json.encodeToString(ImageAttachment.serializer(), attachment))

    /**
     * Opens a file after DSH verifies it. The returned stream reads from disk without holding
     * the DSH process open. The caller must close it, including if an HTTP response is aborted.
     */
    @JvmSynthetic suspend fun openFile(attachment: FileAttachment): FileAttachmentStream =
        FileAttachmentStream(attachment, open("read-file",
            HarnessProtocol.json.encodeToString(FileAttachment.serializer(), attachment)))

    private suspend fun open(command: String, reference: String): InputStream {
        val files = RuntimeFiles("dshjk-attachment-stream-")
        var opened: InputStream? = null
        try {
            return withContext(Dispatchers.IO) {
                val path = files.path("output")
                stage(command, reference, path)
                currentCoroutineContext().ensureActive()
                val input = Files.newInputStream(path)
                object : FilterInputStream(input) {
                    @Synchronized
                    override fun close() = files.use { super.close() }
                }.also { opened = it }
            }
        } catch (error: Throwable) {
            // Cancellation at the dispatcher handoff can discard an already opened stream.
            try { opened?.close() ?: files.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** Resolves a request-scoped file reference and opens its verified content. */
    @JvmSynthetic suspend fun openFile(attachmentId: String, lookup: FileAttachmentLookup): FileAttachmentStream {
        require(attachmentId.isNotBlank()) { "Attachment ID must not be blank" }
        val attachment = lookup.find(attachmentId)
            ?: throw AttachmentException("ATTACHMENT_NOT_FOUND", "File attachment is unavailable")
        require(attachment.attachmentId == attachmentId) { "Lookup returned a different attachment ID" }
        return openFile(attachment)
    }

    private suspend fun read(command: String, reference: String, destination: Path) = withContext(Dispatchers.IO) {
        val target = destination.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "Destination must name a file" }
        RuntimeFiles(".dshjk-download-", parent).use { files ->
            val staged = files.path("output")
            stage(command, reference, staged)
            currentCoroutineContext().ensureActive()
            try {
                Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target, REPLACE_EXISTING)
            }
        }
        Unit
    }

    private suspend fun stage(command: String, reference: String, output: Path) {
        execute(listOf(command, reference, "--output", output.toString()))
        if (!Files.isRegularFile(output, NOFOLLOW_LINKS)) {
            throw ProtocolException("DSH attachment command did not write its output file")
        }
    }

    private suspend fun execute(arguments: List<String>, input: InputStream? = null): JsonElement = withContext(Dispatchers.IO) {
        val profile = invocation.profile
        RuntimeFiles("dshjk-attachments-").use { files ->
            val module = files.path("attachments.mjs")
            checkNotNull(AttachmentStore::class.java.getResourceAsStream("attachments.mjs")) {
                "Missing DSH attachment command resource"
            }.use { Files.copy(it, module) }
            val plugin = HarnessPlugin("dshjk-attachments", module.toUri().toString())
            val reply = cli.executeWithOverlays(
                invocation,
                stdin = input,
                arguments = arguments,
                overlays = surfacePatches + HarnessPatch(insert = listOf(plugin)),
            )
            val response = try {
                boundary("DSH attachment command response") {
                    HarnessProtocol.json.parseToJsonElement(checkNotNull(reply.stdout)).objectValue()
                }
            } catch (error: ProtocolException) {
                if (reply.exitCode == 0) {
                    throw ProtocolException("Invalid DSH attachment command response. ${reply.stderr}".trimEnd(), error)
                }
                throw commandFailed(profile, reply.exitCode, reply.stderr, error)
            }
            response["error"]?.objectValue()?.let { throw AttachmentException(it.string("code"), it.string("message")) }
            if (reply.exitCode != 0) throw commandFailed(profile, reply.exitCode, reply.stderr)
            response["result"] ?: throw ProtocolException("Missing DSH attachment command result")
        }
    }

    @JvmName("saveImage") @JvmOverloads fun saveImageBlocking(
        path: Path, mediaType: ImageMediaType, name: String? = null,
    ): ImageAttachment = runBlocking { saveImage(path, mediaType, name) }

    @JvmName("saveImage") @JvmOverloads fun saveImageBlocking(
        input: InputStream, mediaType: ImageMediaType, name: String? = null,
    ): ImageAttachment = runBlocking { saveImage(input, mediaType, name) }

    @JvmName("saveFile") @JvmOverloads fun saveFileBlocking(
        path: Path, name: String = path.fileName.toString(),
    ): FileAttachment = runBlocking { saveFile(path, name) }

    @JvmName("saveFile") fun saveFileBlocking(input: InputStream, name: String): FileAttachment =
        runBlocking { saveFile(input, name) }

    @JvmName("readImage") fun readImageBlocking(attachment: ImageAttachment, destination: Path): Unit =
        runBlocking { readImage(attachment, destination) }

    @JvmName("readFile") fun readFileBlocking(attachment: FileAttachment, destination: Path): Unit =
        runBlocking { readFile(attachment, destination) }

    @JvmName("openImage") fun openImageBlocking(attachment: ImageAttachment): InputStream =
        runBlocking { openImage(attachment) }

    @JvmName("openFile") fun openFileBlocking(attachment: FileAttachment): FileAttachmentStream =
        runBlocking { openFile(attachment) }

    @JvmName("openFile") fun openFileBlocking(attachmentId: String, lookup: FileAttachmentLookup): FileAttachmentStream =
        runBlocking { openFile(attachmentId, lookup) }
}

private fun commandFailed(profile: String, exitCode: Int, stderr: String, cause: Throwable? = null) =
    CliException(profile, "DSH attachment command exited with $exitCode. $stderr".trimEnd(), cause)

/** Selects the CLI surface without changing the SDK profile's attachment provider or its configuration. */
private fun sdkSurfacePatches(profile: String): List<HarnessPatch> = buildList {
    require(profile == "sdk" || profile == "sdk-minimal") {
        "AttachmentStore(harness) requires the sdk or sdk-minimal profile, got '$profile'"
    }
    add(HarnessPatch(id = "sdk-app-startup", disabled = true, name = "@deepseek-ai/dsh-sdk-app"))
    add(HarnessPatch(id = "sdk-jsonrpc-server", disabled = true, name = "@deepseek-ai/dsh-sdk-jsonrpc-server"))
    if (profile == "sdk") {
        // This entry requires sdkSessionActivation, which is provided by the RPC server.
        add(HarnessPatch(id = "schedule", disabled = true, name = "@deepseek-ai/dsh-schedule"))
    }
}
