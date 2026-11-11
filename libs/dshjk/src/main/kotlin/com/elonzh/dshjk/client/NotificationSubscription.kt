package com.elonzh.dshjk.client

import com.elonzh.dshjk.error.*
import com.elonzh.dshjk.protocol.HarnessNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.jdk9.asPublisher
import kotlinx.coroutines.time.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Eagerly registered, independently buffered stream. Use one consumer per subscription. */
class NotificationSubscription internal constructor(
    capacity: Int,
    private val filter: (HarnessNotification) -> Boolean,
    private val detach: (NotificationSubscription) -> Unit,
) : AutoCloseable {
    private val queue = Channel<HarnessNotification>(capacity)
    private val closed = AtomicBoolean(false)
    internal fun push(notification: HarnessNotification) {
        if (closed.get()) return
        try {
            if (filter(notification) && queue.trySend(notification).isFailure) fail(SlowConsumerException())
        } catch (error: Throwable) { fail(error) }
    }
    internal fun fail(error: Throwable) {
        if (closed.compareAndSet(false, true)) { detach(this); queue.close(error) }
    }
    @JvmSynthetic suspend fun next(timeout: Duration? = null): HarnessNotification =
        if (timeout == null) queue.receive() else {
            require(timeout >= Duration.ofMillis(1))
            withTimeoutOrNull(timeout) { queue.receive() }
                ?: throw RequestTimeoutException("Notification wait timed out")
        }
    fun tryNext(): HarnessNotification? = queue.tryReceive().getOrNull()
    @get:JvmSynthetic val events: Flow<HarnessNotification> get() = flow { while (true) emit(next()) }
    fun events(): java.util.concurrent.Flow.Publisher<HarnessNotification> = events.asPublisher(Dispatchers.IO)
    @JvmName("next") @JvmOverloads
    fun nextBlocking(timeout: Duration? = null): HarnessNotification = runBlocking { next(timeout) }
    override fun close() {
        closed.set(true)
        detach(this)
        queue.cancel(CancellationException("Notification subscription closed"))
    }
}
