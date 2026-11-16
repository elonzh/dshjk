package com.elonzh.dshjk

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.*
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.error.RequestTimeoutException
import com.elonzh.dshjk.protocol.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Upstream CLI, local Messages endpoint, disposable home, no external credentials. */
@EnabledIf("hasNativeRuntime")
@Timeout(90)
class NativeRuntimeTest {
    companion object {
        private val cli = System.getenv("DSHJK_TEST_CLI")?.takeIf(String::isNotBlank)
        private val bundled = java.lang.Boolean.getBoolean("dshjk.test.bundledRuntime")
        @JvmStatic fun hasNativeRuntime() = cli != null || bundled
    }

    @TempDir lateinit var root: Path
    private val runtime = cli?.let { RuntimeCommand.node(Path.of(it)) } ?: RuntimeCommand.automatic
    private fun response(text: String, stopReason: String = "end_turn") = listOf(
        """{"type":"message_start","message":{"id":"native-fixture","model":"deepseek-v4-flash","usage":{"input_tokens":3,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"$text"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
    ).joinToString("") { "data: $it\n\n" }
    @Test fun `minimal profile runs concurrent sessions restores history and forwards model settings`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(HarnessProtocol.json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject)
            val bytes = response("native hello").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val config = nativeConfig("sdk-minimal", server.address.port)
        try {
            val h = DeepSeekHarness(config)
            val session = h.session()
            try {
                assertEquals("deepseek-harness-sdk-runtime", h.start().serverInfo.name)
                if (bundled) {
                    val executable = ProcessHandle.of(checkNotNull(h.client.pid)).orElseThrow().info().command().orElseThrow()
                    assertTrue(Path.of(executable).toRealPath()
                        .startsWith(Path.of(System.getProperty("dshjk.runtime.cacheDir")).toRealPath()))
                }
                assertTrue(session.id.matches(Regex("session-[0-9a-f]{32}")))
                assertEquals(root.toRealPath(), Path.of(session.getWorkingDirectory()).toRealPath())
                val subdir = Files.createDirectory(root.resolve("subdir"))
                assertEquals(subdir.toRealPath(), Path.of(session.setWorkingDirectory("subdir")).toRealPath())
                val results = listOf(async { session.run("First") }, async { h.session("second").run("Second") }).awaitAll()
                assertTrue(results.all { it.finalResponse == "native hello" && it.finishReason == FinishReason.Builtin.COMPLETED })
                assertTrue(results.all { it.events.any { e -> e.type == SessionEventType.Builtin.AGENT_INBOX_SPLICED } })
                assertEquals(2, requests.size)
                assertEquals("deepseek-v4-flash", requests.first()["model"]?.jsonPrimitive?.content)
                assertEquals(1234, requests.first()["max_tokens"]?.jsonPrimitive?.int)
            } finally { h.closeAsync() }
            DeepSeekHarness(config).use { restored ->
                val restoredSession = restored.resumeSession(session.id)
                assertEquals(root.resolve("subdir").toRealPath(), Path.of(restoredSession.getWorkingDirectory()).toRealPath())
                assertEquals("native hello", restoredSession.run("Continue").finalResponse)
                assertTrue(requests.last()["messages"]!!.jsonArray.size > requests.first()["messages"]!!.jsonArray.size)
            }
        } finally { server.stop(0) }
    }
    @Test fun `full profile routes state commands and returns max token outcome`(): Unit = runBlocking {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes(); requests++
            val bytes = response("bounded", if (requests == 1) "max_tokens" else "end_turn").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DeepSeekHarness(nativeConfig("sdk", server.address.port)).use { h ->
                val s = h.session("full")
                val command = s.run("/permission danger-full-access")
                assertTrue(command.events.any { it.data is SessionEventData.CommandRun })
                assertTrue(command.events.any { it.data is SessionEventData.CommandDone })
                assertEquals(FinishReason.Builtin.COMPLETED, command.finishReason)
                assertTrue(command.events.any { (it.data as? SessionEventData.CommandDone)?.kind == CommandStatus.SUCCESS })
                assertEquals(0, requests)
                val r = s.run("One turn")
                assertEquals("bounded", r.finalResponse)
                assertEquals(FinishReason.Builtin.MAX_TOKENS, r.finishReason)
                val compact = s.run("/compact")
                assertTrue(compact.events.any { (it.data as? SessionEventData.CommandDone)?.kind == CommandStatus.SUCCESS }, compact.toString())
                assertEquals(FinishReason.Builtin.COMPLETED, compact.finishReason)
            }
        } finally { server.stop(0) }
    }
    @Test fun `native user question reaches host and its answer returns to model`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val questions = CopyOnWriteArrayList<UserQuestionRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(HarnessProtocol.json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject)
            val body = if (requests.size == 1) listOf(
                """{"type":"message_start","message":{"id":"question","model":"deepseek-v4-flash","usage":{"input_tokens":3,"output_tokens":0}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"q-call","name":"ask_user_question","input":{"questions":[{"id":"format","question":"Which format?","options":[{"label":"CSV"},{"label":"JSON"}]}]}}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "data: $it\n\n" } else response("answer received")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DeepSeekHarness(nativeConfig("sdk", server.address.port, UserQuestionHandler { request, _ ->
                questions.add(request)
                UserQuestionResult.Answer(listOf(AnswerItem("format", listOf("CSV"))))
            })).use { h ->
                val r = h.run("Ask which format", "native-question")
                assertEquals("answer received", r.finalResponse)
                assertEquals("native-question", questions.single().sessionId)
                assertEquals("format", questions.single().questions.single().id)
                assertEquals(2, requests.size)
                assertTrue(requests.last()["messages"].toString().contains("CSV"))
                assertTrue(r.events.any { it.type == SessionEventType.Builtin.TOOL_RESULT })
            }
        } finally { server.stop(0) }
    }
    @Test fun `native home discovery honors environment default and explicit override`(): Unit = runBlocking {
        data class HomeCase(val name: String, val environmentHome: String?, val explicit: Boolean)
        val cases = listOf(HomeCase("default", null, false), HomeCase("blank", "  ", false),
            HomeCase("environment", "environment-home", false), HomeCase("explicit", "environment-home", true))
        for (case in cases) {
            val workspace = Files.createDirectories(root.resolve(case.name))
            val userHome = Files.createDirectories(workspace.resolve("user"))
            val environmentHome = workspace.resolve("environment-home")
            val explicitHome = workspace.resolve("explicit-home")
            val env = System.getenv().toMutableMap().apply {
                remove("DSH_HOME")
                put("HOME", userHome.toString()); put("USERPROFILE", userHome.toString())
                put("DSH_AGENTS_HOME", workspace.resolve("agents").toString())
                put("DEEPSEEK_API_KEY", "local-fixture"); put("DSH_TELEMETRY_DISABLED", "1")
                case.environmentHome?.let { put("DSH_HOME", if (it.isBlank()) it else environmentHome.toString()) }
            }
            val config = HarnessConfig(
                dshHome = if (case.explicit) explicitHome else null,
                cwd = workspace, runtime = runtime,
                profile = "sdk-minimal", environment = env, inheritEnvironment = false,
            )
            val expectedHome = when {
                case.explicit -> explicitHome
                !case.environmentHome.isNullOrBlank() -> environmentHome
                else -> userHome.resolve(".dsh")
            }
            DeepSeekHarness(config).use { h ->
                val session = h.session()
                assertTrue(session.id.matches(Regex("session-[0-9a-f]{32}")))
                assertEquals(workspace.toRealPath(), Path.of(session.getWorkingDirectory()).toRealPath())
            }
            assertTrue(Files.isDirectory(expectedHome.resolve("profiles/sdk-minimal")), case.name)
            if (case.explicit) assertFalse(Files.exists(environmentHome))
        }
    }
    @Test fun `CLI uses the configured launch context and native patch order`(): Unit = runBlocking {
        val config = cliConfig()
        val input = Files.writeString(root.resolve("input"), "stdin payload")
        val output = root.resolve("output")
        val patchFile = Files.writeString(root.resolve("command.json"),
            JsonArray(listOf(HarnessPatch(id = "fixture", config = mapOf("layer" to "file")).toJson())).toString())
        DeepSeekHarness(config).use { harness ->
            val reply = harness.cli.execute(
                ProfileInvocation("fixture", listOf("echo", "--from JVM"),
                    patches = listOf(HarnessPatch(id = "fixture", config = mapOf("layer" to "inline"))),
                    patchFiles = listOf(patchFile)),
                input = input, output = output,
            )
            assertEquals(0, reply.exitCode, reply.stderr)
            assertNull(reply.stdout)
            val payload = HarnessProtocol.json.parseToJsonElement(Files.readString(output)).jsonObject
            assertEquals("--from JVM", payload["value"]?.jsonPrimitive?.content)
            assertEquals("stdin payload", payload["input"]?.jsonPrimitive?.content)
            assertEquals("configured", payload["token"]?.jsonPrimitive?.content)
            assertEquals("file", payload["layer"]?.jsonPrimitive?.content)
            assertEquals(config.processCwd.toRealPath(), Path.of(payload["cwd"]!!.jsonPrimitive.content).toRealPath())
            assertEquals(config.dshHome.toString(), payload["home"]?.jsonPrimitive?.content)
            assertNull(harness.client.pid)

            val failed = harness.cli.execute(ProfileInvocation("fixture", listOf("missing")))
            assertEquals(2, failed.exitCode)
            assertContains(failed.stderr, "Unknown fixture command")
            assertFailsWith<CliException> { harness.cli.execute(ProfileInvocation("fixture", listOf("flood"))) }
        }
    }

    @Test fun `timeout cancellation and harness close stop CLI processes and their children`(): Unit = runBlocking {
        val harness = DeepSeekHarness(cliConfig())
        val cli = harness.cli
        try {
            val timedOut = root.resolve("timed-out-child")
            assertFailsWith<RequestTimeoutException> {
                cli.execute(ProfileInvocation("fixture", listOf("hang", timedOut.toString())),
                    timeout = Duration.ofSeconds(5))
            }
            assertChildExited(timedOut)

            val cancelled = root.resolve("cancelled-child")
            val command = async { cli.execute(ProfileInvocation("fixture", listOf("hang", cancelled.toString()))) }
            awaitChildPid(cancelled)
            command.cancelAndJoin()
            assertChildExited(cancelled)

            val closing = root.resolve("closing-child")
            val running = async { cli.execute(ProfileInvocation("fixture", listOf("hang", closing.toString()))) }
            awaitChildPid(closing)
            harness.closeAsync()
            assertFailsWith<CancellationException> { running.await() }
            assertChildExited(closing)
            assertFailsWith<IllegalStateException> { cli.execute(ProfileInvocation("fixture")) }
        } finally {
            harness.closeAsync()
        }
    }

    private suspend fun awaitChildPid(path: Path) = withTimeout(10_000) {
        while (!Files.exists(path) || Files.size(path) == 0L) delay(20)
    }

    private fun assertChildExited(pidFile: Path) {
        val pid = Files.readString(pidFile).toLong()
        val child = ProcessHandle.of(pid).orElse(null)
        try {
            assertFalse(child?.isAlive == true, "CLI child $pid is still alive")
        } finally {
            if (child?.isAlive == true) child.destroyForcibly()
        }
    }

    private fun cliConfig(): HarnessConfig {
        val home = root.resolve("cli-home")
        val dir = Files.createDirectories(home.resolve("profiles/fixture"))
        Files.writeString(dir.resolve("package.json"), """{"private":true,"dsh":{"profile":{"bundles":[]}}}""")
        val module = root.resolve("cli-fixture.mjs")
        checkNotNull(javaClass.getResourceAsStream("/cli-fixture.mjs")).use { Files.copy(it, module) }
        val plugin = HarnessPlugin("fixture", module.toUri().toString(), mapOf("layer" to "home"))
        Files.writeString(home.resolve("cordis.patch.yml"),
            JsonArray(listOf(HarnessPatch(insert = listOf(plugin)).toJson())).toString())
        return HarnessConfig(
            dshHome = home, cwd = root, processCwd = Files.createDirectory(root.resolve("process")),
            runtime = runtime,
            environment = mapOf("DSHJK_TEST_TOKEN" to "configured", "DSH_TELEMETRY_DISABLED" to "1"),
        )
    }

    private fun nativeConfig(profile: String, port: Int, question: UserQuestionHandler? = null) = HarnessConfig(
        dshHome = root.resolve("home-$profile"), cwd = root,
        runtime = runtime, profile = profile,
        environment = mapOf("DEEPSEEK_API_KEY" to "local-fixture", "DEEPSEEK_BASE_URL" to "http://127.0.0.1:$port",
            "DSH_TELEMETRY_DISABLED" to "1", "DSH_MAX_TOKENS_AS_SUCCESS" to "false", "DSH_PERMISSION_MODE" to "danger-full-access"),
        userQuestionHandler = question, reasoningEffort = "max", maxTokens = 1234,
        initializeTimeout = Duration.ofSeconds(30), requestTimeout = Duration.ofSeconds(30),
    )
}
