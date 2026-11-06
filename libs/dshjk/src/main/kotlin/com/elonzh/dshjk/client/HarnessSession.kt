package com.elonzh.dshjk.client

import com.elonzh.dshjk.error.ProtocolException
import com.elonzh.dshjk.protocol.*
import kotlinx.coroutines.runBlocking

/** A stable native Session identity; the runtime owns history, cwd, tools, permissions and goals. */
class HarnessSession internal constructor(val harness: DeepSeekHarness, val id: String) {
    @JvmSynthetic suspend fun getWorkingDirectory(): String { harness.start(); return harness.client.getWorkingDirectory(id) }
    @JvmSynthetic suspend fun setWorkingDirectory(path: String): String { harness.start(); return harness.client.setWorkingDirectory(id, path) }
    @JvmSynthetic suspend fun waitForIdle() { harness.start(); harness.client.waitForSession(id) }
    @JvmSynthetic suspend fun run(input: String, observer: NotificationObserver? = null): RunResult = run(HarnessProtocol.text(input), observer)
    @JvmSynthetic suspend fun run(input: List<ContentBlock>, observer: NotificationObserver? = null): RunResult {
        harness.start()
        val subscription = harness.client.subscribe(id)
        try {
            val response = harness.client.query(id, input)
            val activity = RunActivity(id, response, observer)
            while (true) {
                val notification = subscription.next()
                if (response is QueryResult.Command && notification is HarnessNotification.QueryCompleted && notification.sessionId == id) {
                    if (notification.commandId != response.execution.commandId) continue
                    // A failure completion can precede its queued command/done on the same stream.
                    while (!activity.commandDone) activity.record(subscription.tryNext() ?: break)
                    if (!activity.commandDone) throw ProtocolException("Command completion missing matching lifecycle events")
                    return activity.result(notification)
                }
                if (!activity.record(notification)) continue
                if (response is QueryResult.Prompt && notification is HarnessNotification.Status && notification.sessionId == id && notification.status == AgentStatus.IDLE)
                    return activity.result()
            }
        } finally { subscription.close() }
    }
    @JvmName("getWorkingDirectory") fun getWorkingDirectoryBlocking(): String = runBlocking { getWorkingDirectory() }
    @JvmName("setWorkingDirectory") fun setWorkingDirectoryBlocking(path: String): String = runBlocking { setWorkingDirectory(path) }
    @JvmName("waitForIdle") fun waitForIdleBlocking() = runBlocking { waitForIdle() }
    @JvmName("run") @JvmOverloads fun runBlocking(input: String, observer: NotificationObserver? = null): RunResult = runBlocking { run(input, observer) }
    @JvmName("run") @JvmOverloads fun runBlocking(input: List<ContentBlock>, observer: NotificationObserver? = null): RunResult = runBlocking { run(input, observer) }
}

private class RunActivity(private val id: String, private val receipt: QueryResult, private val observer: NotificationObserver?) {
    private var received = false
    var commandDone = false
        private set
    private val events = mutableListOf<SessionEvent>()
    private val notifications = mutableListOf<HarnessNotification>()
    fun record(n: HarnessNotification): Boolean {
        if (n is HarnessNotification.QueryCompleted) return false
        val event = (n as? HarnessNotification.Event)?.takeIf { it.sessionId == id }?.event
        if (!received) {
            received = when(receipt) {
                is QueryResult.Prompt -> event?.inboxInsertedIds?.contains(receipt.messageId) == true
                is QueryResult.Command -> event?.commandRun?.commandId == receipt.execution.commandId
            }
            if (!received) return false
        }
        if (receipt is QueryResult.Command && event?.commandDone?.commandId == receipt.execution.commandId) commandDone = true
        if (event != null) events.add(event)
        notifications.add(n)
        observer?.onNotification(n)
        return true
    }
    fun result(completed: HarnessNotification.QueryCompleted? = null): RunResult = RunResult(id,
        completed?.finalResponse ?: events.lastOrNull { it.type == "assistant/message" }?.assistantMessage?.content
            ?.filterIsInstance<ContentBlock.Text>()?.joinToString("") { it.text }.orEmpty(),
        completed?.finishReason ?: events.lastOrNull { it.type == "turn/end" }?.turnEnd?.kind,
        events.toList(), notifications.toList())
}
