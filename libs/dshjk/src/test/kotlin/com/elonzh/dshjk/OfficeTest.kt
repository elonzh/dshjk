package com.elonzh.dshjk

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.error.RequestTimeoutException
import com.elonzh.dshjk.office.HarnessOffice
import com.elonzh.dshjk.office.OfficeConfig
import com.elonzh.dshjk.office.OfficeException
import com.elonzh.dshjk.office.OfficeOptions
import com.elonzh.dshjk.office.OfficeRenderOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(30)
class OfficeTest {
    @TempDir lateinit var root: Path

    @Test
    fun `options validate eagerly and preserve value equality`() {
        val opts1 = OfficeOptions(
            timeout = Duration.ofSeconds(15),
            maxInputBytes = 1024,
            fontDirectories = listOf(Path.of("/fonts")),
            initialFontFamilies = listOf("Noto Sans CJK SC"),
            fontFallbacks = listOf(listOf("Calibri", "Carlito")),
        )
        val opts2 = OfficeOptions.builder()
            .timeout(Duration.ofSeconds(15))
            .maxInputBytes(1024)
            .fontDirectories(listOf(Path.of("/fonts")))
            .initialFontFamilies(listOf("Noto Sans CJK SC"))
            .fontFallbacks(listOf(listOf("Calibri", "Carlito")))
            .build()
        assertEquals(opts1, opts2)
        assertEquals(opts1.hashCode(), opts2.hashCode())
        assertEquals(emptyList(), OfficeOptions(fontFallbacks = emptyList()).fontFallbacks)

        assertFailsWith<IllegalArgumentException> { OfficeOptions(timeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { OfficeOptions(timeout = Duration.ofDays(30)) }
        assertFailsWith<IllegalArgumentException> { OfficeOptions(maxInputBytes = 0) }
        assertFailsWith<IllegalArgumentException> { OfficeOptions(fontFallbacks = listOf(listOf("OnlyOne"))) }

        val render1 = OfficeRenderOptions(sheet = "Summary", range = "A1:D20", dpi = 144, maxPages = 10)
        val render2 = OfficeRenderOptions.builder().sheet("Summary").range("A1:D20").dpi(144).maxPages(10).build()
        assertEquals(render1, render2)
        assertEquals(render1.hashCode(), render2.hashCode())
        assertNotEquals(OfficeRenderOptions.pages(1), OfficeRenderOptions.pages(2))

        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(pages = listOf(0)) }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(pages = listOf(1, 1)) }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(range = "A1:B2") }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(pages = listOf(1), sheet = "Summary") }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(dpi = 10) }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(maxPages = 0) }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(maxPixels = 16_777_217) }
        assertFailsWith<IllegalArgumentException> { OfficeRenderOptions(maxDimension = 0) }
    }

    @Test
    fun `standalone office aligns with dsoffice flags, capabilities, manifest rewriting, and failure parsing`(): Unit = runBlocking {
        val fixture = writeMockOfficeCli(root.resolve("mock-dsoffice.mjs"))
        val fonts = Files.createDirectories(root.resolve("fonts"))
        val config = OfficeConfig(
            cwd = root,
            command = RuntimeCommand.node(fixture),
            terminateGrace = Duration.ofMillis(500),
            defaultOptions = OfficeOptions(
                fontDirectories = listOf(fonts),
                initialFontFamilies = listOf("Noto Sans CJK SC"),
                fontFallbacks = emptyList(),
            ),
        )
        HarnessOffice(config).use { office ->
            val caps = office.capabilities()
            assertEquals("0.2.3", caps.runtime.version)
            assertEquals("wasm", caps.runtime.backend)
            assertEquals(false, caps.runtime.nativePreview)
            assertEquals(100, caps.imageRendering.maxPages)
            assertTrue(caps.recalculation.preservesFormulas)

            val input = Files.writeString(root.resolve("book.xlsx"), "stub")
            val csv = office.convert(
                input = Path.of("book.xlsx"),
                output = Path.of("out.csv"),
                sheet = "-2026",
                options = OfficeOptions(maxInputBytes = 4096, timeout = Duration.ofSeconds(15)),
            )
            assertEquals(root.resolve("out.csv"), csv.outputPath)
            assertEquals("wasm", csv.backend)
            val recorded = Json.parseToJsonElement(Files.readString(csv.outputPath)).jsonObject
            assertEquals("-2026", recorded.getValue("sheet").jsonPrimitive.content)
            assertEquals(fonts.toString(), recorded.getValue("fontDirectories").jsonArray.single().jsonPrimitive.content)
            assertEquals("Noto Sans CJK SC", recorded.getValue("initialFontFamilies").jsonArray.single().jsonPrimitive.content)
            assertEquals("[]", recorded.getValue("fontFallbacks").jsonPrimitive.content)
            assertEquals("4096", recorded.getValue("maxInputBytes").jsonPrimitive.content)
            assertEquals("15000", recorded.getValue("timeoutMs").jsonPrimitive.content)

            val rendered = office.renderImages(
                input = input,
                outputDirectory = Path.of("preview"),
                renderOptions = OfficeRenderOptions(sheet = "-2026", range = "A1:C3", dpi = 96, maxPages = 5, maxPixels = 1000, maxDimension = 500),
            )
            assertEquals(root.resolve("preview"), rendered.outputDirectory)
            assertEquals(1, rendered.schemaVersion)
            assertEquals("libreoffice", rendered.rasterEngine)
            assertEquals("deadbeef", rendered.sourceSha256)
            val image = rendered.images.single()
            assertEquals(1, image.index)
            assertEquals(root.resolve("preview/page-0001.png"), image.path)
            assertEquals(12, image.byteLength)
            assertEquals(true, image.rtl)
            assertEquals("-2026", image.sheet)
            assertEquals("A1:C3", image.range)
            val manifestText = Files.readString(rendered.outputDirectory.resolve("manifest.json"))
            assertTrue(manifestText.contains("\n  "), "manifest.json should stay pretty-printed")
            val manifest = Json.parseToJsonElement(manifestText).jsonObject
            assertEquals(image.path.toString(), manifest.getValue("images").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)

            // Trailing non-JSON stderr warning after the JSON failure line must not lose the error code.
            val err = assertFailsWith<OfficeException> {
                office.convert(Path.of("fail-with-warning.xlsx"), Path.of("fail.pdf"))
            }
            assertEquals("invalid-document", err.code)
            assertEquals(1, err.exitCode)
            assertEquals("corrupt workbook", err.message)

            // CLI --timeout-ms aborts cleanly before outer process timeout kills it.
            val cliTimeout = assertFailsWith<OfficeException> {
                office.convert(Path.of("slow-cli-timeout.xlsx"), Path.of("slow.pdf"), options = OfficeOptions(timeout = Duration.ofMillis(200)))
            }
            assertEquals("timeout", cliTimeout.code)
            assertEquals(1, cliTimeout.exitCode)
            assertTrue(Files.notExists(root.resolve("slow.pdf")))

            // Standalone HarnessOffice refuses bundled downloader calls cleanly.
            val noDownload = assertFailsWith<OfficeException> { office.downloadRuntime() }
            assertEquals("unavailable", noDownload.code)
        }
    }

    @Test
    fun `hung process timeout discards partial output and cleans staging directory`(): Unit = runBlocking {
        val script = Files.writeString(root.resolve("hang.mjs"), """
            import { writeFileSync } from 'node:fs'
            writeFileSync(process.argv[process.argv.indexOf('--output') + 1], 'partial')
            writeFileSync('started', '')
            setInterval(() => {}, 1000)
        """.trimIndent())
        val config = OfficeConfig(
            cwd = root,
            command = RuntimeCommand.node(script),
            terminateGrace = Duration.ofMillis(300),
        )
        HarnessOffice(config).use { office ->
            val output = root.resolve("target.pdf")
            assertFailsWith<RequestTimeoutException> {
                office.convert(root.resolve("source.doc"), output, options = OfficeOptions(timeout = Duration.ofMillis(500)))
            }
            assertTrue(Files.exists(root.resolve("started")))
            assertTrue(Files.notExists(output))
            assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".dshjk-office-") } })
        }
    }

    @Test
    fun `closing standalone or harness-bound office cancels active work without breaking parent harness`(): Unit = runBlocking {
        val script = Files.writeString(root.resolve("wait.mjs"), """
            import { writeFileSync } from 'node:fs'
            writeFileSync(process.argv[process.argv.indexOf('--output') + 1], 'partial')
            writeFileSync('pid', String(process.pid))
            setInterval(() => {}, 1000)
        """.trimIndent())

        // 1. Standalone close cancels active work and rejects subsequent calls.
        val standalone = HarnessOffice(OfficeConfig(cwd = root, command = RuntimeCommand.node(script), terminateGrace = Duration.ofMillis(500)))
        val job1 = async { standalone.convert(root.resolve("a.doc"), root.resolve("a.pdf")) }
        awaitFile(root.resolve("pid"))
        val pid1 = Files.readString(root.resolve("pid")).toLong()
        standalone.closeAsync()
        assertFailsWith<CancellationException> { job1.await() }
        assertFalse(ProcessHandle.of(pid1).map(ProcessHandle::isAlive).orElse(false))
        assertTrue(Files.notExists(root.resolve("a.pdf")))
        assertFailsWith<IllegalStateException> { standalone.capabilities() }

        // 2. Closing a harness-bound HarnessOffice leaves the parent DeepSeekHarness open.
        Files.deleteIfExists(root.resolve("pid"))
        DeepSeekHarness(HarnessConfig(cwd = root, terminateGrace = Duration.ofMillis(500))).use { harness ->
            val officeA = HarnessOffice(harness, RuntimeCommand.node(script))
            val officeB = HarnessOffice(harness, RuntimeCommand.node(writeMockOfficeCli(root.resolve("ok.mjs"))))
            val job2 = async { officeA.convert(root.resolve("b.doc"), root.resolve("b.pdf")) }
            awaitFile(root.resolve("pid"))
            officeA.closeAsync()
            assertFailsWith<CancellationException> { job2.await() }
            assertFailsWith<IllegalStateException> { officeA.capabilities() }
            // Parent harness and sibling office adapter remain usable.
            assertEquals("0.2.3", officeB.capabilities().runtime.version)

            // 3. Closing the parent harness cancels active Office work.
            Files.deleteIfExists(root.resolve("pid"))
            val officeC = HarnessOffice(harness, RuntimeCommand.node(script))
            val job3 = async { officeC.convert(root.resolve("c.doc"), root.resolve("c.pdf")) }
            awaitFile(root.resolve("pid"))
            harness.closeAsync()
            assertFailsWith<CancellationException> { job3.await() }
            assertTrue(Files.notExists(root.resolve("c.pdf")))
            assertFailsWith<IllegalStateException> { officeC.capabilities() }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Uses POSIX shell scripts and executable permissions")
    fun `sidecar validation, directory-on-PATH safety, and bundled download cache work offline`(): Unit = runBlocking {
        // 1. Relative DSH_OFFICE_SIDECAR rejects as unavailable (never misclassified as invalid-output).
        HarnessOffice(OfficeConfig(cwd = root, environment = mapOf("DSH_OFFICE_SIDECAR" to "relative/sidecar"))).use { office ->
            val err = assertFailsWith<OfficeException> { office.convert(Path.of("in.doc"), Path.of("out.pdf")) }
            assertEquals("unavailable", err.code)
        }

        // 2. Directory at sidecar node/bin/node is rejected as incomplete.
        val badSidecar = Files.createDirectories(root.resolve("bad-sidecar"))
        Files.createDirectories(badSidecar.resolve("node/bin/node"))
        val badCli = badSidecar.resolve("node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js")
        Files.createDirectories(badCli.parent)
        Files.writeString(badCli, "")
        HarnessOffice(OfficeConfig(cwd = root, environment = mapOf("DSH_OFFICE_SIDECAR" to badSidecar.toString()))).use { office ->
            val err = assertFailsWith<OfficeException> { office.capabilities() }
            assertEquals("unavailable", err.code)
        }

        // 3. Valid sidecar works without any dsh runtime and without host PATH.
        val goodSidecar = createMockSidecar(root.resolve("good-sidecar"))
        HarnessOffice(OfficeConfig(
            cwd = root,
            environment = mapOf("DSH_OFFICE_SIDECAR" to goodSidecar.toString()),
            inheritEnvironment = false,
        )).use { office ->
            assertEquals("0.2.3", office.capabilities().runtime.version)
        }

        // 4. A directory named 'node' earlier on PATH must not shadow the real node binary.
        val decoyBin = Files.createDirectories(root.resolve("decoy-bin"))
        Files.createDirectories(decoyBin.resolve("node"))
        val nodeOnlyBin = Files.createDirectories(root.resolve("node-only-bin"))
        Files.createSymbolicLink(nodeOnlyBin.resolve("node"), locateSystemNode())
        val isolated = Files.createDirectories(root.resolve("empty-project"))
        val pathEnv = "$decoyBin${File.pathSeparator}$nodeOnlyBin"
        HarnessOffice(OfficeConfig(
            cwd = isolated,
            environment = mapOf("PATH" to pathEnv),
            inheritEnvironment = false,
        )).use { office ->
            val err = assertFailsWith<OfficeException> { office.capabilities() }
            assertEquals("unavailable", err.code)
            assertContains(err.message!!, "LibreOffice Kit was not found")
        }

        // 5. Bundled executable manifest, empty HOME fallback, downloadRuntime(), and cache reuse.
        val bundledDir = Files.createDirectories(root.resolve("bundled"))
        val identity = "a".repeat(64)
        val manifestDir = Files.createDirectories(bundledDir.resolve("test-arch"))
        Files.writeString(manifestDir.resolve("downloads.json"), """{"identity":"$identity"}""")
        val cacheRoot = root.resolve("resource-cache")
        val mockSidecarTemplate = goodSidecar
        val fakeExe = bundledDir.resolve("deepseek-harness-sdk-runtime-test-arch")
        Files.writeString(fakeExe, """
            #!/bin/sh
            set -e
            if [ "${'$'}DSH_RUNTIME_DOWNLOAD" != "office" ]; then
              echo "unexpected invocation" >&2
              exit 2
            fi
            target="${'$'}DSH_RESOURCE_CACHE/$identity/office"
            mkdir -p "${'$'}target"
            cp -R "$mockSidecarTemplate/." "${'$'}target/"
            printf '%s' "$identity" > "${'$'}target/complete"
            printf '"%s"\n' "${'$'}target"
        """.trimIndent() + "\n")
        assertTrue(fakeExe.toFile().setExecutable(true))

        // 5a. Empty HOME without DSH_RESOURCE_CACHE must not report a bogus DSH_RESOURCE_CACHE error.
        DeepSeekHarness(HarnessConfig(
            cwd = root,
            runtime = RuntimeCommand.executable(fakeExe.toString()),
            environment = mapOf("HOME" to ""),
            inheritEnvironment = false,
        )).use { harness ->
            val err = assertFailsWith<OfficeException> { HarnessOffice(harness).capabilities() }
            assertEquals("unavailable", err.code)
            assertContains(err.message!!, "Call office.downloadRuntime() first")
        }

        // 5b. With DSH_RESOURCE_CACHE: missing -> unavailable -> downloadRuntime() -> capabilities() succeeds!
        DeepSeekHarness(HarnessConfig(
            cwd = root,
            runtime = RuntimeCommand.executable(fakeExe.toString()),
            environment = mapOf("DSH_RESOURCE_CACHE" to cacheRoot.toString()),
            inheritEnvironment = false,
        )).use { harness ->
            val office = HarnessOffice(harness)
            assertEquals("unavailable", assertFailsWith<OfficeException> { office.capabilities() }.code)
            val downloaded = office.downloadRuntime()
            assertEquals(cacheRoot.resolve(identity).resolve("office"), downloaded)
            assertEquals("0.2.3", office.capabilities().runtime.version)

            // Corrupted complete marker rejects again.
            Files.writeString(downloaded.resolve("complete"), "b".repeat(64))
            assertEquals("unavailable", assertFailsWith<OfficeException> { office.capabilities() }.code)
        }
    }

    private fun createMockSidecar(dir: Path): Path {
        val nodeBin = dir.resolve("node/bin/node")
        Files.createDirectories(nodeBin.parent)
        val systemNode = locateSystemNode()
        Files.createSymbolicLink(nodeBin, systemNode)
        val cli = dir.resolve("node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js")
        Files.createDirectories(cli.parent)
        writeMockOfficeCli(cli)
        return dir
    }

    private fun locateSystemNode(): Path {
        val path = System.getenv("PATH").orEmpty()
        return path.split(File.pathSeparatorChar).asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it).resolve("node") }
            .first { Files.isRegularFile(it) && Files.isExecutable(it) }
            .toRealPath()
    }

    private fun writeMockOfficeCli(path: Path): Path = Files.writeString(path, """
        import { mkdirSync, writeFileSync } from 'node:fs'
        import { basename, join } from 'node:path'
        import { parseArgs } from 'node:util'

        const { values, positionals } = parseArgs({
          allowPositionals: true,
          strict: true,
          options: {
            json: { type: 'boolean' },
            input: { type: 'string' },
            output: { type: 'string' },
            'output-dir': { type: 'string' },
            sheet: { type: 'string' },
            range: { type: 'string' },
            pages: { type: 'string' },
            dpi: { type: 'string' },
            'max-pages': { type: 'string' },
            'max-pixels': { type: 'string' },
            'max-dimension': { type: 'string' },
            'timeout-ms': { type: 'string' },
            'max-input-bytes': { type: 'string' },
            'max-output-bytes': { type: 'string' },
            'max-image-resolution': { type: 'string' },
            'max-archive-entries': { type: 'string' },
            'max-uncompressed-bytes': { type: 'string' },
            'font-directory': { type: 'string', multiple: true },
            'initial-font-family': { type: 'string', multiple: true },
            'font-fallbacks': { type: 'string' },
            'max-font-files': { type: 'string' },
            'max-font-file-bytes': { type: 'string' },
            'max-loaded-font-bytes': { type: 'string' },
          },
        })
        const cmd = positionals[0]
        if (cmd === 'capabilities') {
          if (Object.keys(values).some((k) => k !== 'json')) {
            console.error(JSON.stringify({ code: 'failed', error: 'capabilities accepts only --json.' }))
            process.exit(1)
          }
          process.stdout.write(JSON.stringify({
            runtime: {
              version: '0.2.3',
              backend: 'wasm',
              nativePreview: false,
              cliPath: process.argv[1],
              nodeApiPath: process.argv[1],
            },
            conversions: [
              { inputs: ['doc', 'docx', 'odt'], outputs: ['pdf', 'docx', 'odt', 'txt'] },
              { inputs: ['xls', 'xlsx', 'ods'], outputs: ['pdf', 'xlsx', 'ods', 'csv'] },
            ],
            imageRendering: {
              inputs: ['doc', 'docx', 'xlsx', 'pdf'],
              output: 'png',
              officeBackend: 'libreoffice',
              pdfBackend: 'pdfium',
              maxPages: 100,
              maxPixels: 16777216,
              maxDimension: 8192,
            },
            recalculation: { inputs: ['xls', 'xlsx', 'ods'], outputs: ['xlsx', 'ods'], preservesFormulas: true },
          }) + '\n')
          process.exit(0)
        }
        const name = basename(values.input)
        if (name === 'fail-with-warning.xlsx') {
          process.stderr.write(JSON.stringify({ code: 'invalid-document', error: 'corrupt workbook' }) + '\n')
          process.stderr.write('Warning: trailing cleanup diagnostic\n')
          process.exit(1)
        }
        if (name === 'slow-cli-timeout.xlsx') {
          setTimeout(() => {
            process.stderr.write(JSON.stringify({ code: 'timeout', error: 'LibreOffice conversion timed out.' }) + '\n')
            process.exit(1)
          }, Number(values['timeout-ms']))
        } else if (cmd === 'convert' || cmd === 'recalculate') {
          writeFileSync(values.output, JSON.stringify({
            sheet: values.sheet ?? null,
            fontDirectories: values['font-directory'] ?? [],
            initialFontFamilies: values['initial-font-family'] ?? [],
            fontFallbacks: values['font-fallbacks'] ?? null,
            maxInputBytes: values['max-input-bytes'] ?? null,
            timeoutMs: values['timeout-ms'] ?? null,
          }))
          process.stdout.write(JSON.stringify({
            backend: 'wasm',
            outputPath: values.output,
            missingFonts: [],
            ...(values.output.endsWith('.pdf') ? { pageCount: 1 } : {}),
          }) + '\n')
        } else if (cmd === 'render') {
          mkdirSync(values['output-dir'])
          const img = join(values['output-dir'], 'page-0001.png')
          writeFileSync(img, '0123456789ab')
          const manifest = {
            schemaVersion: 1,
            backend: 'wasm',
            rasterEngine: 'libreoffice',
            source: 'saved',
            inputPath: values.input,
            sourceSha256: 'deadbeef',
            dpi: Number(values.dpi ?? 144),
            pageCount: 1,
            missingFonts: [],
            images: [{
              index: 1,
              path: img,
              width: 200,
              height: 100,
              rectangle: { x: 0, y: 0, width: 200, height: 100 },
              byteLength: 12,
              ...(values.sheet ? { sheet: values.sheet, range: values.range ?? 'A1:A1', rtl: true } : { page: 1 }),
            }],
          }
          writeFileSync(join(values['output-dir'], 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
          process.stdout.write(JSON.stringify(manifest) + '\n')
        }
    """.trimIndent())

    private suspend fun awaitFile(path: Path) = withTimeout(5_000) {
        while (!Files.exists(path) || Files.size(path) == 0L) delay(20)
    }
}
