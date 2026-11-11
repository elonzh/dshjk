package example.spring

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.protocol.HarnessProtocol
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Import
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Real Boot HTTP/SSE, official Spring AI MCP server, unmodified dsh and a local model endpoint. */
@EnabledIf("hasNativeRuntime")
@Timeout(90)
class NativeSpringAiIntegrationTest {
    companion object {
        private val cli = System.getenv("DSHJK_TEST_CLI")?.takeIf(String::isNotBlank)
        @JvmStatic fun hasNativeRuntime() = cli != null || java.lang.Boolean.getBoolean("dshjk.test.bundledRuntime")
    }

    @TempDir
    lateinit var root: Path
    private val runtimeArgs = cli?.let { arrayOf("--dshjk.runtime.cli=$it") } ?: emptyArray()

    @Test
    fun managedRuntimeCallsSpringAiToolThroughMcpAndStreamsNativeEvents() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            requests.add(HarnessProtocol.json.parseToJsonElement(body).jsonObject)
            val tool = requests.size % 2 == 1
            val content = if (tool) {
                """{"type":"tool_use","id":"spring-add","name":"mcp__application__add","input":{"left":20,"right":22}}"""
            } else {
                """{"type":"text","text":"The sum is 42."}"""
            }
            val response = response(content, if (tool) "tool_use" else "end_turn").toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        model.start()
        try {
            (SpringApplication.run(
                ToolsServer::class.java,
                "--server.port=0", "--server.address=127.0.0.1", "--dshjk.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN",
            ) as ServletWebServerApplicationContext).use { tools ->
                val toolPort = checkNotNull(tools.webServer).port
                val runtimePid: Long
                (SpringApplication.run(
                    HarnessApplication::class.java,
                    "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                    "--dshjk.profile=sdk-minimal", *runtimeArgs,
                    "--dshjk.dsh-home=${root.resolve("home")}", "--dshjk.cwd=$root",
                    "--dshjk.mcp.application.url=http://127.0.0.1:$toolPort/mcp",
                    "--dshjk.mcp.application.startup-timeout=15s",
                    "--dshjk.environment.DEEPSEEK_API_KEY=local-fixture",
                    "--dshjk.environment.DEEPSEEK_BASE_URL=http://127.0.0.1:${model.address.port}",
                    "--dshjk.environment.DSH_TELEMETRY_DISABLED=1",
                ) as ServletWebServerApplicationContext).use { app ->
                    val harness = app.getBean(DeepSeekHarness::class.java)
                    assertThat(harness.client.pid).isNull()
                    val http = HttpClient.newHttpClient()
                    val base = "http://127.0.0.1:${checkNotNull(app.webServer).port}"
                    assertThat(post(http, "$base/run", """{"text":""}""").statusCode()).isEqualTo(400)
                    assertThat(post(http, "$base/run", "{}").statusCode()).isEqualTo(400)
                    assertThat(harness.client.pid).isNull()
                    val normal = post(http, "$base/run")
                    assertThat(normal.statusCode()).isEqualTo(200)
                    assertThat(normal.body()).contains("The sum is 42.", "session-", "completed")
                    assertToolRoundTrip(requests.subList(0, 2))

                    val streamed = post(http, "$base/run/events")
                    assertThat(streamed.statusCode()).isEqualTo(200)
                    assertThat(streamed.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream")
                    assertThat(streamed.body()).contains("event:session.event", "tool/call", "tool/result",
                        "mcp__application__add", "The sum is 42.", "event:result")
                    assertThat(streamed.body().indexOf("tool/call")).isLessThan(streamed.body().indexOf("tool/result"))
                    assertToolRoundTrip(requests.subList(2, 4))
                    assertThat(requests).hasSize(4)
                    runtimePid = checkNotNull(harness.client.pid)
                }
                assertThat(ProcessHandle.of(runtimePid).map { it.isAlive }.orElse(false)).isFalse()
            }
        } finally {
            model.stop(0)
        }
    }

    @Test
    fun extractsAChatModelConnectionAndRunsTheNativeAgentWithTypedPatches() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val paths = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String>()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            requests.add(HarnessProtocol.json.parseToJsonElement(body).jsonObject)
            paths.add(exchange.requestURI.path)
            auth.add(exchange.requestHeaders.getFirst("Authorization"))
            val checkEnvironment = requests.size % 2 == 1
            val shell = if (System.getProperty("os.name").startsWith("Windows")) "pwsh" else "bash"
            val probe = """node -e "console.log(Object.keys(process.env).some(k=>/^DSH(JK)?_SPRING_AI_/i.test(k))?'CREDENTIAL_PRESENT':'CREDENTIAL_ABSENT')""""
            val arguments = buildJsonObject { put("command", probe) }.toString()
            val delta = if (checkEnvironment) {
                """{"role":"assistant","tool_calls":[{"index":0,"id":"call-env","type":"function","function":{"name":"$shell","arguments":${JsonPrimitive(arguments)}}}]}"""
            } else {
                """{"role":"assistant","content":"Native model connection reused."}"""
            }
            val finish = if (checkEnvironment) "tool_calls" else "stop"
            val chunk = """{"id":"fixture","object":"chat.completion.chunk","model":"gpt-4o-mini","choices":[{"index":0,"delta":$delta,"finish_reason":null}]}"""
            val stop = """{"id":"fixture","object":"chat.completion.chunk","model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"$finish"}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
            val bytes = "data: $chunk\n\ndata: $stop\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        model.start()
        try {
            val pid: Long
            (SpringApplication(HarnessApplication::class.java, OpenAiFixture::class.java).run(
                "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--fixture-model-url=http://127.0.0.1:${model.address.port}/v1",
                "--dshjk.profile=sdk-minimal", *runtimeArgs,
                "--dshjk.dsh-home=${root.resolve("derived-home")}", "--dshjk.cwd=$root",
                "--dshjk.environment.DSH_TELEMETRY_DISABLED=1",
            ) as ServletWebServerApplicationContext).use { app ->
                val harness = app.getBean(DeepSeekHarness::class.java)
                assertThat(harness.config.provider).isEqualTo("openai")
                assertThat(harness.config.patches.first().toJson().toString()).doesNotContain("fixture-derived-key")
                val base = "http://127.0.0.1:${checkNotNull(app.webServer).port}"
                val http = HttpClient.newHttpClient()
                val normal = post(http, "$base/run")
                assertThat(normal.statusCode()).isEqualTo(200)
                assertThat(normal.body()).contains("Native model connection reused.")
                val streamed = post(http, "$base/run/events")
                assertThat(streamed.statusCode()).isEqualTo(200)
                assertThat(streamed.body()).contains("event:session.event", "Native model connection reused.", "event:result")
                pid = checkNotNull(harness.client.pid)
            }
            assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isFalse()
            assertThat(paths).containsExactlyElementsOf(List(4) { "/v1/chat/completions" })
            assertThat(auth).containsExactlyElementsOf(List(4) { "Bearer fixture-derived-key" })
            assertThat(requests).hasSize(4)
            for (request in listOf(requests[1], requests[3])) {
                val toolResult = request.getValue("messages").jsonArray.map { it.jsonObject }
                    .single { it["role"]?.jsonPrimitive?.content == "tool" }
                assertThat(toolResult.getValue("content").toString()).contains("CREDENTIAL_ABSENT")
                    .doesNotContain("CREDENTIAL_PRESENT", "fixture-derived-key")
            }
            requests.forEach {
                assertThat(it.getValue("model").jsonPrimitive.content).isEqualTo("gpt-4o-mini")
                assertThat(it.getValue("stream").jsonPrimitive.boolean).isTrue()
            }
        } finally {
            model.stop(0)
        }
    }

    private fun post(
        http: HttpClient,
        uri: String,
        body: String = """{"text":"Use the add tool with 20 and 22."}""",
    ): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun assertToolRoundTrip(requests: List<JsonObject>) {
        val tool = requests[0].getValue("tools").jsonArray.single {
            it.jsonObject["name"]?.jsonPrimitive?.content == "mcp__application__add"
        }.jsonObject
        assertThat(tool.getValue("input_schema").jsonObject.getValue("properties").jsonObject.keys)
            .containsExactlyInAnyOrder("left", "right")
        val results = requests[1].getValue("messages").jsonArray.flatMap { message ->
            (message.jsonObject["content"] as? JsonArray).orEmpty()
        }.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "tool_result" }
        assertThat(results).isNotEmpty()
        results.forEach { value ->
            assertThat(value["is_error"]).isNotEqualTo(JsonPrimitive(true))
            assertThat(value["content"].toString()).contains("42")
        }
    }

    private fun response(block: String, stop: String): String = listOf(
        """{"type":"message_start","message":{"id":"spring-fixture","model":"deepseek-v4-flash","usage":{"input_tokens":3,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":$block}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
    ).joinToString("") { "data: $it\n\n" }

    @TestConfiguration(proxyBeanMethods = false)
    class OpenAiFixture {
        @Bean
        fun chatModel(@Value("\${fixture-model-url}") url: String): OpenAiChatModel = OpenAiChatModel.builder().options(
            OpenAiChatOptions.builder().model("gpt-4o-mini").baseUrl(url)
                .apiKey("fixture-derived-key").maxCompletionTokens(1024).build(),
        ).build()
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(CalculatorToolsConfiguration::class)
    class ToolsServer
}
