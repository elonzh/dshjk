package com.elonzh.dshjk.configuration

import com.elonzh.dshjk.protocol.*
import java.nio.file.Path
import java.time.Duration

/** Immutable launch inputs. The runtime and all durable resources remain owned by dsh. */
class HarnessConfig @JvmOverloads constructor(
    val dshHome: Path,
    val cwd: Path = Path.of("").toAbsolutePath(),
    val runtime: RuntimeCommand = RuntimeCommand.executable("dsh"),
    val profile: String = "sdk",
    patches: List<Path> = emptyList(),
    val processCwd: Path = cwd,
    environment: Map<String, String> = emptyMap(),
    val inheritEnvironment: Boolean = true,
    val provider: String = "deepseek-official",
    val model: String = "deepseek-v4-flash",
    val reasoningEffort: String? = null,
    val maxTokens: Int? = null,
    val initializeTimeout: Duration = Duration.ofSeconds(30),
    val requestTimeout: Duration? = null,
    val shutdownTimeout: Duration = Duration.ofSeconds(1),
    val eofGrace: Duration = Duration.ofSeconds(6),
    val terminateGrace: Duration = Duration.ofSeconds(3),
    val notificationBufferCapacity: Int = 8192,
    val approvalHandler: ApprovalHandler? = null,
    val userQuestionHandler: UserQuestionHandler? = null,
) {
    val patches: List<Path> = java.util.List.copyOf(patches.map { it.toAbsolutePath().normalize() })
    val environment: Map<String, String> = java.util.Map.copyOf(environment)
    init {
        require(profile.isNotBlank() && !profile.startsWith("-")) { "Invalid profile" }
        require(provider.isNotBlank() && model.isNotBlank())
        require(reasoningEffort == null || reasoningEffort.isNotBlank())
        require(maxTokens == null || maxTokens > 0)
        require(notificationBufferCapacity > 0)
        listOfNotNull(initializeTimeout, requestTimeout, shutdownTimeout, eofGrace, terminateGrace).forEach {
            require(!it.isNegative && it.toMillis() > 0) { "Timeouts must be at least one millisecond" }
        }
    }
    class Builder internal constructor(private val home: Path) {
        private var cwd = Path.of("").toAbsolutePath()
        private var runtime = RuntimeCommand.executable("dsh")
        private var profile = "sdk"
        private var patches = emptyList<Path>()
        private var environment = emptyMap<String, String>()
        private var inheritEnvironment = true
        private var processCwd: Path? = null
        private var provider = "deepseek-official"
        private var model = "deepseek-v4-flash"
        private var effort: String? = null
        private var tokens: Int? = null
        private var initializeTimeout = Duration.ofSeconds(30)
        private var requestTimeout: Duration? = null
        private var shutdownTimeout = Duration.ofSeconds(1)
        private var eofGrace = Duration.ofSeconds(6)
        private var terminateGrace = Duration.ofSeconds(3)
        private var capacity = 8192
        private var approval: ApprovalHandler? = null
        private var question: UserQuestionHandler? = null
        fun cwd(value: Path) = apply { cwd = value }
        fun runtime(value: RuntimeCommand) = apply { runtime = value }
        fun profile(value: String) = apply { profile = value }
        fun patches(value: List<Path>) = apply { patches = value.toList() }
        fun environment(value: Map<String, String>) = apply { environment = value.toMap() }
        fun inheritEnvironment(value: Boolean) = apply { inheritEnvironment = value }
        fun processCwd(value: Path) = apply { processCwd = value }
        fun provider(value: String) = apply { provider = value }
        fun model(value: String) = apply { model = value }
        fun reasoningEffort(value: String) = apply { effort = value }
        fun maxTokens(value: Int) = apply { tokens = value }
        fun initializeTimeout(value: Duration) = apply { initializeTimeout = value }
        fun requestTimeout(value: Duration) = apply { requestTimeout = value }
        fun shutdownTimeout(value: Duration) = apply { shutdownTimeout = value }
        fun eofGrace(value: Duration) = apply { eofGrace = value }
        fun terminateGrace(value: Duration) = apply { terminateGrace = value }
        fun notificationBufferCapacity(value: Int) = apply { capacity = value }
        fun approvalHandler(value: ApprovalHandler) = apply { approval = value }
        fun userQuestionHandler(value: UserQuestionHandler) = apply { question = value }
        fun build() = HarnessConfig(home, cwd, runtime, profile, patches, processCwd ?: cwd, environment,
            inheritEnvironment, provider, model, effort, tokens, initializeTimeout, requestTimeout,
            shutdownTimeout, eofGrace, terminateGrace, capacity, approval, question)
    }
    companion object { @JvmStatic fun builder(dshHome: Path) = Builder(dshHome) }
}

/** An executable, or Node plus an explicit CLI module. No shell command interpretation. */
class RuntimeCommand private constructor(val executable: String, arguments: List<String>) {
    val arguments: List<String> = java.util.List.copyOf(arguments)
    init { require(executable.isNotBlank()) }
    companion object {
        @JvmStatic fun executable(command: String) = RuntimeCommand(command, emptyList())
        @JvmStatic @JvmOverloads fun node(cli: Path, node: String = "node") =
            RuntimeCommand(node, listOf(cli.toAbsolutePath().normalize().toString()))
        /** Explicit launcher prefix, e.g. a Java executable wrapper or Node loader. Profile flags are appended. */
        @JvmStatic fun of(executable: String, arguments: List<String>) = RuntimeCommand(executable, arguments)
    }
}
