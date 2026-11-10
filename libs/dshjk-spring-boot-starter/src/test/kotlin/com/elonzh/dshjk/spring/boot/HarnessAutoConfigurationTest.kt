package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.protocol.ApprovalHandler
import com.elonzh.dshjk.protocol.ApprovalOutcome
import com.elonzh.dshjk.protocol.ApprovalResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import org.springframework.core.env.MapPropertySource
import java.nio.file.Path
import java.time.Duration

@Timeout(20)
class HarnessAutoConfigurationTest {
    @TempDir
    lateinit var root: Path

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HarnessAutoConfiguration::class.java))

    @Test
    fun defaultsAreLazyAndKeepNativeHomeDiscovery() {
        runner.run { context ->
            assertThat(context).hasSingleBean(DeepSeekHarness::class.java).hasSingleBean(HarnessConfig::class.java)
            val harness = context.getBean(DeepSeekHarness::class.java)
            val defaults = HarnessConfig()
            assertThat(harness.client.pid).isNull()
            assertThat(harness.config.dshHome).isNull()
            assertThat(harness.config.model).isEqualTo(defaults.model)
            assertThat(harness.config.initializeTimeout).isEqualTo(defaults.initializeTimeout)
        }
    }

    @Test
    fun disabledConfigurationCreatesNoRuntimeOrLaunchResources() {
        runner.withPropertyValues("dshjk.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(DeepSeekHarness::class.java).doesNotHaveBean(HarnessConfig::class.java)

        }
    }

    @Test
    fun applicationConfigAndHarnessOverrideAutoConfiguration() {
        val config = HarnessConfig(model = "custom-model")
        runner.withBean(HarnessConfig::class.java, { config }).run { context ->
            assertThat(context.getBean(DeepSeekHarness::class.java).config).isSameAs(config)

        }
        val custom = DeepSeekHarness(config)
        runner.withBean(DeepSeekHarness::class.java, { custom }).run { context ->
            assertThat(context.getBean(DeepSeekHarness::class.java)).isSameAs(custom)
            assertThat(context).doesNotHaveBean(HarnessConfig::class.java)

        }
    }

    @Test
    fun bindsLaunchPropertiesAndDiscoversHandlersBeforeCustomizers() {
        val handler = ApprovalHandler { _, _ -> ApprovalResult(ApprovalOutcome.REJECTED) }
        runner.withPropertyValues(
            "dshjk.profile=sdk-minimal", "dshjk.model=bound-model", "dshjk.max-tokens=1234",
            "dshjk.request-timeout=45s", "dshjk.runtime.cli=${root.resolve("cli.js")}",
            "dshjk.runtime.node=custom-node", "dshjk.environment.TEST_VALUE=bound",
        )
            .withBean(ApprovalHandler::class.java, { handler })
            .withBean("first", HarnessConfigCustomizer::class.java, { ModelCustomizer(1, "first") })
            .withBean("last", HarnessConfigCustomizer::class.java, { ModelCustomizer(2, "last") })
            .run { context ->
                val config = context.getBean(HarnessConfig::class.java)
                assertThat(config.profile).isEqualTo("sdk-minimal")
                assertThat(config.model).isEqualTo("last")
                assertThat(config.maxTokens).isEqualTo(1234)
                assertThat(config.requestTimeout).isEqualTo(Duration.ofSeconds(45))
                assertThat(config.runtime.executable).isEqualTo("custom-node")
                assertThat(config.runtime.arguments).containsExactly(root.resolve("cli.js").toString())
                assertThat(config.environment).containsEntry("TEST_VALUE", "bound")
                assertThat(config.approvalHandler).isSameAs(handler)
            }
    }

    @Test
    fun bindsTypedPatchesProvidersAndMcpWithoutCreatingLaunchFiles() {
        runner.withPropertyValues(
            "dshjk.mcp.tools.url=http://127.0.0.1:12345/mcp",
            "dshjk.mcp.tools.headers.Authorization=Bearer test-token", "dshjk.mcp.tools.startup-timeout=12s",
            "dshjk.patch-files[0]=${root.resolve("override.json")}",
            "dshjk.providers.gateway.api=openai-completions", "dshjk.providers.gateway.base-url=http://localhost:9000/v1",
            "dshjk.providers.gateway.api-key-env=GATEWAY_KEY", "dshjk.providers.gateway.models[0].id=local-model",
        ).withInitializer { context ->
            context.environment.propertySources.addFirst(MapPropertySource("inline-values", mapOf(
                "dshjk.patches[0].id" to "example",
                "dshjk.patches[0].disabled" to true,
                "dshjk.patches[0].config.limit" to 10,
                "dshjk.patches[0].config.enabled" to false,
                "dshjk.patches[0].config.text" to "123",
            )))
        }.run { context ->
            val config = context.getBean(HarnessConfig::class.java)
            assertThat(config.patchFiles).containsExactly(root.resolve("override.json").toAbsolutePath())
            val patches = config.patches.map { it.toJson() }
            val providers = patches[0].getValue("config").jsonObject.getValue("providers").jsonObject
            assertThat(providers.getValue("gateway").jsonObject.getValue("api").jsonPrimitive.content)
                .isEqualTo("openai-completions")
            val insert = patches[1].getValue("insert").jsonArray
            val mcp = insert[0].jsonObject.getValue("config").jsonObject
            assertThat(mcp.getValue("startupTimeoutMs").jsonPrimitive.content).isEqualTo("12000")
            assertThat(mcp.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
                .isEqualTo("Bearer test-token")
            assertThat(patches[2].getValue("disabled").jsonPrimitive.content).isEqualTo("true")
            val inline = patches[2].getValue("config").jsonObject
            assertThat(inline.getValue("limit").jsonPrimitive.isString).isFalse()
            assertThat(inline.getValue("enabled").jsonPrimitive.isString).isFalse()
            assertThat(inline.getValue("text").jsonPrimitive.isString).isTrue()
            assertThat(context.getBean(DeepSeekHarness::class.java).client.pid).isNull()
        }
    }

    @Test
    fun invalidRuntimeAndMcpSettingsFailBeforeLaunching() {
        runner.withPropertyValues("dshjk.runtime.cli=cli.js", "dshjk.runtime.executable=dsh").run { context ->
            assertThat(context).hasFailed()
        }
        runner.withPropertyValues("dshjk.mcp.tools.url=file:/tmp/tools").run { context -> assertThat(context).hasFailed() }
        runner.withPropertyValues("dshjk.max-tokens=-1").run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun contextShutdownReapsTheLazyStartedRuntime() {
        val fixture = Path.of(javaClass.getResource("/runtime.mjs")!!.toURI())
        var pid: Long? = null
        runner.withPropertyValues(
            "dshjk.runtime.cli=$fixture", "dshjk.dsh-home=${root.resolve("home")}",
            "dshjk.cwd=$root", "dshjk.eof-grace=100ms", "dshjk.terminate-grace=100ms",
        ).run { context ->
            val harness = context.getBean(DeepSeekHarness::class.java)
            assertThat(runBlocking { harness.run("managed runtime") }.finalResponse).isEqualTo("managed runtime")
            pid = harness.client.pid
            assertThat(harness.client.isAlive).isTrue()
        }
        assertThat(ProcessHandle.of(checkNotNull(pid)).map { it.isAlive }.orElse(false)).isFalse()
    }

    private class ModelCustomizer(private val order: Int, private val model: String) : HarnessConfigCustomizer, Ordered {
        override fun getOrder(): Int = order
        override fun customize(builder: HarnessConfig.Builder) { builder.model(model) }
    }
}
