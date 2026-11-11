package com.elonzh.dshjk.spring.boot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.LlmProviderConfig
import com.elonzh.dshjk.configuration.McpServerConfig
import java.nio.file.Path
import java.time.Duration

/** Optional overrides for native SDK defaults, bound once when the context starts. */
@ConfigurationProperties("dshjk")
data class HarnessProperties(
    /** Whether to create the managed Harness client. */
    @param:DefaultValue("true") val enabled: Boolean,
    /** Optional home override; omission preserves native home discovery. */
    val dshHome: Path? = null,
    /** Agent workspace; omission uses the SDK default. */
    val cwd: Path? = null,
    /** Independent runtime process working directory. */
    val processCwd: Path? = null,
    /** Named dsh profile; omission uses the SDK default. */
    val profile: String? = null,
    /** Provider route used at initialization. */
    val provider: String? = null,
    /** Model used at initialization. */
    val model: String? = null,
    /** Adapter-owned reasoning effort. */
    val reasoningEffort: String? = null,
    /** Positive output token cap. */
    val maxTokens: Int? = null,
    /** Inline native patch operations, applied after generated provider and MCP patches. */
    val patches: List<HarnessPatch> = emptyList(),
    /** Ordered caller patch files, applied after inline operations. */
    val patchFiles: List<Path> = emptyList(),
    /** Typed multi-provider adapter configuration. */
    val providers: Map<String, LlmProviderConfig> = emptyMap(),
    /** Optional Spring AI configuration discovery. */
    val springAi: SpringAi = SpringAi(enabled = true),
    /** Child environment overrides. */
    val environment: Map<String, String> = emptyMap(),
    /** Whether the runtime inherits the process environment. */
    val inheritEnvironment: Boolean? = null,
    /** Profile handshake timeout. */
    val initializeTimeout: Duration? = null,
    /** Optional protocol response timeout. */
    val requestTimeout: Duration? = null,
    /** Protocol shutdown timeout. */
    val shutdownTimeout: Duration? = null,
    /** Grace for native stdin-EOF cleanup. */
    val eofGrace: Duration? = null,
    /** Termination confirmation timeout. */
    val terminateGrace: Duration? = null,
    /** Independent capacity of each notification subscription. */
    val notificationBufferCapacity: Int? = null,
    /** Runtime executable or Node CLI module. */
    val runtime: Runtime = Runtime(executable = null),
    /** Named Streamable HTTP MCP servers, including Spring AI tool servers. */
    val mcp: Map<String, McpServerConfig> = emptyMap(),
) {
    data class Runtime(
        /** Executable on PATH or an absolute path. Mutually exclusive with cli. */
        val executable: String?,
        /** Optional JavaScript CLI module launched with Node. */
        val cli: Path? = null,
        /** Node executable used when cli is configured. */
        val node: String? = null,
    )

    data class SpringAi(
        /** Reuse a unique or primary ChatModel connection when no dsh model configuration was supplied. */
        @param:DefaultValue("true") val enabled: Boolean,
    )
}

private val modelPluginIds = setOf("llm", "llm-pi-ai", "llm-deepseek", "llm-deepseek-account")

internal fun HarnessProperties.hasExplicitModelConfiguration(): Boolean =
    provider != null || model != null || providers.isNotEmpty() ||
        environment.keys.any { it == "DEEPSEEK_API_KEY" || it == "DEEPSEEK_BASE_URL" } ||
        patches.any { patch ->
            patch.id in modelPluginIds || patch.insert.orEmpty().any { plugin ->
                plugin.id in modelPluginIds || plugin.name.startsWith("@deepseek-ai/dsh-llm-")
            }
        }
