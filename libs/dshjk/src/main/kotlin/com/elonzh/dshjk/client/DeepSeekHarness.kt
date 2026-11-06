package com.elonzh.dshjk.client

import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One lazily initialized runtime, shared by multiple independent sessions. Always close the owner. */
class DeepSeekHarness(val config: HarnessConfig) : AutoCloseable {
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
    /** A lazy handle creates no runtime resources until its first operation. */
    @JvmOverloads fun session(id: String = "session-${UUID.randomUUID()}"): HarnessSession {
        require(id.isNotBlank()); return HarnessSession(this, id)
    }
    @JvmSynthetic suspend fun resumeSession(id: String): HarnessSession {
        start(); client.resumeSession(id); return session(id)
    }
    @JvmSynthetic suspend fun run(input: String, sessionId: String = "session-${UUID.randomUUID()}", observer: NotificationObserver? = null): RunResult =
        session(sessionId).run(input, observer)
    @JvmSynthetic suspend fun run(input: List<ContentBlock>, sessionId: String = "session-${UUID.randomUUID()}", observer: NotificationObserver? = null): RunResult =
        session(sessionId).run(input, observer)
    @JvmSynthetic suspend fun closeAsync() = withContext(NonCancellable) {
        // Teardown must not wait for an initialize response holding lifecycle.
        client.closeAsync()
        lifecycle.withLock { closed = true }
    }
    override fun close() = runBlocking { closeAsync() }
    @JvmName("start") fun startBlocking(): InitializeResult = runBlocking { start() }
    @JvmName("resumeSession") fun resumeSessionBlocking(id: String): HarnessSession = runBlocking { resumeSession(id) }
    @JvmName("run") @JvmOverloads fun runBlocking(input: String, sessionId: String = "session-${UUID.randomUUID()}", observer: NotificationObserver? = null): RunResult =
        runBlocking { run(input, sessionId, observer) }
    @JvmName("run") @JvmOverloads fun runBlocking(input: List<ContentBlock>, sessionId: String = "session-${UUID.randomUUID()}", observer: NotificationObserver? = null): RunResult =
        runBlocking { run(input, sessionId, observer) }
}
