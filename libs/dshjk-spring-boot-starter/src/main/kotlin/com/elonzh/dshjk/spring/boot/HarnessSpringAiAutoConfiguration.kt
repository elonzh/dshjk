package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.LlmApi
import com.elonzh.dshjk.configuration.LlmModelConfig
import com.elonzh.dshjk.configuration.LlmProviderConfig
import org.apache.commons.logging.LogFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import org.springframework.ai.openai.setup.OpenAiSetup
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.net.URI

private const val OPENAI_AUTO_CONFIG = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration"
private const val ANTHROPIC_AUTO_CONFIG = "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration"

@AutoConfiguration(
    before = [HarnessAutoConfiguration::class],
    afterName = [OPENAI_AUTO_CONFIG, ANTHROPIC_AUTO_CONFIG],
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
        fun openAiModelConfigurationExtractor(beans: ConfigurableListableBeanFactory): SpringAiModelConfigurationExtractor =
            SpringAiModelConfigurationExtractor { model ->
                if (model !is OpenAiChatModel) return@SpringAiModelConfigurationExtractor null
                val options = model.options
                val maxTokens = options.maxCompletionTokens ?: options.maxTokens
                if (beans.createdModel(model, "openAiChatModel", OPENAI_AUTO_CONFIG)) {
                    extractAutoConfiguredOpenAi(beans, options.model, maxTokens)
                } else {
                    if (options.credential != null || options.proxy != null ||
                        OpenAiSetup.detectModelProvider(options.isMicrosoftFoundry, options.isGitHubModels,
                            options.baseUrl, options.microsoftDeploymentName, options.microsoftFoundryServiceVersion)
                            != OpenAiSetup.ModelProvider.OPEN_AI) return@SpringAiModelConfigurationExtractor null
                    modelConfiguration("openai", LlmApi.OPENAI_COMPLETIONS, options.model, maxTokens,
                        options.baseUrl, options.apiKey, openAiHeaders(options.customHeaders, options.organizationId))
                }
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AnthropicChatModel::class)
    internal class AnthropicExtraction {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        fun anthropicModelConfigurationExtractor(beans: ConfigurableListableBeanFactory): SpringAiModelConfigurationExtractor =
            SpringAiModelConfigurationExtractor { model ->
                if (model !is AnthropicChatModel) return@SpringAiModelConfigurationExtractor null
                val options = model.options
                if (options.proxy != null) return@SpringAiModelConfigurationExtractor null
                val stockModel = beans.createdModel(model, "anthropicChatModel", ANTHROPIC_AUTO_CONFIG)
                if (stockModel && beans.getBeanNamesForType(AnthropicHttpClientBuilderCustomizer::class.java).isNotEmpty()) {
                    return@SpringAiModelConfigurationExtractor null
                }
                val baseUrl = if (stockModel) {
                    options.baseUrl ?: System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com"
                } else options.baseUrl
                val key = if (stockModel) options.apiKey ?: System.getenv("ANTHROPIC_API_KEY") else options.apiKey
                modelConfiguration("anthropic", LlmApi.ANTHROPIC_MESSAGES, options.model, options.maxTokens,
                    baseUrl, key, options.httpHeaders.orEmpty())
            }
    }

    companion object {
        private val logger = LogFactory.getLog(HarnessSpringAiAutoConfiguration::class.java)
    }
}

// Only the stock factory is known to consume the Spring AI connection properties.
private fun ConfigurableListableBeanFactory.createdModel(model: ChatModel, name: String, factoryClass: String): Boolean {
    if (!containsBeanDefinition(name) || getSingleton(name) !== model) return false
    val definition = getMergedBeanDefinition(name)
    val factory = definition.factoryBeanName ?: return false
    return definition.factoryMethodName == name && getType(factory)?.name == factoryClass
}

private fun extractAutoConfiguredOpenAi(
    beans: ConfigurableListableBeanFactory,
    model: String,
    maxTokens: Int?,
): SpringAiModelConfiguration? {
    if (beans.getBeanNamesForType(OpenAiHttpClientBuilderCustomizer::class.java).isNotEmpty()) return null
    val connection = OpenAiAutoConfigurationUtil.resolveCommonProperties(
        beans.getBean(OpenAiCommonProperties::class.java),
        beans.getBean(OpenAiChatProperties::class.java),
    )
    if (connection.credential != null || connection.proxy != null) return null
    val baseUrl = connection.baseUrl ?: System.getenv("AZURE_OPENAI_BASE_URL") ?: System.getenv("OPENAI_BASE_URL")
    if (OpenAiSetup.detectModelProvider(connection.isMicrosoftFoundry, connection.isGitHubModels,
            baseUrl, connection.microsoftDeploymentName, connection.microsoftFoundryServiceVersion)
        != OpenAiSetup.ModelProvider.OPEN_AI) return null
    return modelConfiguration("openai", LlmApi.OPENAI_COMPLETIONS, model, maxTokens,
        baseUrl?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1",
        connection.apiKey ?: System.getenv("OPENAI_API_KEY"),
        openAiHeaders(connection.customHeaders, connection.organizationId))
}

private fun openAiHeaders(headers: Map<String, String>?, organization: String?): Map<String, String> =
    if (organization == null) headers.orEmpty() else mapOf("OpenAI-Organization" to organization) + headers.orEmpty()

private val credentialHeader = Regex("authorization|cookie|api[-_]?key|token|secret|password", RegexOption.IGNORE_CASE)

private fun modelConfiguration(
    provider: String,
    api: LlmApi,
    model: String,
    maxTokens: Int?,
    baseUrl: String?,
    apiKey: String?,
    headers: Map<String, String>,
): SpringAiModelConfiguration? {
    if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || headers.keys.any(credentialHeader::containsMatchIn)) return null
    val connection = try {
        LlmProviderConfig(api = api, baseUrl = URI.create(baseUrl),
            models = listOf(LlmModelConfig(model, maxTokens = maxTokens)), headers = headers)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return SpringAiModelConfiguration(provider, model, connection, apiKey, maxTokens)
}
