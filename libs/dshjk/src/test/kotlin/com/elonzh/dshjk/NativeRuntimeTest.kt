package com.elonzh.dshjk

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.*
import com.elonzh.dshjk.protocol.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Unmodified published CLI, local Messages endpoint, disposable home, no external credentials. */
@EnabledIfEnvironmentVariable(named = "DSHJK_TEST_CLI", matches = ".+")
@Timeout(90)
class NativeRuntimeTest {
    @TempDir lateinit var root: Path
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
            try {
                assertEquals("deepseek-harness-sdk-runtime", h.start().serverInfo.name)
                val session = h.session("native-persisted")
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
                val session = restored.resumeSession("native-persisted")
                assertEquals(root.resolve("subdir").toRealPath(), Path.of(session.getWorkingDirectory()).toRealPath())
                assertEquals("native hello", session.run("Continue").finalResponse)
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
    private fun nativeConfig(profile: String, port: Int, question: UserQuestionHandler? = null) = HarnessConfig(
        dshHome = root.resolve("home-$profile"), cwd = root,
        runtime = RuntimeCommand.node(Path.of(System.getenv("DSHJK_TEST_CLI"))), profile = profile,
        environment = mapOf("DEEPSEEK_API_KEY" to "local-fixture", "DEEPSEEK_BASE_URL" to "http://127.0.0.1:$port",
            "DSH_TELEMETRY_DISABLED" to "1", "DSH_MAX_TOKENS_AS_SUCCESS" to "false", "DSH_PERMISSION_MODE" to "danger-full-access"),
        userQuestionHandler = question, reasoningEffort = "max", maxTokens = 1234, initializeTimeout = Duration.ofSeconds(30), requestTimeout = Duration.ofSeconds(30),
    )
}
