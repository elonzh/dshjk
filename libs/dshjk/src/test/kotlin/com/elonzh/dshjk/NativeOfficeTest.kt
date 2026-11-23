package com.elonzh.dshjk

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.office.HarnessOffice
import com.elonzh.dshjk.office.OfficeConfig
import com.elonzh.dshjk.office.OfficeException
import com.elonzh.dshjk.office.OfficeRenderOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledIf("hasOfficeRuntime")
@Timeout(180)
class NativeOfficeTest {
    companion object {
        private val cli = System.getenv("DSHJK_TEST_CLI")?.takeIf(String::isNotBlank)
        @JvmStatic fun hasNpmRuntime() = cli != null
        @JvmStatic fun hasOfficeRuntime() = cli != null || (
            java.lang.Boolean.getBoolean("dshjk.test.bundledRuntime") && !System.getenv("DSH_OFFICE_SIDECAR").isNullOrBlank()
        )
    }

    @TempDir lateinit var root: Path

    @Test
    fun `harness-bound office operations use the local kit without starting a session`(): Unit = runBlocking {
        val config = HarnessConfig(cwd = root, profile = "sdk-minimal", runtime = cli?.let {
            RuntimeCommand.node(Path.of(it))
        } ?: HarnessConfig().runtime)
        DeepSeekHarness(config).use { harness ->
            val office = HarnessOffice(harness)
            val caps = office.capabilities()
            assertTrue(caps.runtime.backend in setOf("native", "wasm"))
            assertTrue(caps.runtime.version.isNotBlank())

            val input = Path.of("budget 表.xlsx")
            workbook(root.resolve(input))
            val pdf = Path.of("budget export.pdf")
            val conversion = office.convert(input, pdf)
            assertEquals(root.resolve(pdf), conversion.outputPath)
            assertEquals(2, conversion.pageCount)
            assertEquals("%PDF-", Files.newInputStream(conversion.outputPath).use {
                String(it.readNBytes(5), Charsets.US_ASCII)
            })

            val csv = office.convert(input, Path.of("dash-sheet.csv"), sheet = "-2026")
            assertContains(Files.readString(csv.outputPath), "42")

            val recalculated = office.recalculate(input, Path.of("recalculated.xlsx"))
            ZipFile(recalculated.outputPath.toFile()).use { workbook ->
                val sheet = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().use { it.readText() }
                assertContains(sheet, ">A1*B1</f>")
                assertContains(sheet, "<v>42</v>")
            }

            val rendered = office.renderImages(pdf, Path.of("images"), OfficeRenderOptions(pages = listOf(1), dpi = 72))
            val image = rendered.images.single()
            assertEquals(1, rendered.schemaVersion)
            assertEquals("pdfium", rendered.rasterEngine)
            assertEquals(1, image.index)
            assertEquals(1, image.page)
            assertEquals(72.0, rendered.dpi)
            assertEquals(rendered.outputDirectory, image.path.parent)
            assertTrue(image.byteLength > 0 && Files.isRegularFile(image.path))
            val manifest = Json.parseToJsonElement(Files.readString(rendered.outputDirectory.resolve("manifest.json"))).jsonObject
            assertEquals(image.path.toString(), manifest.getValue("images").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)

            val sheetRendered = office.renderImages(
                recalculated.outputPath,
                Path.of("sheet-images"),
                OfficeRenderOptions(sheet = "-2026", range = "A1:C1", dpi = 72),
            )
            assertEquals("libreoffice", sheetRendered.rasterEngine)
            val sheetImage = sheetRendered.images.single()
            assertEquals("-2026", sheetImage.sheet)
            assertEquals("A1:C1", sheetImage.range)
            assertTrue(sheetImage.width > 0 && sheetImage.height > 0 && Files.isRegularFile(sheetImage.path))

            val before = Files.readAllBytes(conversion.outputPath)
            assertEquals("invalid-output", assertFailsWith<OfficeException> { office.convert(input, pdf) }.code)
            assertTrue(before.contentEquals(Files.readAllBytes(conversion.outputPath)))
            val invalid = Files.writeString(root.resolve("broken.xlsx"), "not a workbook")
            val badOutput = root.resolve("broken.pdf")
            assertEquals("invalid-document", assertFailsWith<OfficeException> {
                office.convert(invalid, badOutput)
            }.code)
            assertTrue(Files.notExists(badOutput))
            assertNull(harness.client.pid)
        }
    }

    @Test
    @EnabledIf("hasNpmRuntime")
    fun `standalone HarnessOffice discovers libreoffice-kit from processCwd without a DeepSeekHarness`(): Unit = runBlocking {
        // Point processCwd at the npm project containing @deepseek-ai/libreoffice-kit; no DeepSeekHarness or dsh config is created.
        val npmProject = Path.of(requireNotNull(cli)).toAbsolutePath().normalize().parent.parent.parent.parent.parent
        val input = Path.of("standalone.xlsx")
        workbook(root.resolve(input))
        HarnessOffice(OfficeConfig(cwd = root, processCwd = npmProject)).use { office ->
            val caps = office.capabilities()
            assertTrue(caps.runtime.backend in setOf("native", "wasm"))
            val pdf = office.convert(input, Path.of("standalone.pdf"))
            assertEquals(2, pdf.pageCount)
            assertTrue(Files.isRegularFile(pdf.outputPath))
        }
    }

    private fun workbook(path: Path) {
        val parts = mapOf(
            "[Content_Types].xml" to """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
            """,
            "_rels/.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """,
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Budget" sheetId="1" r:id="rId1"/>
                    <sheet name="-2026" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
            """,
            "xl/_rels/workbook.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>
            """,
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1">
                    <c r="A1"><v>6</v></c><c r="B1"><v>7</v></c><c r="C1"><f>A1*B1</f><v>0</v></c>
                  </row></sheetData>
                </worksheet>
            """,
            "xl/worksheets/sheet2.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1">
                    <c r="A1"><v>6</v></c><c r="B1"><v>7</v></c><c r="C1"><f>A1*B1</f><v>42</v></c>
                  </row></sheetData>
                </worksheet>
            """,
        )
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            parts.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.trimIndent().toByteArray())
                output.closeEntry()
            }
        }
    }
}
