package com.elonzh.dshjk.office

import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.error.HarnessException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Duration
import java.util.Objects

internal val DEFAULT_OFFICE_TIMEOUT: Duration = Duration.ofMinutes(2)
internal val DEFAULT_CAPABILITIES_TIMEOUT: Duration = Duration.ofSeconds(30)
internal val DEFAULT_DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(10)
internal val DEFAULT_TERMINATE_GRACE: Duration = Duration.ofSeconds(3)
internal const val MAX_OFFICE_TIMEOUT_MS: Long = Int.MAX_VALUE.toLong()
private const val MAX_SAFE_JS_INTEGER: Long = 9_007_199_254_740_991L

/** Native LibreOffice Kit error code and exit status, when the command was started. */
class OfficeException internal constructor(
    val code: String,
    message: String,
    val exitCode: Int? = null,
    cause: Throwable? = null,
) : HarnessException(message, cause)

/** Immutable launch settings for standalone Office operations without a DeepSeekHarness or dsh runtime. */
class OfficeConfig(
    val cwd: Path = Path.of("").toAbsolutePath(),
    val command: RuntimeCommand? = null,
    val processCwd: Path = cwd,
    environment: Map<String, String> = emptyMap(),
    val inheritEnvironment: Boolean = true,
    val terminateGrace: Duration = DEFAULT_TERMINATE_GRACE,
    val defaultOptions: OfficeOptions = OfficeOptions(),
) {
    val environment: Map<String, String> = java.util.Map.copyOf(environment)

    init {
        require(terminateGrace.toMillis() > 0) { "terminateGrace must be at least one millisecond" }
    }

    internal fun toHarnessConfig(): HarnessConfig = HarnessConfig(
        cwd = cwd,
        processCwd = processCwd,
        environment = environment,
        inheritEnvironment = inheritEnvironment,
        terminateGrace = terminateGrace,
    )

    class Builder internal constructor() {
        private var cwd = Path.of("").toAbsolutePath()
        private var command: RuntimeCommand? = null
        private var processCwd: Path? = null
        private var environment = emptyMap<String, String>()
        private var inheritEnvironment = true
        private var terminateGrace = DEFAULT_TERMINATE_GRACE
        private var defaultOptions = OfficeOptions()

        fun cwd(value: Path) = apply { cwd = value }
        fun command(value: RuntimeCommand) = apply { command = value }
        fun processCwd(value: Path) = apply { processCwd = value }
        fun environment(value: Map<String, String>) = apply { environment = value.toMap() }
        fun inheritEnvironment(value: Boolean) = apply { inheritEnvironment = value }
        fun terminateGrace(value: Duration) = apply { terminateGrace = value }
        fun defaultOptions(value: OfficeOptions) = apply { defaultOptions = value }

        fun build() = OfficeConfig(
            cwd = cwd,
            command = command,
            processCwd = processCwd ?: cwd,
            environment = environment,
            inheritEnvironment = inheritEnvironment,
            terminateGrace = terminateGrace,
            defaultOptions = defaultOptions,
        )
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}

/**
 * Conversion, rendering, and font options mirroring the `dsoffice` CLI flags.
 * Omitted fields retain the installed LibreOffice Kit defaults.
 */
class OfficeOptions @JvmOverloads constructor(
    val timeout: Duration? = null,
    val maxInputBytes: Long? = null,
    val maxOutputBytes: Long? = null,
    val maxImageResolution: Int? = null,
    val maxArchiveEntries: Int? = null,
    val maxUncompressedBytes: Long? = null,
    fontDirectories: List<Path> = emptyList(),
    initialFontFamilies: List<String> = emptyList(),
    fontFallbacks: List<List<String>>? = null,
    val maxFontFiles: Int? = null,
    val maxFontFileBytes: Long? = null,
    val maxLoadedFontBytes: Long? = null,
) {
    val fontDirectories: List<Path> = java.util.List.copyOf(fontDirectories)
    val initialFontFamilies: List<String> = java.util.List.copyOf(initialFontFamilies)
    val fontFallbacks: List<List<String>>? = fontFallbacks?.map { java.util.List.copyOf(it) }?.let { java.util.List.copyOf(it) }

    init {
        timeout?.let { validateOfficeTimeout(it) }
        maxInputBytes?.let { requirePositiveSafeInt(it, "maxInputBytes") }
        maxOutputBytes?.let { requirePositiveSafeInt(it, "maxOutputBytes") }
        maxImageResolution?.let { require(it > 0) { "maxImageResolution must be positive" } }
        maxArchiveEntries?.let { require(it > 0) { "maxArchiveEntries must be positive" } }
        maxUncompressedBytes?.let { requirePositiveSafeInt(it, "maxUncompressedBytes") }
        this.fontDirectories.forEach {
            require('\u0000' !in it.toString()) { "fontDirectories must not contain NUL characters" }
        }
        this.initialFontFamilies.forEach {
            require(it.isNotEmpty() && '\u0000' !in it) { "initialFontFamilies must contain nonempty strings without NUL" }
        }
        this.fontFallbacks?.forEach { group ->
            require(group.size >= 2 && group.all { it.isNotBlank() && '\u0000' !in it }) {
                "fontFallbacks must contain groups of at least two nonblank font names"
            }
        }
        maxFontFiles?.let { require(it > 0) { "maxFontFiles must be positive" } }
        maxFontFileBytes?.let { requirePositiveSafeInt(it, "maxFontFileBytes") }
        maxLoadedFontBytes?.let { requirePositiveSafeInt(it, "maxLoadedFontBytes") }
    }

    internal fun withDefaults(defaults: OfficeOptions): OfficeOptions = OfficeOptions(
        timeout = timeout ?: defaults.timeout,
        maxInputBytes = maxInputBytes ?: defaults.maxInputBytes,
        maxOutputBytes = maxOutputBytes ?: defaults.maxOutputBytes,
        maxImageResolution = maxImageResolution ?: defaults.maxImageResolution,
        maxArchiveEntries = maxArchiveEntries ?: defaults.maxArchiveEntries,
        maxUncompressedBytes = maxUncompressedBytes ?: defaults.maxUncompressedBytes,
        fontDirectories = fontDirectories.ifEmpty { defaults.fontDirectories },
        initialFontFamilies = initialFontFamilies.ifEmpty { defaults.initialFontFamilies },
        fontFallbacks = fontFallbacks ?: defaults.fontFallbacks,
        maxFontFiles = maxFontFiles ?: defaults.maxFontFiles,
        maxFontFileBytes = maxFontFileBytes ?: defaults.maxFontFileBytes,
        maxLoadedFontBytes = maxLoadedFontBytes ?: defaults.maxLoadedFontBytes,
    )

    internal fun toCliArguments(resolvePath: (Path) -> Path, effectiveTimeout: Duration): List<String> = buildList {
        addAll(listOf("--timeout-ms", effectiveTimeout.toMillis().toString()))
        maxInputBytes?.let { addAll(listOf("--max-input-bytes", it.toString())) }
        maxOutputBytes?.let { addAll(listOf("--max-output-bytes", it.toString())) }
        maxImageResolution?.let { addAll(listOf("--max-image-resolution", it.toString())) }
        maxArchiveEntries?.let { addAll(listOf("--max-archive-entries", it.toString())) }
        maxUncompressedBytes?.let { addAll(listOf("--max-uncompressed-bytes", it.toString())) }
        for (directory in fontDirectories) add("--font-directory=${resolvePath(directory)}")
        for (family in initialFontFamilies) add("--initial-font-family=$family")
        fontFallbacks?.let { groups ->
            val encoded = JsonArray(groups.map { group -> JsonArray(group.map(::JsonPrimitive)) }).toString()
            add("--font-fallbacks=$encoded")
        }
        maxFontFiles?.let { addAll(listOf("--max-font-files", it.toString())) }
        maxFontFileBytes?.let { addAll(listOf("--max-font-file-bytes", it.toString())) }
        maxLoadedFontBytes?.let { addAll(listOf("--max-loaded-font-bytes", it.toString())) }
    }

    override fun equals(other: Any?): Boolean = this === other || (other is OfficeOptions &&
        Objects.equals(timeout, other.timeout) &&
        maxInputBytes == other.maxInputBytes &&
        maxOutputBytes == other.maxOutputBytes &&
        maxImageResolution == other.maxImageResolution &&
        maxArchiveEntries == other.maxArchiveEntries &&
        maxUncompressedBytes == other.maxUncompressedBytes &&
        fontDirectories == other.fontDirectories &&
        initialFontFamilies == other.initialFontFamilies &&
        fontFallbacks == other.fontFallbacks &&
        maxFontFiles == other.maxFontFiles &&
        maxFontFileBytes == other.maxFontFileBytes &&
        maxLoadedFontBytes == other.maxLoadedFontBytes)

    override fun hashCode(): Int = Objects.hash(
        timeout, maxInputBytes, maxOutputBytes, maxImageResolution, maxArchiveEntries, maxUncompressedBytes,
        fontDirectories, initialFontFamilies, fontFallbacks, maxFontFiles, maxFontFileBytes, maxLoadedFontBytes,
    )

    override fun toString(): String =
        "OfficeOptions(timeout=$timeout, maxInputBytes=$maxInputBytes, maxOutputBytes=$maxOutputBytes, " +
            "maxImageResolution=$maxImageResolution, maxArchiveEntries=$maxArchiveEntries, " +
            "maxUncompressedBytes=$maxUncompressedBytes, fontDirectories=$fontDirectories, " +
            "initialFontFamilies=$initialFontFamilies, fontFallbacks=$fontFallbacks, " +
            "maxFontFiles=$maxFontFiles, maxFontFileBytes=$maxFontFileBytes, maxLoadedFontBytes=$maxLoadedFontBytes)"

    class Builder internal constructor() {
        private var timeout: Duration? = null
        private var maxInputBytes: Long? = null
        private var maxOutputBytes: Long? = null
        private var maxImageResolution: Int? = null
        private var maxArchiveEntries: Int? = null
        private var maxUncompressedBytes: Long? = null
        private var fontDirectories: List<Path> = emptyList()
        private var initialFontFamilies: List<String> = emptyList()
        private var fontFallbacks: List<List<String>>? = null
        private var maxFontFiles: Int? = null
        private var maxFontFileBytes: Long? = null
        private var maxLoadedFontBytes: Long? = null

        fun timeout(value: Duration) = apply { timeout = value }
        fun maxInputBytes(value: Long) = apply { maxInputBytes = value }
        fun maxOutputBytes(value: Long) = apply { maxOutputBytes = value }
        fun maxImageResolution(value: Int) = apply { maxImageResolution = value }
        fun maxArchiveEntries(value: Int) = apply { maxArchiveEntries = value }
        fun maxUncompressedBytes(value: Long) = apply { maxUncompressedBytes = value }
        fun fontDirectories(value: List<Path>) = apply { fontDirectories = value.toList() }
        fun initialFontFamilies(value: List<String>) = apply { initialFontFamilies = value.toList() }
        fun fontFallbacks(value: List<List<String>>) = apply { fontFallbacks = value.map(List<String>::toList) }
        fun maxFontFiles(value: Int) = apply { maxFontFiles = value }
        fun maxFontFileBytes(value: Long) = apply { maxFontFileBytes = value }
        fun maxLoadedFontBytes(value: Long) = apply { maxLoadedFontBytes = value }

        fun build() = OfficeOptions(
            timeout = timeout,
            maxInputBytes = maxInputBytes,
            maxOutputBytes = maxOutputBytes,
            maxImageResolution = maxImageResolution,
            maxArchiveEntries = maxArchiveEntries,
            maxUncompressedBytes = maxUncompressedBytes,
            fontDirectories = fontDirectories,
            initialFontFamilies = initialFontFamilies,
            fontFallbacks = fontFallbacks,
            maxFontFiles = maxFontFiles,
            maxFontFileBytes = maxFontFileBytes,
            maxLoadedFontBytes = maxLoadedFontBytes,
        )
    }

    companion object {
        @JvmStatic fun builder() = Builder()
        @JvmStatic fun timeout(timeout: Duration) = OfficeOptions(timeout = timeout)
    }
}

/**
 * Selection and raster limits for PNG rendering.
 * Documents and presentations use distinct one-based [pages]; spreadsheets use an exact [sheet] and optional A1 [range].
 */
class OfficeRenderOptions @JvmOverloads constructor(
    pages: List<Int> = emptyList(),
    val sheet: String? = null,
    val range: String? = null,
    val dpi: Int? = null,
    val maxPages: Int? = null,
    val maxPixels: Int? = null,
    val maxDimension: Int? = null,
) {
    val pages: List<Int> = java.util.List.copyOf(pages)

    init {
        require(this.pages.all { it >= 1 } && this.pages.size == this.pages.toSet().size) {
            "pages must contain distinct positive one-based page numbers"
        }
        sheet?.let { validateSheetName(it) }
        require(range == null || sheet != null) { "range requires an exact sheet name" }
        require(range == null || (range.isNotBlank() && '\u0000' !in range)) {
            "range must be a nonblank A1 cell or rectangle"
        }
        require(this.pages.isEmpty() || (sheet == null && range == null)) {
            "pages cannot be combined with sheet or range"
        }
        require(dpi == null || dpi in 24..600) { "dpi must be between 24 and 600" }
        require(maxPages == null || maxPages in 1..10_000) { "maxPages must be between 1 and 10000" }
        require(maxPixels == null || maxPixels in 1..16_777_216) { "maxPixels must be between 1 and 16777216" }
        require(maxDimension == null || maxDimension in 1..16_777_216) { "maxDimension must be between 1 and 16777216" }
    }

    override fun equals(other: Any?): Boolean = this === other || (other is OfficeRenderOptions &&
        pages == other.pages &&
        sheet == other.sheet &&
        range == other.range &&
        dpi == other.dpi &&
        maxPages == other.maxPages &&
        maxPixels == other.maxPixels &&
        maxDimension == other.maxDimension)

    override fun hashCode(): Int = Objects.hash(pages, sheet, range, dpi, maxPages, maxPixels, maxDimension)

    override fun toString(): String =
        "OfficeRenderOptions(pages=$pages, sheet=$sheet, range=$range, dpi=$dpi, " +
            "maxPages=$maxPages, maxPixels=$maxPixels, maxDimension=$maxDimension)"

    class Builder internal constructor() {
        private var pages: List<Int> = emptyList()
        private var sheet: String? = null
        private var range: String? = null
        private var dpi: Int? = null
        private var maxPages: Int? = null
        private var maxPixels: Int? = null
        private var maxDimension: Int? = null

        fun pages(value: List<Int>) = apply { pages = value.toList() }
        fun pages(vararg value: Int) = apply { pages = value.toList() }
        fun sheet(value: String) = apply { sheet = value }
        fun range(value: String) = apply { range = value }
        fun dpi(value: Int) = apply { dpi = value }
        fun maxPages(value: Int) = apply { maxPages = value }
        fun maxPixels(value: Int) = apply { maxPixels = value }
        fun maxDimension(value: Int) = apply { maxDimension = value }

        fun build() = OfficeRenderOptions(pages, sheet, range, dpi, maxPages, maxPixels, maxDimension)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
        @JvmStatic fun pages(vararg pages: Int) = OfficeRenderOptions(pages = pages.toList())
        @JvmStatic fun pages(pages: List<Int>) = OfficeRenderOptions(pages = pages)
        @JvmStatic @JvmOverloads fun sheet(sheet: String, range: String? = null) =
            OfficeRenderOptions(sheet = sheet, range = range)
    }
}

data class OfficeFileResult(
    val outputPath: Path,
    val backend: String,
    val missingFonts: List<String>,
    val pageCount: Int?,
)

/** Source rectangle in CSS pixels at 96 DPI, before raster scaling. */
data class OfficeImageRectangle(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class OfficeRenderedImage(
    val index: Int,
    val path: Path,
    val width: Int,
    val height: Int,
    val rectangle: OfficeImageRectangle,
    val byteLength: Long,
    val page: Int?,
    val sheet: String?,
    val range: String?,
    val rtl: Boolean?,
)

data class OfficeRenderResult(
    val outputDirectory: Path,
    val schemaVersion: Int,
    val backend: String,
    val rasterEngine: String,
    val inputPath: Path,
    val sourceSha256: String,
    val pageCount: Int,
    val dpi: Double,
    val missingFonts: List<String>,
    val images: List<OfficeRenderedImage>,
)

data class OfficeRuntimeInfo(
    val version: String,
    val backend: String,
    val nativePreview: Boolean? = null,
    val cliPath: Path,
    val nodeApiPath: Path,
)

data class OfficeConversionGroup(
    val inputs: List<String>,
    val outputs: List<String>,
)

data class OfficeImageCapabilities(
    val inputs: List<String>,
    val output: String,
    val officeBackend: String,
    val pdfBackend: String,
    val maxPages: Int,
    val maxPixels: Int,
    val maxDimension: Int,
)

data class OfficeRecalculationCapabilities(
    val inputs: List<String>,
    val outputs: List<String>,
    val preservesFormulas: Boolean,
)

data class OfficeCapabilities(
    val runtime: OfficeRuntimeInfo,
    val conversions: List<OfficeConversionGroup>,
    val imageRendering: OfficeImageCapabilities,
    val recalculation: OfficeRecalculationCapabilities,
)

internal fun validateSheetName(sheet: String) {
    require(sheet.isNotBlank() && '\u0000' !in sheet) {
        "sheet must be a nonblank worksheet name without NUL characters"
    }
}

internal fun validateOfficeTimeout(timeout: Duration) {
    val millis = try {
        timeout.toMillis()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Office timeout exceeds supported range: $timeout", error)
    }
    require(millis in 1L..MAX_OFFICE_TIMEOUT_MS) {
        "Office timeout must be between 1 ms and $MAX_OFFICE_TIMEOUT_MS ms"
    }
}

internal fun validatePositiveTimeout(timeout: Duration) {
    val millis = try {
        timeout.toMillis()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Timeout exceeds supported range: $timeout", error)
    }
    require(millis > 0L) { "Timeout must be at least one millisecond" }
}

private fun requirePositiveSafeInt(value: Long, name: String) {
    require(value in 1L..MAX_SAFE_JS_INTEGER) { "$name must be between 1 and $MAX_SAFE_JS_INTEGER" }
}
