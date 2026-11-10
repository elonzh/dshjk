package com.elonzh.dshjk

import com.elonzh.dshjk.client.HarnessClient
import com.elonzh.dshjk.configuration.*
import com.elonzh.dshjk.error.TransportClosedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@Timeout(20)
class PatchConfigurationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `typed operations become ordered launch files and are removed after a crashed child exits`(): Unit = runBlocking {
        val raw = root.resolve("last.json")
        Files.writeString(raw, """[{"id":"example","disabled":true}]""")
        val settings = mutableMapOf<String, Any?>("message" to "quoted \" text 😀", "count" to 3, "enabled" to true)
        val config = HarnessConfig(
            cwd = root, dshHome = root.resolve("home"),
            runtime = RuntimeCommand.node(Path.of(javaClass.getResource("/runtime.mjs")!!.toURI())),
            patches = listOf(
                HarnessPatch(insert = listOf(HarnessPlugin("example", "example-plugin", settings))),
                HarnessPatch(id = "example", disabled = false),
                HarnessPatch.providers(mapOf("gateway" to LlmProviderConfig(
                    api = LlmApi.OPENAI_COMPLETIONS, baseUrl = URI.create("https://gateway.example/v1"),
                    apiKeyEnv = "GATEWAY_KEY", models = listOf(LlmModelConfig("my-model", 131072, 8192)),
                ))),
            ),
            patchFiles = listOf(raw),
        )
        settings["enabled"] = false
        lateinit var generated: Path
        HarnessClient(config).use { client ->
            assertNull(client.pid)
            val launch = client.request("launch-config").jsonObject
            val paths = launch.getValue("paths").jsonArray.map { Path.of(it.jsonPrimitive.content) }
            generated = paths[0]
            assertEquals(listOf(generated, raw), paths)
            val operations = launch.getValue("patches").jsonArray[0].jsonArray
            val inserted = operations[0].jsonObject.getValue("insert").jsonArray[0].jsonObject.getValue("config").jsonObject
            assertEquals("quoted \" text 😀", inserted.getValue("message").jsonPrimitive.content)
            assertEquals(true, inserted.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(3, inserted.getValue("count").jsonPrimitive.int)
            assertEquals(false, operations[1].jsonObject.getValue("disabled").jsonPrimitive.boolean)
            assertTrue(Files.exists(generated))
            assertFailsWith<TransportClosedException> { client.request("crash") }
        }
        assertFalse(Files.exists(generated))
        assertFalse(Files.exists(generated.parent))
        assertTrue(Files.exists(raw))
    }

    @Test
    fun `invalid operation and plugin JSON fail before launching a runtime`() {
        assertFailsWith<IllegalArgumentException> { HarnessPatch() }
        assertFailsWith<IllegalArgumentException> { HarnessPatch(id = "", disabled = true) }
        assertFailsWith<IllegalArgumentException> { LlmProviderConfig(baseUrl = URI.create("file:/tmp/model")) }
        assertFailsWith<IllegalArgumentException> {
            HarnessConfig(patches = listOf(HarnessPatch(id = "example", config = mapOf("callback" to Runnable {}))))
        }
    }
}
