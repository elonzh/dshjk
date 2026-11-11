package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.configuration.*
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.lang.reflect.Proxy
import java.net.URI

class SpringAiConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        HarnessSpringAiAutoConfiguration::class.java, HarnessAutoConfiguration::class.java,
    ))

    private fun openAi(model: String = "gpt-4o-mini"): OpenAiChatModel = OpenAiChatModel.builder().options(
        OpenAiChatOptions.builder().model(model).apiKey("fixture-openai-secret")
            .baseUrl("http://127.0.0.1:19001/v1").maxCompletionTokens(1024)
            .customHeaders(mapOf("X-Tenant" to "fixture")).build(),
    ).build()

    @Test
    fun extractsOpenAiConnectionAndKeepsTheKeyOutOfPatchFiles() {
        runner.withBean(ChatModel::class.java, { openAi() }).run { context ->
            val config = context.getBean(HarnessConfig::class.java)
            assertThat(config.provider).isEqualTo("openai")
            assertThat(config.model).isEqualTo("gpt-4o-mini")
            assertThat(config.maxTokens).isEqualTo(1024)
            assertThat(config.environment.values).containsExactly("fixture-openai-secret")
            val patch = config.patches.single().toJson().toString()
            assertThat(patch).contains("openai-completions", "http://127.0.0.1:19001/v1", "X-Tenant", config.environment.keys.single())
                .doesNotContain("fixture-openai-secret")
            assertThat(context.getBean(SpringAiModelConfiguration::class.java).toString()).doesNotContain("fixture-openai-secret")
        }
    }

    @Test
    fun reusesActualSpringAiAutoConfigurationPropertiesWhenOptionsDoNotExposeTheConnection() {
        for (chatOverride in listOf(false, true)) {
            val properties = mutableListOf(
                "spring.ai.openai.apiKey=common-key", "spring.ai.openai.baseUrl=http://localhost:19004/v1",
                "spring.ai.openai.chat.model=gpt-4o-mini", "spring.ai.openai.chat.max-completion-tokens=512",
                "dshjk.environment.DSH_TELEMETRY_DISABLED=1",
            )
            if (chatOverride) properties += listOf(
                "spring.ai.openai.chat.api-key=chat-key", "spring.ai.openai.chat.base-url=http://localhost:19005/v1",
            )
            runner.withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration::class.java))
                .withBean(ToolCallingManager::class.java, { ToolCallingManager.builder().build() })
                .withPropertyValues(*properties.toTypedArray()).run { context ->
                    val config = context.getBean(HarnessConfig::class.java)
                    assertThat(config.provider).isEqualTo("openai")
                    assertThat(config.maxTokens).isEqualTo(512)
                    assertThat(config.environment).containsValue(if (chatOverride) "chat-key" else "common-key")
                        .containsEntry("DSH_TELEMETRY_DISABLED", "1")
                    assertThat(config.patches.single().toJson().toString())
                        .contains(if (chatOverride) "http://localhost:19005/v1" else "http://localhost:19004/v1")
                        .doesNotContain("common-key", "chat-key")
                }
        }
    }

    @Test
    fun extractsAnthropicAndInsertsTheAdapterForTheMinimalProfile() {
        val model = AnthropicChatModel.builder().options(AnthropicChatOptions.builder()
            .model("claude-sonnet-4-5").apiKey("fixture-anthropic-secret")
            .baseUrl("http://127.0.0.1:19002").maxTokens(2048).build()).build()
        runner.withPropertyValues("dshjk.profile=sdk-minimal").withBean(ChatModel::class.java, { model }).run { context ->
            val config = context.getBean(HarnessConfig::class.java)
            assertThat(config.provider).isEqualTo("anthropic")
            assertThat(config.model).isEqualTo("claude-sonnet-4-5")
            assertThat(config.maxTokens).isEqualTo(2048)
            assertThat(config.patches.single().toJson().toString()).contains("insert", "anthropic-messages")
            assertThat(config.environment.values).containsExactly("fixture-anthropic-secret")
        }
    }

    @Test
    fun explicitDshConfigurationAndOptOutKeepNativeSelection() {
        for (properties in listOf(
            arrayOf("dshjk.provider=explicit", "dshjk.model=chosen"),
            arrayOf("dshjk.spring-ai.enabled=false"),
            arrayOf("dshjk.patches[0].id=llm-pi-ai", "dshjk.patches[0].disabled=true"),
            arrayOf("dshjk.environment.DEEPSEEK_API_KEY=explicit-secret"),
        )) {
            runner.withBean(ChatModel::class.java, { openAi() }).withPropertyValues(*properties).run { context ->
                val config = context.getBean(HarnessConfig::class.java)
                assertThat(config.provider).isNotEqualTo("openai")
                assertThat(config.patches).noneMatch { it.toJson().toString().contains("openai-completions") }
                assertThat(config.environment.values).doesNotContain("fixture-openai-secret")
            }
        }
        val explicit = HarnessConfig(provider = "explicit", model = "chosen")
        runner.withBean(ChatModel::class.java, { openAi() }).withBean(HarnessConfig::class.java, { explicit }).run { context ->
            assertThat(context.getBean(HarnessConfig::class.java)).isSameAs(explicit)
            assertThat(context).doesNotHaveBean(SpringAiModelConfiguration::class.java)
        }
    }

    @Test
    fun toolPatchesAndNativeFilesCanBeCombinedWithModelDiscovery() {
        runner.withBean(ChatModel::class.java, { openAi() })
            .withPropertyValues(
                "dshjk.patches[0].id=terminal-bash", "dshjk.patches[0].disabled=true",
                "dshjk.patch-files[0]=custom.json",
            ).run { context ->
                val config = context.getBean(HarnessConfig::class.java)
                assertThat(config.provider).isEqualTo("openai")
                assertThat(config.patches.map { it.id }).containsExactly("llm-pi-ai", "terminal-bash")
                assertThat(config.patchFiles.map { it.fileName.toString() }).containsExactly("custom.json")
            }
    }

    @Test
    fun userSuppliedOpenAiModelsNeverBorrowHalfOfAConnection() {
        val endpointOnly = OpenAiChatOptions.builder().model("gpt-4o-mini")
            .baseUrl("http://localhost:19003/v1").build()
        val keyOnly = OpenAiChatOptions.builder().model("gpt-4o-mini").apiKey("explicit-key").build()
        for (options in listOf(endpointOnly, keyOnly)) {
            val model = OpenAiChatModel.builder().options(options)
                .openAiClient(opaqueClient(OpenAIClient::class.java))
                .openAiClientAsync(opaqueClient(OpenAIClientAsync::class.java))
                .build()
            runner.withBean("openAiChatModel", ChatModel::class.java, { model })
                .withPropertyValues("spring.ai.openai.api-key=global-key", "spring.ai.openai.base-url=http://localhost:19004/v1")
                .run { context ->
                    val config = context.getBean(HarnessConfig::class.java)
                    assertThat(config.provider).isEqualTo("deepseek-official")
                    assertThat(config.environment).isEmpty()
                    assertThat(config.patches).isEmpty()
                }
        }
    }

    private fun <T> opaqueClient(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ ->
            error("Extraction must never call the opaque HTTP client")
        },
    )

    @Test
    fun ambiguousModelsKeepNativeDefaultsAndPrimarySelectsTheIntendedBean() {
        runner.withBean("first", ChatModel::class.java, { openAi("gpt-4o") })
            .withBean("second", ChatModel::class.java, { openAi() }).run { context ->
                assertThat(context.getBean(HarnessConfig::class.java).patches).isEmpty()
            }
        runner.withBean("first", ChatModel::class.java, { openAi("gpt-4o") }, { it.isPrimary = true })
            .withBean("second", ChatModel::class.java, { openAi() }).run { context ->
                assertThat(context.getBean(HarnessConfig::class.java).model).isEqualTo("gpt-4o")
            }
    }

    @Test
    fun customExtractorHandlesOtherChatModelsWithoutCallingThem() {
        runner.withBean(ChatModel::class.java, { UnsupportedModel() })
            .withBean(SpringAiModelConfigurationExtractor::class.java, { SpringAiModelConfigurationExtractor {
                SpringAiModelConfiguration("custom", "custom-model", LlmProviderConfig(
                    api = LlmApi.OPENAI_RESPONSES, baseUrl = URI.create("http://localhost:19003/v1"),
                    models = listOf(LlmModelConfig("custom-model", 131072, 2048)),
                ), "custom-secret")
            } }).run { context ->
                val config = context.getBean(HarnessConfig::class.java)
                assertThat(config.provider).isEqualTo("custom")
                assertThat(config.patches.single().toJson().toString()).contains("openai-responses")
            }
    }

    @Test
    fun missingSpringAiOrUnsupportedModelsKeepNativeDefaults() {
        runner.withClassLoader(FilteredClassLoader("org.springframework.ai")).run { context ->
            assertThat(context).hasSingleBean(HarnessConfig::class.java)
            assertThat(context.getBean(HarnessConfig::class.java).patches).isEmpty()
        }
        runner.withBean(ChatModel::class.java, { UnsupportedModel() }).run { context ->
            assertThat(context.getBean(HarnessConfig::class.java).patches).isEmpty()
        }
    }

    private class UnsupportedModel : ChatModel {
        override fun call(prompt: Prompt): ChatResponse = error("Extraction must never call the model")
    }
}
