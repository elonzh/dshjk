package com.elonzh.dshjk.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

/** Complete durable envelope, including plugin-owned fields, without rewriting session logs. */
@ConsistentCopyVisibility
data class SessionEvent internal constructor(val type: String, val data: JsonElement, val raw: JsonObject) {
    private val objectData: JsonObject get() = data.objectValue()
    val seq: Long? get() = (raw["seq"] as? JsonPrimitive)?.longOrNull
    /** Decode any plugin's documented payload with its own serializer. */
    fun <T> decodeData(deserializer: DeserializationStrategy<T>): T = HarnessProtocol.json.decodeFromJsonElement(deserializer, data)
    val assistantMessage: AssistantMessage? get() = if (type == "assistant/message") {
        val message = objectData.objectAt("message")
        AssistantMessage(message.string("id"), message.arrayAt("content").map { HarnessProtocol.content(it.objectValue()) }, message)
    } else null
    val turnEnd: TurnEnd? get() = if (type == "turn/end") {
        val reason = objectData.objectAt("reason")
        TurnEnd(reason.string("kind"), reason)
    } else null
    val commandRun: CommandRun? get() = if (type == "command/run") {
        CommandRun(objectData.string("commandId"), objectData.string("name"), objectData.optionalString("args"), objectData.objectAt("source"))
    } else null
    val commandDone: CommandDone? get() = if (type == "command/done") {
        CommandDone(objectData.string("commandId"), objectData.string("kind"), objectData.optionalString("text"), (objectData["sourceEventSeq"] as? JsonPrimitive)?.longOrNull)
    } else null
    val inboxInsertedIds: List<String>? get() = if (type == "agent/inbox/spliced")
        objectData.arrayAt("inserted").map { it.objectValue().string("id") } else null
}
data class AssistantMessage(val id: String, val content: List<ContentBlock>, val raw: JsonObject)
data class TurnEnd(val kind: String, val reason: JsonObject)
data class CommandRun(val commandId: String, val name: String, val args: String?, val source: JsonObject)
data class CommandDone(val commandId: String, val kind: String, val text: String?, val sourceEventSeq: Long?)

/** All six SDK notification methods are represented, while retaining their original params. */
sealed class HarnessNotification(val method: String, open val params: JsonObject) {
    data class Event(val sessionId: String, val event: SessionEvent, override val params: JsonObject) : HarnessNotification("session.event", params)
    data class Status(val sessionId: String, val status: AgentStatus, override val params: JsonObject) : HarnessNotification("session.status", params)
    data class QueryCompleted(val sessionId: String, val commandId: String, val finalResponse: String, val finishReason: String,
        override val params: JsonObject) : HarnessNotification("session.query.completed", params)
    data class SubagentStarted(val parentSessionId: String, val childSessionId: String, override val params: JsonObject) : HarnessNotification("subagent.started", params)
    data class SubagentFinished(val provider: String, val agentId: String, val parentSessionId: String, val childSessionId: String,
        val status: RunStatus, val stopReason: JsonElement, val lastAssistantMessage: List<ContentBlock>?,
        override val params: JsonObject) : HarnessNotification("subagent.finished", params)
    data class QuestionCancelled(val requestId: String, val sessionId: String, override val params: JsonObject) : HarnessNotification("user-question.cancelled", params)
    data class Extension(val name: String, override val params: JsonObject) : HarnessNotification(name, params)
}
enum class AgentStatus { IDLE, RUNNING }
enum class RunStatus { OK, ERROR }
data class RunResult(val sessionId: String, val finalResponse: String, val finishReason: String?,
    val events: List<SessionEvent>, val notifications: List<HarnessNotification>)
