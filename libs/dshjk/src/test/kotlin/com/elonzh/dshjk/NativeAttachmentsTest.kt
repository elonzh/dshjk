package com.elonzh.dshjk

import com.elonzh.dshjk.attachment.AttachmentException
import com.elonzh.dshjk.attachment.AttachmentStore
import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.HarnessPlugin
import com.elonzh.dshjk.configuration.ProfileInvocation
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.protocol.ContentBlock
import com.elonzh.dshjk.protocol.HarnessProtocol
import com.elonzh.dshjk.protocol.ImageMediaType
import com.elonzh.dshjk.protocol.SessionEventData
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledIf("hasNativeRuntime")
@Timeout(90)
class NativeAttachmentsTest {
    companion object {
        private val cli = System.getenv("DSHJK_TEST_CLI")?.takeIf(String::isNotBlank)
        @JvmStatic fun hasNativeRuntime() = cli != null || java.lang.Boolean.getBoolean("dshjk.test.bundledRuntime")
    }

    @TempDir lateinit var root: Path
    private val runtime = cli?.let { RuntimeCommand.node(Path.of(it)) } ?: HarnessConfig().runtime

    private fun response(text: String) = listOf(
        """{"type":"message_start","message":{"id":"native-fixture","model":"deepseek-v4-flash","usage":{"input_tokens":3,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"$text"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
    ).joinToString("") { "data: $it\n\n" }

    @ParameterizedTest
    @ValueSource(strings = ["sdk", "sdk-minimal"])
    fun `attachments share profile configuration and preserve destinations on failure`(profile: String): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = response("attachments accepted").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val store = root.resolve("configured-store")
            val patch = Files.writeString(root.resolve("storage.json"),
                """[{"id":"attachment-local","config":{"dshHome":${HarnessProtocol.json.encodeToString(store.toString())},"maxImageBytes":1024}}]""")
            val config = HarnessConfig(
                dshHome = root.resolve("home"),
                cwd = root,
                runtime = runtime,
                profile = profile,
                patchFiles = listOf(patch),
                environment = mapOf(
                    "DEEPSEEK_API_KEY" to "local-fixture",
                    "DEEPSEEK_BASE_URL" to "http://127.0.0.1:${server.address.port}",
                    "DSH_TELEMETRY_DISABLED" to "1",
                ),
            )
            val sourceFile = Files.writeString(root.resolve("sample.csv"), "name,count\nexample,2\n")
            val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
            val png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==",
            )
            val fileCopy = root.resolve("copy.csv")
            val imageCopy = root.resolve("copy.png")
            val harness = DeepSeekHarness(config)
            val attachments = AttachmentStore(harness)
            harness.use {
                val image = png.inputStream().use { attachments.saveImage(it, ImageMediaType.IMAGE_PNG, "sample.png") }
                val file = attachments.saveFile(sourceFile)
                assertEquals("sample.csv", file.name)
                assertEquals(2, image.width)
                assertTrue(Files.isDirectory(store.resolve("attachments/v1")))
                assertFalse(Files.exists(config.dshHome!!.resolve("attachments")))
                assertNull(harness.client.pid)

                val result = it.run(listOf(ContentBlock.Text("Inspect attachments"), ContentBlock.Image(image), ContentBlock.File(file)))
                assertEquals("attachments accepted", result.finalResponse)
                val input = result.events.mapNotNull { event -> event.data as? SessionEventData.InboxSpliced }
                    .flatMap { splice -> splice.inserted }.single()
                assertEquals(listOf(ContentBlock.Image(image), ContentBlock.File(file)), input.content.drop(1))

                val pid = harness.client.pid
                attachments.readFile(file, fileCopy)
                attachments.readImage(image, imageCopy)
                assertTrue(harness.client.isAlive)
                assertEquals(pid, harness.client.pid)
                assertContentEquals(Files.readAllBytes(sourceFile), Files.readAllBytes(fileCopy))
                assertContentEquals(pngSignature, Files.readAllBytes(imageCopy).take(8).toByteArray())
                assertEquals(image.bytes, Files.size(imageCopy))

                attachments.openImage(image).use { stream ->
                    assertContentEquals(Files.readAllBytes(imageCopy), stream.readAllBytes())
                }
                val invalidImage = assertFailsWith<AttachmentException> {
                    attachments.openImage(image.copy(bytes = image.bytes + 1)).close()
                }
                assertEquals("ATTACHMENT_CORRUPT", invalidImage.code)
                val oversized = assertFailsWith<AttachmentException> {
                    ByteArray(2 * 1024 * 1024).inputStream().use { attachments.saveImage(it, ImageMediaType.IMAGE_PNG) }
                }
                assertEquals("IMAGE_TOO_LARGE", oversized.code)

                val error = assertFailsWith<AttachmentException> {
                    attachments.readFile(file.copy(bytes = file.bytes + 1), fileCopy)
                }
                assertEquals("ATTACHMENT_CORRUPT", error.code)
                assertContentEquals(Files.readAllBytes(sourceFile), Files.readAllBytes(fileCopy))
            }
            assertFalse(harness.client.isAlive)
            assertFailsWith<IllegalStateException> { attachments.saveFile(sourceFile) }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `an opt-in command profile serves a verified file by request-scoped ID`(): Unit = runBlocking {
        val home = root.resolve("home")
        val profile = Files.createDirectories(home.resolve("profiles/downloads"))
        Files.writeString(profile.resolve("package.json"), """{"private":true,"dsh":{"profile":{"bundles":[]}}}""")
        val provider = HarnessPlugin("attachment-local", "@deepseek-ai/dsh-attachment-local")
        val invocation = ProfileInvocation("downloads", patches = listOf(HarnessPatch(insert = listOf(provider))))
        val config = HarnessConfig(dshHome = home, cwd = root, runtime = runtime, profile = "downloads",
            environment = mapOf("DSH_TELEMETRY_DISABLED" to "1"))
        // This also exceeds the CLI's capture limit, so passing bytes through JSON or captured stdout would fail.
        val content = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
        val source = Files.write(root.resolve("source.bin"), content)
        DeepSeekHarness(config).use { harness ->
            val store = AttachmentStore(harness.cli, invocation)
            val first = content.inputStream().use { store.saveFile(it, "first.bin") }
            val second = store.saveFile(source, "linked-name.bin")
            assertEquals(first.attachmentId, second.attachmentId)
            assertNull(harness.client.pid)

            val missing = assertFailsWith<AttachmentException> { store.openFile(first.attachmentId) { null } }
            assertEquals("ATTACHMENT_NOT_FOUND", missing.code)
            assertFailsWith<IllegalArgumentException> {
                store.openFile(first.attachmentId) { first.copy(attachmentId = "different-id") }
            }
            val corrupt = assertFailsWith<AttachmentException> {
                store.openFile(first.attachmentId) { second.copy(bytes = second.bytes + 1) }
            }
            assertEquals("ATTACHMENT_CORRUPT", corrupt.code)

            val sink = root.resolve("response.bin")
            store.openFile(first.attachmentId) { second }.use { stream ->
                assertEquals(second, stream.attachment)
                Files.newOutputStream(sink).use { assertEquals(second.bytes, stream.transferTo(it)) }
            }
            assertEquals(-1, Files.mismatch(source, sink))
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Depends on graceful SIGTERM delivery to DSH")
    fun `a failed upload read aborts the provider without publishing a partial file`(): Unit = runBlocking {
        val home = root.resolve("home")
        val config = HarnessConfig(dshHome = home, cwd = root, runtime = runtime, profile = "sdk-minimal",
            environment = mapOf("DSH_TELEMETRY_DISABLED" to "1"))
        val staging = home.resolve("attachments/v1/tmp")
        val input = object : InputStream() {
            var closed = false
            var started = false
            override fun read(): Int = error("Expected a bulk read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!started) {
                    started = true
                    bytes.fill(1, offset, offset + length)
                    return length
                }
                // Fail after the provider is actively consuming the upload.
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!Files.isDirectory(staging) || Files.list(staging).use { files -> files.noneMatch { Files.size(it) > 0 } }) {
                    check(System.nanoTime() < deadline) { "DSH did not start staging the upload" }
                    Thread.sleep(10)
                }
                throw IOException("upload disconnected")
            }
            override fun close() { closed = true }
        }
        DeepSeekHarness(config).use { harness ->
            input.use {
                val error = assertFailsWith<CliException> { AttachmentStore(harness).saveFile(it, "partial.bin") }
                assertEquals("upload disconnected", assertIs<IOException>(error.cause).message)
                assertFalse(it.closed)
            }
            assertFalse(Files.exists(home.resolve("attachments/v1/files")))
            assertTrue(Files.list(staging).use { it.findAny().isEmpty })
        }
    }
}
