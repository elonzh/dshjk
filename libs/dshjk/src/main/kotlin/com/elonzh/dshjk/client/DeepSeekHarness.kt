package com.elonzh.dshjk.client

import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lazily initialized runtime, shared by multiple independent sessions. Always close the owner. */
class DeepSeekHarness @JvmOverloads constructor(val config: HarnessConfig = HarnessConfig()) : AutoCloseable {
    val client = HarnessClient(config)
    private val lifecycle = Mutex()
    private var initialized: InitializeResult? = null
    private var closed = false
    @JvmSynthetic suspend fun start(): InitializeResult = lifecycle.withLock {
        check(!closed) { "Harness closed" }
        initialized?.let { return@withLock it }
        try {
            client.initialize(InitializeParams(config.cwd.toAbsolutePath().normalize().toString(), config.provider, config.model,
                config.reasoningEffort, config.maxTokens, config.approvalHandler?.let { true }, config.userQuestionHandler?.let { Capabilities() }))
                .also { initialized = it }
        } catch (error: Throwable) {
            closed = true
            try { client.closeAsync() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
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
    @JvmSynthetic suspend fun closeAsync() = withContext(NonCancellable) {
        // Teardown must not wait for an initialize response holding lifecycle.
        client.closeAsync()
        lifecycle.withLock { closed = true }
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
