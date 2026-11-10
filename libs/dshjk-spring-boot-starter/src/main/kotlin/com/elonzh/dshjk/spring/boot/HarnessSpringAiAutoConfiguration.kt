package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.LlmApi
import com.elonzh.dshjk.configuration.LlmModelConfig
import com.elonzh.dshjk.configuration.LlmProviderConfig
import org.apache.commons.logging.LogFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import java.net.URI

@AutoConfiguration(
    before = [HarnessAutoConfiguration::class],
    afterName = [
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
    ],
)
@ConditionalOnClass(ChatModel::class)
@ConditionalOnMissingBean(HarnessConfig::class, DeepSeekHarness::class)
@EnableConfigurationProperties(HarnessProperties::class)
class HarnessSpringAiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun springAiModelConfiguration(
        properties: HarnessProperties,
        models: ObjectProvider<ChatModel>,
        extractors: ObjectProvider<SpringAiModelConfigurationExtractor>,
    ): SpringAiModelConfiguration? {
        if (!properties.enabled || !properties.springAi.enabled || properties.hasExplicitModelConfiguration()) return null
        val model = models.ifUnique
        if (model == null) {
            if (models.stream().use { it.count() } > 1) {
                logger.warn("Multiple Spring AI ChatModels are available; select one with @Primary or configure dshjk.provider/model")
            }
            return null
        }
        val extracted = extractors.orderedStream().use { stream ->
            stream.map { it.extract(model) }.filter { it != null }.findFirst().orElse(null)
        }
        if (extracted == null) logger.warn("Spring AI ChatModel has no extractable connection; using native dsh defaults")
        return extracted
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenAiChatModel::class)
    internal class OpenAiExtraction {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        fun openAiModelConfigurationExtractor(environment: Environment): SpringAiModelConfigurationExtractor =
            SpringAiModelConfigurationExtractor { model ->
                if (model !is OpenAiChatModel) return@SpringAiModelConfigurationExtractor null
                val options = model.options
                if (options.isMicrosoftFoundry || options.isGitHubModels || options.credential != null || options.proxy != null ||
                    property(environment, "spring.ai.openai", "microsoft-foundry").toBoolean() ||
                    property(environment, "spring.ai.openai", "github-models").toBoolean()) {
                    return@SpringAiModelConfigurationExtractor null
                }
                val prefix = "spring.ai.openai"
                val key = options.apiKey ?: property(environment, prefix, "api-key") ?: environment.getProperty("OPENAI_API_KEY")
                if (key.isNullOrBlank()) return@SpringAiModelConfigurationExtractor null
                val baseUrl = options.baseUrl ?: property(environment, prefix, "base-url")
                    ?: environment.getProperty("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"
                val headers = headers(environment, prefix) + options.customHeaders.orEmpty() + buildMap {
                    options.organizationId?.let { put("OpenAI-Organization", it) }
                }
                if (hasCredentialHeaders(headers)) return@SpringAiModelConfigurationExtractor null
                val maxTokens = options.maxCompletionTokens ?: options.maxTokens
                SpringAiModelConfiguration("openai", options.model, LlmProviderConfig(
                    api = LlmApi.OPENAI_COMPLETIONS,
                    baseUrl = URI.create(baseUrl),
                    models = listOf(LlmModelConfig(options.model, maxTokens = maxTokens)),
                    headers = headers,
                ), key, maxTokens)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AnthropicChatModel::class)
    internal class AnthropicExtraction {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        fun anthropicModelConfigurationExtractor(environment: Environment): SpringAiModelConfigurationExtractor =
            SpringAiModelConfigurationExtractor { model ->
                if (model !is AnthropicChatModel) return@SpringAiModelConfigurationExtractor null
                val options = model.options
                if (options.proxy != null) return@SpringAiModelConfigurationExtractor null
                val prefix = "spring.ai.anthropic"
                val key = options.apiKey ?: property(environment, prefix, "api-key") ?: environment.getProperty("ANTHROPIC_API_KEY")
                if (key.isNullOrBlank()) return@SpringAiModelConfigurationExtractor null
                val baseUrl = options.baseUrl ?: property(environment, prefix, "base-url")
                    ?: environment.getProperty("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com"
                val headers = headers(environment, prefix) + options.httpHeaders.orEmpty()
                if (hasCredentialHeaders(headers)) return@SpringAiModelConfigurationExtractor null
                SpringAiModelConfiguration("anthropic", options.model, LlmProviderConfig(
                    api = LlmApi.ANTHROPIC_MESSAGES,
                    baseUrl = URI.create(baseUrl),
                    models = listOf(LlmModelConfig(options.model, maxTokens = options.maxTokens)),
                    headers = headers,
                ), key, options.maxTokens)
            }
    }

    companion object {
        private val logger = LogFactory.getLog(HarnessSpringAiAutoConfiguration::class.java)
    }
}

private fun property(environment: Environment, prefix: String, name: String): String? {
    val binder = Binder.get(environment)
    return binder.bind("$prefix.chat.$name", String::class.java).orElse(null)
        ?: binder.bind("$prefix.$name", String::class.java).orElse(null)
}

private fun headers(environment: Environment, prefix: String): Map<String, String> {
    val binder = Binder.get(environment)
    val type = Bindable.mapOf(String::class.java, String::class.java)
    val common = binder.bind("$prefix.custom-headers", type).orElse(null).orEmpty()
    val chat = binder.bind("$prefix.chat.custom-headers", type).orElse(null).orEmpty()
    return common + chat
}

private fun hasCredentialHeaders(headers: Map<String, String>): Boolean =
    headers.keys.any { it.equals("Authorization", ignoreCase = true) || it.equals("x-api-key", ignoreCase = true) }
