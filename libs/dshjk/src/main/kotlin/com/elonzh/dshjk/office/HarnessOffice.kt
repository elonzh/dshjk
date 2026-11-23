package com.elonzh.dshjk.office

import com.elonzh.dshjk.cli.executeLocal
import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.error.ProtocolException
import com.elonzh.dshjk.protocol.HarnessProtocol
import com.elonzh.dshjk.protocol.arrayAt
import com.elonzh.dshjk.protocol.boundary
import com.elonzh.dshjk.protocol.objectValue
import com.elonzh.dshjk.runtime.internal.DshRuntime
import com.elonzh.dshjk.runtime.internal.RuntimeFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration

/**
 * Local Office operations over the LibreOffice Kit (`dsoffice`) CLI.
 *
 * Works standalone without a [DeepSeekHarness] or `dsh` runtime when LibreOffice Kit (`@deepseek-ai/libreoffice-kit`),
 * `DSH_OFFICE_SIDECAR`, or an explicit [RuntimeCommand] is available. When constructed from a [DeepSeekHarness],
 * it shares the harness's working directory, environment, bundled runtime downloader/cache, and close lifecycle.
 */
class HarnessOffice private constructor(
    private val runtime: DshRuntime,
    private val ownsRuntime: Boolean,
    private val allowBundledDsh: Boolean,
    private val command: RuntimeCommand?,
    private val defaultOptions: OfficeOptions,
) : AutoCloseable {

    /** Standalone Office operations without a DeepSeekHarness or dsh runtime. */
    @JvmOverloads constructor(config: OfficeConfig = OfficeConfig()) : this(
        runtime = DshRuntime(config.toHarnessConfig()),
        ownsRuntime = true,
        allowBundledDsh = false,
        command = config.command,
        defaultOptions = config.defaultOptions,
    )

    /** Standalone Office operations using an explicit `dsoffice` or LibreOffice Kit CLI command. */
    @JvmOverloads constructor(
        command: RuntimeCommand,
        defaultOptions: OfficeOptions = OfficeOptions(),
    ) : this(OfficeConfig(command = command, defaultOptions = defaultOptions))

    /** Harness-bound Office operations sharing working directory, environment, bundled runtime, and lifecycle. */
    @JvmOverloads constructor(
        harness: DeepSeekHarness,
        command: RuntimeCommand? = null,
        defaultOptions: OfficeOptions = OfficeOptions(),
    ) : this(
        runtime = harness.cli.runtime,
        ownsRuntime = false,
        allowBundledDsh = true,
        command = command,
        defaultOptions = defaultOptions,
    )

    private val lifecycleLock = Any()
    private var closed = false
    private val activeTasks = linkedSetOf<Job>()

    /** Inspects the installed LibreOffice Kit runtime and supported operations without converting a file. */
    @JvmSynthetic suspend fun capabilities(timeout: Duration = DEFAULT_CAPABILITIES_TIMEOUT): OfficeCapabilities = runOperation {
        validatePositiveTimeout(timeout)
        val response = executeRaw(
            arguments = listOf("capabilities", "--json"),
            timeout = timeout,
            name = "office-capabilities",
            description = "Office capabilities command",
        ).objectValue()
        boundary("Office capabilities result") {
            val value = HarnessProtocol.json.decodeFromJsonElement<CapabilitiesResponse>(response)
            require(value.runtime.version.isNotBlank() && value.runtime.backend.isNotBlank())
            OfficeCapabilities(
                runtime = OfficeRuntimeInfo(
                    version = value.runtime.version,
                    backend = value.runtime.backend,
                    nativePreview = value.runtime.nativePreview,
                    cliPath = Path.of(value.runtime.cliPath),
                    nodeApiPath = Path.of(value.runtime.nodeApiPath),
                ),
                conversions = value.conversions.map { OfficeConversionGroup(it.inputs, it.outputs) },
                imageRendering = OfficeImageCapabilities(
                    inputs = value.imageRendering.inputs,
                    output = value.imageRendering.output,
                    officeBackend = value.imageRendering.officeBackend,
                    pdfBackend = value.imageRendering.pdfBackend,
                    maxPages = value.imageRendering.maxPages,
                    maxPixels = value.imageRendering.maxPixels,
                    maxDimension = value.imageRendering.maxDimension,
                ),
                recalculation = OfficeRecalculationCapabilities(
                    inputs = value.recalculation.inputs,
                    outputs = value.recalculation.outputs,
                    preservesFormulas = value.recalculation.preservesFormulas,
                ),
            )
        }
    }

    /** Converts to the format selected by the output extension. Multi-sheet CSV requires an exact [sheet] name. */
    @JvmSynthetic suspend fun convert(
        input: Path,
        output: Path,
        sheet: String? = null,
        options: OfficeOptions = OfficeOptions(),
    ): OfficeFileResult {
        sheet?.let { validateSheetName(it) }
        return fileOperation("convert", input, output, sheet, options)
    }

    /** Recalculates a workbook and saves refreshed formula values in a new XLSX or ODS file. */
    @JvmSynthetic suspend fun recalculate(
        input: Path,
        output: Path,
        options: OfficeOptions = OfficeOptions(),
    ): OfficeFileResult = fileOperation("recalculate", input, output, null, options)

    /** Writes PNG images and their `manifest.json` to a fresh directory owned by the caller. */
    @JvmSynthetic suspend fun renderImages(
        input: Path,
        outputDirectory: Path,
        renderOptions: OfficeRenderOptions = OfficeRenderOptions(),
        options: OfficeOptions = OfficeOptions(),
    ): OfficeRenderResult {
        val effective = options.withDefaults(defaultOptions)
        val timeout = effectiveTimeout(effective)
        return withOutput(outputDirectory) { staged, target ->
            val args = buildList {
                addAll(listOf("render", "--input", path(input).toString(), "--output-dir", staged.toString()))
                if (renderOptions.pages.isNotEmpty()) addAll(listOf("--pages", renderOptions.pages.joinToString(",")))
                renderOptions.sheet?.let { add("--sheet=$it") }
                renderOptions.range?.let { add("--range=$it") }
                renderOptions.dpi?.let { addAll(listOf("--dpi", it.toString())) }
                renderOptions.maxPages?.let { addAll(listOf("--max-pages", it.toString())) }
                renderOptions.maxPixels?.let { addAll(listOf("--max-pixels", it.toString())) }
                renderOptions.maxDimension?.let { addAll(listOf("--max-dimension", it.toString())) }
                addAll(effective.toCliArguments(::path, timeout))
            }
            val response = executeOperation(args, timeout).objectValue()
            boundary("Office render result") {
                require(Files.isDirectory(staged, NOFOLLOW_LINKS)) { "Rendered output directory is missing" }
                val value = HarnessProtocol.json.decodeFromJsonElement<RenderResponse>(response)
                require(value.schemaVersion == 1 && value.backend.isNotBlank() && value.rasterEngine.isNotBlank())
                require(value.pageCount >= 0 && value.dpi.isFinite() && value.dpi > 0)
                val images = value.images.map { image ->
                    val stagedImage = Path.of(image.path).normalize()
                    require(stagedImage.parent == staged && Files.isRegularFile(stagedImage, NOFOLLOW_LINKS))
                    require(image.width > 0 && image.height > 0 && image.byteLength >= 0)
                    OfficeRenderedImage(
                        index = image.index,
                        path = target.resolve(stagedImage.fileName),
                        width = image.width,
                        height = image.height,
                        rectangle = OfficeImageRectangle(
                            x = image.rectangle.x,
                            y = image.rectangle.y,
                            width = image.rectangle.width,
                            height = image.rectangle.height,
                        ),
                        byteLength = image.byteLength,
                        page = image.page,
                        sheet = image.sheet,
                        range = image.range,
                        rtl = image.rtl,
                    )
                }
                val rawImages = response.arrayAt("images")
                require(rawImages.size == images.size)
                val manifest = JsonObject(response + ("images" to JsonArray(rawImages.zip(images).map { (raw, image) ->
                    JsonObject(raw.objectValue() + ("path" to JsonPrimitive(image.path.toString())))
                })))
                val manifestFile = staged.resolve("manifest.json")
                require(!Files.isSymbolicLink(manifestFile)) { "manifest.json must not be a symbolic link" }
                try {
                    Files.writeString(manifestFile, manifestJson.encodeToString(JsonObject.serializer(), manifest) + "\n")
                } catch (error: IOException) {
                    throw OfficeException("invalid-output", "Cannot update Office manifest at $target: ${error.message}", cause = error)
                }
                OfficeRenderResult(
                    outputDirectory = target,
                    schemaVersion = value.schemaVersion,
                    backend = value.backend,
                    rasterEngine = value.rasterEngine,
                    inputPath = Path.of(value.inputPath),
                    sourceSha256 = value.sourceSha256,
                    pageCount = value.pageCount,
                    dpi = value.dpi,
                    missingFonts = value.missingFonts,
                    images = images,
                )
            }
        }
    }

    /** Explicitly prepares the Office sidecar using the bundled DSH downloader (requires `dshjk-runtime` via `HarnessOffice(harness)`). */
    @JvmSynthetic suspend fun downloadRuntime(timeout: Duration = DEFAULT_DOWNLOAD_TIMEOUT): Path = runOperation {
        validatePositiveTimeout(timeout)
        if (!allowBundledDsh) {
            throw unavailable("Office runtime downloads require a bundled dshjk-runtime via HarnessOffice(harness)")
        }
        val download = runtime.officeDownload() ?: throw unavailable("This DSH runtime has no bundled Office downloader")
        val response = runCommand(
            command = download.command,
            arguments = emptyList(),
            timeout = timeout,
            environment = mapOf("DSH_RUNTIME_DOWNLOAD" to "office"),
            name = "office-download",
            description = "Office runtime download",
        )
        boundary("Office download result") {
            val value = response as? JsonPrimitive ?: throw ProtocolException("Expected Office resource path")
            require(value.isString)
            Path.of(value.content).also { require(it.isAbsolute && Files.isDirectory(it)) }
        }
    }

    /** Cancels active Office operations started through this instance and waits for their processes to exit. */
    @JvmSynthetic suspend fun closeAsync() = withContext(NonCancellable) {
        if (ownsRuntime) {
            synchronized(lifecycleLock) { closed = true }
            runtime.stopAccepting()
            runtime.awaitCommands()
        } else {
            val toCancel = synchronized(lifecycleLock) {
                closed = true
                activeTasks.toList()
            }
            toCancel.forEach { it.cancel() }
            toCancel.forEach { it.join() }
        }
    }

    override fun close() = runBlocking { closeAsync() }

    @JvmName("capabilities") @JvmOverloads fun capabilitiesBlocking(
        timeout: Duration = DEFAULT_CAPABILITIES_TIMEOUT,
    ): OfficeCapabilities = runBlocking { capabilities(timeout) }

    @JvmName("convert") @JvmOverloads fun convertBlocking(
        input: Path, output: Path, sheet: String? = null, options: OfficeOptions = OfficeOptions(),
    ): OfficeFileResult = runBlocking { convert(input, output, sheet, options) }

    @JvmName("convert") fun convertBlocking(
        input: Path, output: Path, options: OfficeOptions,
    ): OfficeFileResult = runBlocking { convert(input, output, null, options) }

    @JvmName("recalculate") @JvmOverloads fun recalculateBlocking(
        input: Path, output: Path, options: OfficeOptions = OfficeOptions(),
    ): OfficeFileResult = runBlocking { recalculate(input, output, options) }

    @JvmName("renderImages") @JvmOverloads fun renderImagesBlocking(
        input: Path, outputDirectory: Path,
        renderOptions: OfficeRenderOptions = OfficeRenderOptions(),
        options: OfficeOptions = OfficeOptions(),
    ): OfficeRenderResult = runBlocking { renderImages(input, outputDirectory, renderOptions, options) }

    @JvmName("downloadRuntime") @JvmOverloads fun downloadRuntimeBlocking(
        timeout: Duration = DEFAULT_DOWNLOAD_TIMEOUT,
    ): Path = runBlocking { downloadRuntime(timeout) }

    private suspend fun fileOperation(
        operation: String, input: Path, output: Path, sheet: String?, options: OfficeOptions,
    ): OfficeFileResult {
        val effective = options.withDefaults(defaultOptions)
        val timeout = effectiveTimeout(effective)
        return withOutput(output) { staged, target ->
            val args = buildList {
                addAll(listOf(operation, "--input", path(input).toString(), "--output", staged.toString()))
                sheet?.let { add("--sheet=$it") }
                addAll(effective.toCliArguments(::path, timeout))
            }
            val response = executeOperation(args, timeout)
            boundary("Office file result") {
                val value = HarnessProtocol.json.decodeFromJsonElement<FileResponse>(response)
                require(value.backend.isNotBlank())
                require(Path.of(value.outputPath).normalize() == staged && Files.isRegularFile(staged, NOFOLLOW_LINKS))
                OfficeFileResult(target, value.backend, value.missingFonts, value.pageCount)
            }
        }
    }

    private fun effectiveTimeout(options: OfficeOptions): Duration =
        (options.timeout ?: DEFAULT_OFFICE_TIMEOUT).also(::validateOfficeTimeout)

    private fun path(path: Path): Path = runtime.config.cwd.toAbsolutePath().resolve(path).normalize()

    private suspend fun <T> runOperation(action: suspend () -> T): T {
        if (ownsRuntime) {
            synchronized(lifecycleLock) { check(!closed) { "HarnessOffice closed" } }
            return runtime.command(action)
        }
        return runtime.command {
            val job = currentCoroutineContext()[Job]!!
            synchronized(lifecycleLock) {
                check(!closed) { "HarnessOffice closed" }
                activeTasks.add(job)
            }
            try {
                action()
            } finally {
                synchronized(lifecycleLock) { activeTasks.remove(job) }
            }
        }
    }

    private suspend fun <T> withOutput(output: Path, action: suspend (Path, Path) -> T): T = runOperation {
        val target = path(output)
        val parent = target.parent ?: throw OfficeException("invalid-output", "Expected a file or directory path")
        // Keep incomplete files private even if cancellation has to forcibly stop the engine.
        val files = try {
            if (Files.exists(target, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
            RuntimeFiles(".dshjk-office-", parent)
        } catch (error: IOException) {
            throw OfficeException("invalid-output", "Cannot prepare Office output at $target: ${error.message}", cause = error)
        }
        var published = false
        var failure: Throwable? = null
        try {
            val staged = try {
                files.path(target.fileName.toString())
            } catch (error: IOException) {
                throw OfficeException("invalid-output", "Cannot prepare Office output at $target: ${error.message}", cause = error)
            }
            val result = action(staged, target)
            currentCoroutineContext().ensureActive()
            try {
                synchronized(outputPublication) {
                    Files.move(staged, target)
                }
                published = true
            } catch (error: IOException) {
                throw OfficeException("invalid-output", "Cannot publish Office output at $target: ${error.message}", cause = error)
            }
            result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                files.close()
            } catch (cleanup: IOException) {
                val primary = failure
                if (primary != null) {
                    primary.addSuppressed(cleanup)
                } else if (!published) {
                    throw OfficeException("invalid-output", "Cannot clean Office staging directory at $target: ${cleanup.message}", cause = cleanup)
                }
            }
        }
    }

    /** Give the CLI's own `--timeout-ms` time to abort and clean scratch files before forcible process termination. */
    private suspend fun executeOperation(arguments: List<String>, cliTimeout: Duration): JsonElement {
        val processTimeout = try {
            cliTimeout.plus(runtime.config.terminateGrace)
        } catch (_: ArithmeticException) {
            cliTimeout
        }
        return executeRaw(arguments, processTimeout, "office", "Office command")
    }

    private suspend fun executeRaw(
        arguments: List<String>,
        timeout: Duration,
        name: String,
        description: String,
    ): JsonElement {
        val files = RuntimeFiles("dshjk-office-cli-")
        var failure: Throwable? = null
        try {
            val resolved = command ?: runtime.officeCommand(files, allowBundledDsh)
            return runCommand(resolved, arguments, timeout, name = name, description = description)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                files.close()
            } catch (cleanup: IOException) {
                failure?.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun runCommand(
        command: RuntimeCommand,
        arguments: List<String>,
        timeout: Duration,
        environment: Map<String, String> = emptyMap(),
        name: String = "office",
        description: String = "Office command",
    ): JsonElement {
        val result = try {
            runtime.executeLocal(command, arguments, timeout, environment, name, description)
        } catch (error: CliException) {
            throw OfficeException("failed", error.message ?: "Cannot execute $description", cause = error)
        }
        if (result.exitCode != 0) {
            val diagnostic = result.stderr.trim()
            val failure = parseFailure(diagnostic)
            throw OfficeException(
                code = failure?.code ?: "failed",
                message = failure?.error ?: diagnostic.ifEmpty { "$description exited with status ${result.exitCode}" },
                exitCode = result.exitCode,
            )
        }
        return boundary("Office command response") {
            HarnessProtocol.json.parseToJsonElement(result.stdout ?: throw ProtocolException("Missing Office stdout"))
        }
    }
}

private fun parseFailure(diagnostic: String): FailureResponse? {
    if (diagnostic.isEmpty()) return null
    return diagnostic.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("{") && it.endsWith("}") }
        .toList()
        .asReversed()
        .firstNotNullOfOrNull { line ->
            runCatching { HarnessProtocol.json.decodeFromString<FailureResponse>(line) }
                .getOrNull()
                ?.takeIf { it.code.isNotBlank() && it.error.isNotBlank() }
        }
}

private val outputPublication = Any()
private val manifestJson = Json { prettyPrint = true }

@Serializable
private data class FileResponse(
    val backend: String,
    val outputPath: String,
    val missingFonts: List<String>,
    val pageCount: Int? = null,
)

@Serializable
private data class RectangleResponse(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@Serializable
private data class ImageResponse(
    val index: Int,
    val path: String,
    val width: Int,
    val height: Int,
    val rectangle: RectangleResponse,
    val byteLength: Long,
    val page: Int? = null,
    val sheet: String? = null,
    val range: String? = null,
    val rtl: Boolean? = null,
)

@Serializable
private data class RenderResponse(
    val schemaVersion: Int,
    val backend: String,
    val rasterEngine: String,
    val inputPath: String,
    val sourceSha256: String,
    val pageCount: Int,
    val dpi: Double,
    val missingFonts: List<String>,
    val images: List<ImageResponse>,
)

@Serializable
private data class CapabilitiesResponse(
    val runtime: RuntimeInfoResponse,
    val conversions: List<ConversionGroupResponse>,
    val imageRendering: ImageCapabilitiesResponse,
    val recalculation: RecalculationCapabilitiesResponse,
)

@Serializable
private data class RuntimeInfoResponse(
    val version: String,
    val backend: String,
    val nativePreview: Boolean? = null,
    val cliPath: String,
    val nodeApiPath: String,
)

@Serializable
private data class ConversionGroupResponse(
    val inputs: List<String>,
    val outputs: List<String>,
)

@Serializable
private data class ImageCapabilitiesResponse(
    val inputs: List<String>,
    val output: String,
    val officeBackend: String,
    val pdfBackend: String,
    val maxPages: Int,
    val maxPixels: Int,
    val maxDimension: Int,
)

@Serializable
private data class RecalculationCapabilitiesResponse(
    val inputs: List<String>,
    val outputs: List<String>,
    val preservesFormulas: Boolean,
)

@Serializable
private data class FailureResponse(val code: String, val error: String)
