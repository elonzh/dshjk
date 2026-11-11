package com.elonzh.dshjk.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

/** Typed built-in data plus the complete original durable envelope. */
class SessionEvent internal constructor(val data: SessionEventData, val raw: JsonObject) {
    val type: SessionEventType get() = data.type
    val seq: Long get() = (raw["seq"] as? JsonPrimitive)?.longOrNull ?: throw com.elonzh.dshjk.error.ProtocolException("Invalid event seq")
    val time: Long get() = (raw["time"] as? JsonPrimitive)?.longOrNull ?: throw com.elonzh.dshjk.error.ProtocolException("Invalid event time")
    /** Decode a plugin-owned payload using the plugin's own serializer. */
    fun <T> decodeData(deserializer: DeserializationStrategy<T>): T = HarnessProtocol.json.decodeFromJsonElement(deserializer, raw.getValue("data"))
}

/** Known methods and their payloads have typed discriminators; extensions retain their wire names. */
sealed class HarnessNotification(val method: NotificationMethod, open val params: JsonObject) {
    data class Event(val sessionId: String, val event: SessionEvent, override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.SESSION_EVENT, params)
    data class Status(val sessionId: String, val status: AgentStatus, override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.SESSION_STATUS, params)
    data class QueryCompleted(val sessionId: String, val commandId: String, val finalResponse: String, val finishReason: FinishReason,
        override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.SESSION_QUERY_COMPLETED, params)
    data class SubagentStarted(val parentSessionId: String, val childSessionId: String, override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.SUBAGENT_STARTED, params)
    data class SubagentFinished(val provider: String, val agentId: String, val parentSessionId: String, val childSessionId: String,
        val status: RunStatus, val stopReason: SubagentStopReason, val lastAssistantMessage: List<ContentBlock>?,
        override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.SUBAGENT_FINISHED, params)
    data class QuestionCancelled(val requestId: String, val sessionId: String, override val params: JsonObject) : HarnessNotification(NotificationMethod.Builtin.USER_QUESTION_CANCELLED, params)
    data class Extension(val name: String, override val params: JsonObject) : HarnessNotification(NotificationMethod.Extension(name), params)
}
data class RunResult(val sessionId: String, val finalResponse: String, val finishReason: FinishReason?,
    val events: List<SessionEvent>, val notifications: List<HarnessNotification>)

/** The final root-session outcome of a streamed run. */
data class RunSummary(val sessionId: String, val finalResponse: String, val finishReason: FinishReason?)
