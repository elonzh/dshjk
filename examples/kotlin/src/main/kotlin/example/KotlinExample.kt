package example

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.error.TransportClosedException
import com.elonzh.dshjk.protocol.ContentBlock
import com.elonzh.dshjk.protocol.HarnessNotification
import com.elonzh.dshjk.protocol.SessionEventData
import com.elonzh.dshjk.protocol.TurnEndReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Streams native SDK events; assistant text arrives when each model step is recorded. */
fun main(args: Array<String>) = runBlocking {
    val harness = DeepSeekHarness()
    try {
        System.err.println("[runtime] ${harness.start().serverInfo}")
        val session = harness.session()
        System.err.println("[session] ${session.id}")
        System.err.println("[cwd] ${session.getWorkingDirectory()}")
        if (args.isEmpty()) return@runBlocking

        // Registration is eager; events remain buffered until the collector runs.
        val notifications = harness.client.subscribe(session.id)
        var closing = false
        val collector = launch {
            try {
                notifications.events.collect { display(it, session.id) }
            } catch (error: TransportClosedException) {
                if (!closing) throw error
            }
        }
        try {
            val result = session.run(args[0])
            closing = true
            harness.closeAsync()
            collector.join() // Drain buffered output before finishing the example.
            System.err.println("[run complete] ${result.finishReason?.wireName ?: "no terminal event"}")
            // Text was already printed by the collector; do not print finalResponse again.
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                notifications.close()
            }
        }
    } finally { harness.closeAsync() }
}

private fun display(notification: HarnessNotification, rootSessionId: String) {
    when (notification) {
        is HarnessNotification.Event -> {
            val prefix = "[${notification.sessionId}] "
            when (val data = notification.event.data) {
                is SessionEventData.AssistantMessage -> {
                    val text = data.message.content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
                    if (notification.sessionId == rootSessionId) printText(text)
                    else System.err.println("${prefix}assistant: $text")
                }
                is SessionEventData.TurnStarted -> System.err.println("${prefix}turn ${data.turn} started")
                is SessionEventData.ToolCalled -> System.err.println("${prefix}tool ${data.name} started: ${data.callId}")
                is SessionEventData.ToolResult -> {
                    val status = if (data.message.isError == true) "error" else "ok"
                    System.err.println("${prefix}tool result ${data.message.toolCallId}: $status")
                }
                is SessionEventData.CommandRun -> System.err.println("${prefix}command /${data.name} started")
                is SessionEventData.CommandDone -> {
                    System.err.println("${prefix}command ${data.commandId}: ${data.kind.wireName}")
                    if (notification.sessionId == rootSessionId) data.text?.let(::printText)
                }
                is SessionEventData.TurnEnded -> {
                    System.err.println("${prefix}turn ${data.turn}: ${data.reason.kind.wireName}")
                    val failure = data.reason as? TurnEndReason.Error
                    if (failure != null) System.err.println("$prefix${failure.error.code}: ${failure.error.message}")
                }
                else -> Unit
            }
        }
        is HarnessNotification.Status -> System.err.println("[${notification.sessionId}] status: ${notification.status.wireName}")
        is HarnessNotification.SubagentStarted -> System.err.println("[subagent started] ${notification.childSessionId}")
        is HarnessNotification.SubagentFinished -> System.err.println("[subagent finished] ${notification.childSessionId}: ${notification.stopReason.wireName}")
        else -> Unit
    }
}

private fun printText(text: String) {
    if (text.isEmpty()) return
    print(text)
    if (!text.endsWith("\n")) println()
    System.out.flush()
}
