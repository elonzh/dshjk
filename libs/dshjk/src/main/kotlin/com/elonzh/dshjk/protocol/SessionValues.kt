package com.elonzh.dshjk.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable data class LlmFailure(val message: String, val code: String, val status: Int? = null,
    val providerRetryAfterMs: Double? = null, val requestId: String? = null, val offloadImages: Int? = null)
@Serializable data class TokenUsage(val inputTokens: Long, val outputTokens: Long, val totalTokens: Long? = null,
    val cacheReadTokens: Long? = null, val cacheWriteTokens: Long? = null, val reasoningTokens: Long? = null)
@Serializable data class Message(val id: String, val role: MessageRole, val content: List<ContentBlock>,
    val source: JsonObject, val toolCallId: String? = null, val isError: Boolean? = null)
@Serializable data class ToolFailure(val name: String, val code: String, val reason: String? = null)
@Serializable data class ToolSchema(val name: String, val description: String, val parameters: JsonObject, val deferLoading: Boolean? = null)
@Serializable data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)
@Serializable data class LlmCallConfig(val provider: String, val model: String, val reasoningEffort: String? = null,
    val temperature: Double? = null, val maxTokens: Int? = null, val stop: List<String>? = null)
@Serializable data class AdapterDefaults(val reasoningEffort: Boolean? = null, val maxTokens: Boolean? = null)
@Serializable data class RequestHeader(val config: LlmCallConfig, val adapterDefaults: AdapterDefaults? = null, val tools: List<ToolSchema>? = null)
@Serializable data class ImageAttachment(val attachmentId: String, val mediaType: ImageMediaType, val bytes: Long,
    val width: Int, val height: Int, val name: String? = null, val originalDimensions: ImageDimensions? = null)
@Serializable data class ImageDimensions(val width: Int, val height: Int)
@Serializable data class FileAttachment(val attachmentId: String, val name: String, val bytes: Long)

@Serializable(with = TurnEndReasonSerializer::class)
sealed interface TurnEndReason {
    val kind: FinishReason
    @Serializable data object Completed : TurnEndReason { override val kind get() = FinishReason.Builtin.COMPLETED }
    @Serializable data object Blocked : TurnEndReason { override val kind get() = FinishReason.Builtin.BLOCKED }
    @Serializable data object MaxTokens : TurnEndReason { override val kind get() = FinishReason.Builtin.MAX_TOKENS }
    @Serializable data object Interrupted : TurnEndReason { override val kind get() = FinishReason.Builtin.INTERRUPTED }
    @Serializable data object Forked : TurnEndReason { override val kind get() = FinishReason.Builtin.FORKED }
    @Serializable data class Aborted(val reason: AbortReason) : TurnEndReason { override val kind get() = FinishReason.Builtin.ABORTED }
    @Serializable data class Error(val error: LlmFailure) : TurnEndReason { override val kind get() = FinishReason.Builtin.ERROR }
    data class Extension(override val kind: FinishReason.Extension, val raw: JsonObject) : TurnEndReason
}
object TurnEndReasonSerializer : KSerializer<TurnEndReason> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): TurnEndReason {
        val o = (decoder as JsonDecoder).decodeJsonElement().objectValue()
        return when(val k = FinishReason.fromWire(o.string("kind"))) {
            FinishReason.Builtin.COMPLETED -> TurnEndReason.Completed
            FinishReason.Builtin.BLOCKED -> TurnEndReason.Blocked
            FinishReason.Builtin.MAX_TOKENS -> TurnEndReason.MaxTokens
            FinishReason.Builtin.INTERRUPTED -> TurnEndReason.Interrupted
            FinishReason.Builtin.FORKED -> TurnEndReason.Forked
            FinishReason.Builtin.ABORTED -> TurnEndReason.Aborted(HarnessProtocol.json.decodeFromJsonElement(o.required("reason")))
            FinishReason.Builtin.ERROR -> TurnEndReason.Error(HarnessProtocol.json.decodeFromJsonElement(o.required("error")))
            is FinishReason.Extension -> TurnEndReason.Extension(k, o)
        }
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: TurnEndReason) {
        val o = buildJsonObject {
            put("kind", value.kind.wireName)
            when(value) {
                is TurnEndReason.Aborted -> put("reason", HarnessProtocol.json.encodeToJsonElement(value.reason))
                is TurnEndReason.Error -> put("error", HarnessProtocol.json.encodeToJsonElement(value.error))
                is TurnEndReason.Extension -> value.raw.forEach { (k, v) -> put(k, v) }
                else -> Unit
            }
        }
        (encoder as JsonEncoder).encodeJsonElement(o)
    }
}
@Serializable sealed interface AbortReason {
    @Serializable @SerialName("user") data object User : AbortReason
    @Serializable @SerialName("parent") data object Parent : AbortReason
    @Serializable @SerialName("disposed") data object Disposed : AbortReason
    @Serializable @SerialName("legacy") data object Legacy : AbortReason
    @Serializable @SerialName("hook") data class Hook(val reason: String) : AbortReason
}

@Serializable data class SequenceRange(val start: Long, val end: Long)
@Serializable data class ImageOffloadTarget(val seq: Long, val imageIndexes: List<Int>)
@Serializable data class PresentedFile(val path: String, val description: String? = null)
@Serializable data class TodoItem(val content: String, val status: TodoStatus)
@Serializable data class GoalRef(val id: String, val revision: Long)
@Serializable data class GoalSnapshot(val id: String, val revision: Long, val objective: String, val phase: GoalPhase,
    val maxGoalRounds: Int, val blockedReason: GoalBlockReason? = null)
@Serializable data class GoalBlockReason(val code: String, val message: String)
@Serializable data class TeamMember(val id: String, val name: String, val description: String, val provider: String,
    val context: TeamContext, val phase: TeamMemberPhase, val error: String? = null)
@Serializable data class TeamTask(val id: String, val revision: Long, val subject: String, val description: String,
    val status: TeamTaskStatus, val blockedBy: List<String>, val writeScopes: List<String>, val ownerId: String? = null, val ownerName: String? = null)
@Serializable data class TeamMessage(val id: String, val senderId: String, val senderName: String, val targetId: String, val content: List<ContentBlock>)
@Serializable data class JobToolCall(val callId: String, val rootCallId: String)
@Serializable data class MessageFeedback(val messageId: String, val rating: MessageFeedbackRating, val version: String,
    val createdAt: Long, val updatedAt: Long, val note: String? = null, val category: FeedbackCategory? = null)
@Serializable data class AllowedModelRoute(val provider: String, val model: String)
@Serializable data class ToolRestriction(val allow: List<String>? = null, val deny: List<String>? = null)

@Serializable sealed interface QuestionIntent {
    @Serializable @SerialName("plan-review") data class PlanReview(val approve: String, val callId: String? = null) : QuestionIntent
    @Serializable @SerialName("approval") data class Approval(val approve: String, val reject: String, val always: String? = null,
        val subject: ApprovalSubject? = null, val display: ApprovalDisplay? = null) : QuestionIntent
}
@Serializable data class ApprovalSubject(val name: String, val icon: String? = null)
@Serializable data class ApprovalDisplay(val question: Map<String, String>, val detail: Map<String, String>? = null, val approve: Map<String, String>? = null)
