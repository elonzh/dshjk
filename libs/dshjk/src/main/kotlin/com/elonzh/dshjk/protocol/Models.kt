package com.elonzh.dshjk.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class ServerInfo(val name: String, val version: String)
@Serializable data class InitializeResult(val serverInfo: ServerInfo)
@Serializable data class InitializeParams @JvmOverloads constructor(
    val cwd: String, val provider: String, val model: String,
    val reasoningEffort: String? = null, val maxTokens: Int? = null,
    val approvalRequests: Boolean? = null, val capabilities: Capabilities? = null,
)
@Serializable data class Capabilities(val userQuestions: Boolean = true)
@Serializable data class PromptReceipt(val messageId: String)
@Serializable data class CommandExecution(val commandId: String, val result: CommandResult)
@Serializable sealed interface CommandResult {
    @Serializable @SerialName("success") data class Success @JvmOverloads constructor(
        val text: String? = null, val sourceEventSeq: Long? = null,
    ) : CommandResult
    @Serializable @SerialName("error") data class Error(val text: String) : CommandResult
}
sealed interface QueryResult {
    data class Prompt(val messageId: String) : QueryResult
    data class Command(val execution: CommandExecution) : QueryResult
}

/** Native content is extensible. Known blocks have typed constructors; extension blocks retain exact JSON. */
@Serializable(with = ContentBlockSerializer::class)
sealed interface ContentBlock {
    val type: ContentType
    fun toJson(): JsonObject
    data class Text(val text: String) : ContentBlock {
        override val type get() = ContentType.Builtin.TEXT
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("text", text) }
    }
    data class Reasoning(val text: String) : ContentBlock {
        override val type get() = ContentType.Builtin.REASONING
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("text", text) }
    }
    data class EncodedImage @JvmOverloads constructor(val data: String, val mimeType: ImageMediaType, val name: String? = null) : ContentBlock {
        override val type get() = ContentType.Builtin.IMAGE
        override fun toJson() = buildJsonObject {
            put("type", type.wireName); put("data", data); put("mimeType", mimeType.wireName); name?.let { put("name", it) }
        }
    }
    data class Image @JvmOverloads constructor(val attachment: ImageAttachment, val offloaded: Boolean = false) : ContentBlock {
        override val type get() = ContentType.Builtin.IMAGE
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("attachment", HarnessProtocol.json.encodeToJsonElement(attachment)); if (offloaded) put("offloaded", true) }
    }
    data class File(val attachment: FileAttachment) : ContentBlock {
        override val type get() = ContentType.Builtin.FILE
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("attachment", HarnessProtocol.json.encodeToJsonElement(attachment)) }
    }
    data class ToolCall(val id: String, val name: String, val arguments: String) : ContentBlock {
        override val type get() = ContentType.Builtin.TOOL_CALL
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("id", id); put("name", name); put("arguments", arguments) }
    }
    data class ToolAddition(val toolName: String) : ContentBlock {
        override val type get() = ContentType.Builtin.TOOL_ADDITION
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("toolName", toolName) }
    }
    data class ToolRemoval(val toolName: String) : ContentBlock {
        override val type get() = ContentType.Builtin.TOOL_REMOVAL
        override fun toJson() = buildJsonObject { put("type", type.wireName); put("toolName", toolName) }
    }
    data class Extension(val value: JsonObject) : ContentBlock {
        override val type: ContentType.Extension = ContentType.Extension(value.string("type"))
        override fun toJson() = value
    }
}

@Serializable data class ApprovalRequest @JvmOverloads constructor(
    val sessionId: String, val toolName: String, val callId: String? = null, val reason: String? = null,
)
@Serializable enum class ApprovalOutcome {
    @SerialName("allowed-once") ALLOWED_ONCE,
    @SerialName("rejected") REJECTED,
    @SerialName("cancelled") CANCELLED,
}
@Serializable data class ApprovalResult(val outcome: ApprovalOutcome)
@Serializable data class UserQuestionRequest(val requestId: String, val sessionId: String, val questions: List<UserQuestion>)
@Serializable data class UserQuestion @JvmOverloads constructor(
    val id: String, val question: String, val detail: String? = null, val header: String? = null,
    val options: List<QuestionOption>? = null, val multiSelect: Boolean? = null, val intent: QuestionIntent? = null,
)
@Serializable data class QuestionOption @JvmOverloads constructor(val label: String, val description: String? = null)
@Serializable data class AnswerItem @JvmOverloads constructor(val id: String, val selected: List<String>, val custom: String? = null)
sealed interface UserQuestionResult {
    data class Answer(val answers: List<AnswerItem>) : UserQuestionResult
    data object Cancelled : UserQuestionResult
}
class CancellationToken internal constructor() {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    internal fun cancel() { cancelled.set(true) }
}
/** Callbacks run independently of the protocol reader; they may make further SDK calls. */
fun interface ApprovalHandler { fun handle(request: ApprovalRequest, cancellation: CancellationToken): ApprovalResult }
fun interface UserQuestionHandler { fun handle(request: UserQuestionRequest, cancellation: CancellationToken): UserQuestionResult }
fun interface NotificationObserver { fun onNotification(notification: HarnessNotification) }

object ContentBlockSerializer : kotlinx.serialization.KSerializer<ContentBlock> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ContentBlock =
        HarnessProtocol.content((decoder as JsonDecoder).decodeJsonElement().objectValue())
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ContentBlock) =
        (encoder as JsonEncoder).encodeJsonElement(value.toJson())
}
