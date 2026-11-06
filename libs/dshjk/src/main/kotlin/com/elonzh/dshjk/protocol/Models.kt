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
sealed interface ContentBlock {
    fun toJson(): JsonObject
    data class Text(val text: String) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "text"); put("text", text) }
    }
    data class Reasoning(val text: String) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "reasoning"); put("text", text) }
    }
    data class EncodedImage @JvmOverloads constructor(val data: String, val mimeType: String, val name: String? = null) : ContentBlock {
        init { require(mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) }
        override fun toJson() = buildJsonObject {
            put("type", "image"); put("data", data); put("mimeType", mimeType); name?.let { put("name", it) }
        }
    }
    data class Image @JvmOverloads constructor(val attachment: JsonObject, val offloaded: Boolean = false) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "image"); put("attachment", attachment); if (offloaded) put("offloaded", true) }
    }
    data class File(val attachment: JsonObject) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "file"); put("attachment", attachment) }
    }
    data class ToolCall(val id: String, val name: String, val arguments: String) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "tool-call"); put("id", id); put("name", name); put("arguments", arguments) }
    }
    data class ToolAddition(val toolName: String) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "tool-addition"); put("toolName", toolName) }
    }
    data class ToolRemoval(val toolName: String) : ContentBlock {
        override fun toJson() = buildJsonObject { put("type", "tool-removal"); put("toolName", toolName) }
    }
    data class Extension(val value: JsonObject) : ContentBlock { override fun toJson() = value }
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
    val options: List<QuestionOption>? = null, val multiSelect: Boolean? = null, val intent: JsonObject? = null,
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
