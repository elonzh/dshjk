package com.elonzh.dshjk.client

import com.elonzh.dshjk.cli.HarnessCli
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.protocol.*
import com.elonzh.dshjk.runtime.internal.DshRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A configured DSH runtime with a lazily initialized SDK process for agent sessions. */
class DeepSeekHarness @JvmOverloads constructor(val config: HarnessConfig = HarnessConfig()) : AutoCloseable {
    private val runtime = DshRuntime(config)
    val client = HarnessClient(config, runtime)
    val cli = HarnessCli(runtime)
    private val lifecycle = Mutex()
    private var initialized: InitializeResult? = null
    @JvmSynthetic suspend fun start(): InitializeResult = lifecycle.withLock {
        runtime.checkOpen()
        initialized?.let { return@withLock it }
        try {
            client.initialize(InitializeParams(config.cwd.toAbsolutePath().normalize().toString(), config.provider, config.model,
                config.reasoningEffort, config.maxTokens, config.approvalHandler?.let { true }, config.userQuestionHandler?.let { Capabilities() }))
                .also { initialized = it }
        } catch (error: Throwable) {
            try { closeRuntime() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    /** Lazy handle; omitted ids follow the native SDK session-<UUID hex> convention. */
    @JvmOverloads fun session(id: String? = null): HarnessSession {
        val sessionId = id ?: HarnessProtocol.newSessionId()
        require(sessionId.isNotBlank()); return HarnessSession(this, sessionId)
    }
    @JvmSynthetic suspend fun resumeSession(id: String): HarnessSession {
        start(); client.resumeSession(id); return session(id)
    }
    @JvmSynthetic suspend fun run(input: String, sessionId: String? = null, observer: NotificationObserver? = null): RunResult =
        session(sessionId).run(input, observer)
    @JvmSynthetic suspend fun run(input: List<ContentBlock>, sessionId: String? = null, observer: NotificationObserver? = null): RunResult =
        session(sessionId).run(input, observer)
    @JvmSynthetic suspend fun runStreaming(input: String, sessionId: String? = null, observer: NotificationObserver? = null): RunSummary =
        session(sessionId).runStreaming(input, observer)
    @JvmSynthetic suspend fun runStreaming(input: List<ContentBlock>, sessionId: String? = null, observer: NotificationObserver? = null): RunSummary =
        session(sessionId).runStreaming(input, observer)
    @JvmSynthetic suspend fun closeAsync() {
        // Teardown must not wait for an initialize response holding lifecycle.
        closeRuntime()
        withContext(NonCancellable) { lifecycle.withLock { initialized = null } }
    }
    private suspend fun closeRuntime() = withContext(NonCancellable) {
        runtime.stopAccepting()
        try { client.closeAsync() } finally { runtime.awaitCommands() }
    }
    override fun close() = runBlocking { closeAsync() }
    @JvmName("start") fun startBlocking(): InitializeResult = runBlocking { start() }
    @JvmName("resumeSession") fun resumeSessionBlocking(id: String): HarnessSession = runBlocking { resumeSession(id) }
    @JvmName("run") @JvmOverloads fun runBlocking(input: String, sessionId: String? = null, observer: NotificationObserver? = null): RunResult =
        runBlocking { run(input, sessionId, observer) }
    @JvmName("run") @JvmOverloads fun runBlocking(input: List<ContentBlock>, sessionId: String? = null, observer: NotificationObserver? = null): RunResult =
        runBlocking { run(input, sessionId, observer) }
    @JvmName("runStreaming") @JvmOverloads fun runStreamingBlocking(input: String, sessionId: String? = null, observer: NotificationObserver? = null): RunSummary =
        runBlocking { runStreaming(input, sessionId, observer) }
    @JvmName("runStreaming") @JvmOverloads fun runStreamingBlocking(input: List<ContentBlock>, sessionId: String? = null, observer: NotificationObserver? = null): RunSummary =
        runBlocking { runStreaming(input, sessionId, observer) }
}
