@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.elonzh.dshjk.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable @JsonClassDiscriminator("type")
sealed interface AssistantStreamRecord {
    @Serializable @SerialName("text-chunks") data class TextChunks(val time0: Long, val index: Int, val dt: List<Long>, val texts: List<String>) : AssistantStreamRecord
    @Serializable @SerialName("reasoning-chunks") data class ReasoningChunks(val time0: Long, val index: Int, val dt: List<Long>, val texts: List<String>) : AssistantStreamRecord
    @Serializable @SerialName("tool-call-chunks") data class ToolCallChunks(val time0: Long, val index: Int, val dt: List<Long>, val id: String,
        val args: List<String>, val name: String? = null) : AssistantStreamRecord
    @Serializable @SerialName("chunk") data class Chunk(val time: Long, val chunk: StreamChunk) : AssistantStreamRecord
}
@Serializable @JsonClassDiscriminator("type")
sealed interface StreamChunk {
    @Serializable @SerialName("block-start") data class BlockStart(val index: Int, val blockType: ContentType) : StreamChunk
    @Serializable @SerialName("text-delta") data class TextDelta(val index: Int, val text: String) : StreamChunk
    @Serializable @SerialName("reasoning-delta") data class ReasoningDelta(val index: Int, val text: String) : StreamChunk
    @Serializable @SerialName("tool-call-delta") data class ToolCallDelta(val index: Int, val id: String, val argumentsDelta: String, val name: String? = null) : StreamChunk
    @Serializable @SerialName("block-end") data class BlockEnd(val index: Int, val block: ContentBlock) : StreamChunk
    @Serializable @SerialName("usage") data class Usage(val usage: TokenUsage) : StreamChunk
    @Serializable @SerialName("finish") data class Finish(val reason: ModelFinishReason, val replayState: JsonObject? = null) : StreamChunk
}
@Serializable(with = ModelFinishReasonSerializer::class) sealed interface ModelFinishReason {
    @Serializable @SerialName("stop") data object Stop : ModelFinishReason
    @Serializable @SerialName("tool-calls") data object ToolCalls : ModelFinishReason
    @Serializable @SerialName("max-tokens") data object MaxTokens : ModelFinishReason
    @Serializable @SerialName("aborted") data class Aborted(val failure: LlmFailure) : ModelFinishReason
    @Serializable @SerialName("error") data class Error(val failure: LlmFailure) : ModelFinishReason
    data class Extension(val kind: String, val raw: JsonObject) : ModelFinishReason
}
@Serializable sealed interface SessionTitleSource {
    @Serializable @SerialName("fallback") data object Fallback : SessionTitleSource
    @Serializable @SerialName("user") data object User : SessionTitleSource
    @Serializable @SerialName("provider") data class Provider(val provider: String, val model: ModelSelection? = null) : SessionTitleSource
}
@Serializable data class ScheduleRecord(val id: String, val kind: ScheduleKind, val prompt: String, val scheduledAt: String,
    val title: String? = null, val afterSeconds: Double? = null, val everySeconds: Double? = null)
@Serializable enum class ScheduleKind { @SerialName("after") AFTER, @SerialName("at") AT, @SerialName("every") EVERY }
@Serializable enum class ScheduleOperation { @SerialName("create") CREATE, @SerialName("delete") DELETE, @SerialName("dispatch") DISPATCH }
@Serializable enum class GoalChangeOperation {
    @SerialName("create") CREATE, @SerialName("edit") EDIT, @SerialName("pause") PAUSE,
    @SerialName("resume") RESUME, @SerialName("complete") COMPLETE, @SerialName("block") BLOCK, @SerialName("clear") CLEAR
}
@Serializable data class CommandSource(val kind: CommandSourceKind)
@Serializable enum class CommandSourceKind { @SerialName("user") USER }
@Serializable data class SearchLlmBody(val model: String, val max_tokens: Int, val messages: List<SearchMessage>,
    val tools: List<SearchTool>, val thinking: SearchThinking? = null)
@Serializable data class SearchMessage(val content: List<ContentBlock>, val role: MessageRole)
@Serializable data class SearchTool(val max_uses: Int, val type: SearchToolType, val name: SearchToolName)
@Serializable data class SearchThinking(val type: SearchThinkingType)

object ModelFinishReasonSerializer : kotlinx.serialization.KSerializer<ModelFinishReason> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ModelFinishReason {
        val o = (decoder as JsonDecoder).decodeJsonElement().objectValue()
        return when(val kind = o.string("kind")) {
            "stop" -> ModelFinishReason.Stop
            "tool-calls" -> ModelFinishReason.ToolCalls
            "max-tokens" -> ModelFinishReason.MaxTokens
            "aborted" -> ModelFinishReason.Aborted(HarnessProtocol.json.decodeFromJsonElement(o.required("failure")))
            "error" -> ModelFinishReason.Error(HarnessProtocol.json.decodeFromJsonElement(o.required("failure")))
            else -> ModelFinishReason.Extension(kind, o)
        }
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ModelFinishReason) {
        val o = when(value) {
            ModelFinishReason.Stop -> buildJsonObject { put("kind", "stop") }
            ModelFinishReason.ToolCalls -> buildJsonObject { put("kind", "tool-calls") }
            ModelFinishReason.MaxTokens -> buildJsonObject { put("kind", "max-tokens") }
            is ModelFinishReason.Aborted -> buildJsonObject { put("kind", "aborted"); put("failure", HarnessProtocol.json.encodeToJsonElement(value.failure)) }
            is ModelFinishReason.Error -> buildJsonObject { put("kind", "error"); put("failure", HarnessProtocol.json.encodeToJsonElement(value.failure)) }
            is ModelFinishReason.Extension -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(o)
    }
}
