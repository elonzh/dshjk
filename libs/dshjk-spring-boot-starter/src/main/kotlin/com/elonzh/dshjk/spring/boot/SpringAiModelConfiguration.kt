package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.configuration.LlmProviderConfig

/** A connection snapshot. API keys are passed only through the child process environment. */
data class SpringAiModelConfiguration @JvmOverloads constructor(
    val provider: String,
    val model: String,
    val connection: LlmProviderConfig,
    val apiKey: String? = null,
    val maxTokens: Int? = null,
) {
    init {
        require(provider.isNotBlank() && model.isNotBlank())
        require(apiKey == null || apiKey.isNotBlank())
        require(maxTokens == null || maxTokens > 0)
    }
    override fun toString(): String = "SpringAiModelConfiguration(provider=$provider, model=$model, credentials=<redacted>)"
}
