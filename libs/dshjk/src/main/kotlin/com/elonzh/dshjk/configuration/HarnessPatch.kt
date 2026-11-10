package com.elonzh.dshjk.configuration

import kotlinx.serialization.json.*
import java.net.URI
import java.time.Duration

/** One native Cordis patch operation. Plugin config remains open JSON. */
data class HarnessPatch @JvmOverloads constructor(
    val id: String? = null,
    val config: Map<String, Any?>? = null,
    val disabled: Boolean? = null,
    val name: String? = null,
    val insert: List<HarnessPlugin>? = null,
) {
    init {
        require(id == null || id.isNotBlank()) { "Patch id must not be blank" }
        require(insert != null || id != null) { "A patch needs an id or inserted plugins" }
        require(insert == null || (config == null && disabled == null && name == null)) {
            "An insertion contains plugins and an optional target group id"
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        config?.let { put("config", jsonValue(it)) }
        disabled?.let { put("disabled", it) }
        name?.let { put("name", it) }
        insert?.let { put("insert", JsonArray(it.map(HarnessPlugin::toJson))) }
    }

    companion object {
        /** Overrides the SDK profile's multi-provider adapter, or inserts it into a minimal profile. */
        @JvmStatic @JvmOverloads
        fun providers(providers: Map<String, LlmProviderConfig>, insertAdapter: Boolean = false): HarnessPatch {
            providers.keys.forEach { require(it.isNotBlank()) { "Provider name must not be blank" } }
            val config = mapOf("providers" to providers.mapValues { it.value.toConfig() })
            return if (insertAdapter) HarnessPatch(insert = listOf(HarnessPlugin(
                "llm-pi-ai", "@deepseek-ai/dsh-llm-pi-ai", config,
            ))) else HarnessPatch(id = "llm-pi-ai", config = config)
        }

        @JvmStatic
        fun mcpServers(servers: Map<String, McpServerConfig>): HarnessPatch = HarnessPatch(insert = servers.map { (name, server) ->
            require(name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "Invalid MCP server name: $name" }
            HarnessPlugin("dshjk-mcp-$name", "@deepseek-ai/dsh-mcp-client", buildMap {
                put("serverName", name)
                put("transport", "streamable-http")
                put("url", server.url.toString())
                put("headers", server.headers)
                server.startupTimeout?.let { put("startupTimeoutMs", it.toMillis()) }
                server.toolCallTimeout?.let { put("toolCallTimeoutMs", it.toMillis()) }
            })
        })
    }
}

data class HarnessPlugin @JvmOverloads constructor(
    val id: String,
    val name: String,
    val config: Map<String, Any?> = emptyMap(),
    val inject: List<String>? = null,
) {
    init { require(id.isNotBlank() && name.isNotBlank()) { "Plugin id and name must not be blank" } }
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("config", jsonValue(config))
        inject?.let { put("inject", JsonArray(it.map(::JsonPrimitive))) }
    }
}

/** Protocol names supported by the native multi-provider adapter. */
enum class LlmApi(val wireName: String) {
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC_MESSAGES("anthropic-messages"),
}

/** Built-in provider fields. The API key is supplied through the named environment reference. */
data class LlmProviderConfig @JvmOverloads constructor(
    val api: LlmApi? = null,
    val baseUrl: URI? = null,
    val apiKeyEnv: String? = null,
    val models: List<LlmModelConfig> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val reasoning: String? = null,
    val defaultContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
) {
    init {
        baseUrl?.let { requireHttpEndpoint(it) }
        require(apiKeyEnv == null || apiKeyEnv.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid credential reference" }
        require(defaultContextWindow == null || defaultContextWindow > 0)
        require(defaultMaxTokens == null || defaultMaxTokens > 0)
    }
    internal fun toConfig(): Map<String, Any?> = buildMap {
        api?.let { put("api", it.wireName) }
        baseUrl?.let { put("baseURL", it.toString()) }
        apiKeyEnv?.let { put("apiKeyEnv", it) }
        if (models.isNotEmpty()) put("models", models.map { it.toConfig() })
        if (headers.isNotEmpty()) put("headers", headers)
        reasoning?.let { put("reasoning", it) }
        defaultContextWindow?.let { put("defaultContextWindow", it) }
        defaultMaxTokens?.let { put("defaultMaxTokens", it) }
    }
}

data class LlmModelConfig @JvmOverloads constructor(
    val id: String,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoningEfforts: Map<String, String?>? = null,
) {
    init {
        require(id.isNotBlank())
        require(contextWindow == null || contextWindow > 0)
        require(maxTokens == null || maxTokens > 0)
    }
    internal fun toConfig(): Map<String, Any?> = buildMap {
        put("id", id)
        contextWindow?.let { put("contextWindow", it) }
        maxTokens?.let { put("maxTokens", it) }
        reasoningEfforts?.let { put("reasoningEfforts", it) }
    }
}

data class McpServerConfig @JvmOverloads constructor(
    val url: URI,
    val headers: Map<String, String> = emptyMap(),
    val startupTimeout: Duration? = null,
    val toolCallTimeout: Duration? = null,
) {
    init {
        require(url.host != null && url.scheme?.lowercase() in setOf("http", "https")) { "Expected an absolute HTTP(S) MCP endpoint" }
        listOfNotNull(startupTimeout, toolCallTimeout).forEach {
            require(it.toMillis() > 0) { "MCP timeouts must be at least one millisecond" }
        }
    }
}

private fun requireHttpEndpoint(uri: URI) {
    require(uri.host != null && uri.scheme?.lowercase() in setOf("http", "https") &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) { "Expected an absolute HTTP(S) endpoint without credentials, query or fragment" }
}

private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> {
        require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
        JsonPrimitive(value)
    }
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
        require(key is String) { "Plugin configuration keys must be strings" }
        key to jsonValue(item)
    })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> throw IllegalArgumentException("Plugin configuration must contain JSON values")
}
