package com.elonzh.dshjk.protocol

import com.elonzh.dshjk.error.ProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

object HarnessProtocol {
    @JvmField val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; classDiscriminator = "kind" }
    @JvmStatic fun text(input: String): List<ContentBlock> = listOf(ContentBlock.Text(input))
    @JvmStatic fun content(value: JsonObject): ContentBlock = boundary("content block") {
        when (ContentType.fromWire(value.string("type"))) {
            ContentType.Builtin.TEXT -> ContentBlock.Text(value.string("text", allowEmpty = true))
            ContentType.Builtin.REASONING -> ContentBlock.Reasoning(value.string("text", allowEmpty = true))
            ContentType.Builtin.IMAGE -> if (value.containsKey("data")) ContentBlock.EncodedImage(value.string("data"), json.decodeFromJsonElement(value.required("mimeType")), value.optionalString("name"))
                else ContentBlock.Image(json.decodeFromJsonElement(value.required("attachment")), value["offloaded"] == JsonPrimitive(true))
            ContentType.Builtin.FILE -> ContentBlock.File(json.decodeFromJsonElement(value.required("attachment")))
            ContentType.Builtin.TOOL_CALL -> ContentBlock.ToolCall(value.string("id"), value.string("name"), value.string("arguments", true))
            ContentType.Builtin.TOOL_ADDITION -> ContentBlock.ToolAddition(value.string("toolName"))
            ContentType.Builtin.TOOL_REMOVAL -> ContentBlock.ToolRemoval(value.string("toolName"))
            is ContentType.Extension -> ContentBlock.Extension(value)
        }
    }
    @JvmStatic fun event(value: JsonObject): SessionEvent = boundary("session event") {
        val type = SessionEventType.fromWire(value.string("type"))
        val rawData = value["data"] ?: throw ProtocolException("Missing event data")
        val data = when (type) {
            is SessionEventType.Builtin -> json.decodeFromJsonElement(type.payloadSerializer(), rawData)
            is SessionEventType.Extension -> SessionEventData.Extension(type, rawData)
        }
        SessionEvent(data, value).also {
            require(it.seq >= 0 && it.seq <= 9007199254740991L) { "Invalid event seq" }
            require(it.time >= 0 && it.time <= 9007199254740991L) { "Invalid event time" }
        }
    }

    @JvmStatic fun notification(method: String, p: JsonObject): HarnessNotification = boundary(method) {
        when (val name = NotificationMethod.fromWire(method)) {
            NotificationMethod.Builtin.SESSION_EVENT -> HarnessNotification.Event(p.string("sessionId"), event(p.objectAt("event")), p)
            NotificationMethod.Builtin.SESSION_STATUS -> HarnessNotification.Status(p.string("sessionId"), json.decodeFromJsonElement<AgentStatus>(p.required("status")), p)
            NotificationMethod.Builtin.SESSION_QUERY_COMPLETED -> HarnessNotification.QueryCompleted(p.string("sessionId"), p.string("commandId"), p.string("finalResponse", true), FinishReason.fromWire(p.string("finishReason")), p)
            NotificationMethod.Builtin.SUBAGENT_STARTED -> HarnessNotification.SubagentStarted(p.string("parentSessionId"), p.string("childSessionId"), p)
            NotificationMethod.Builtin.SUBAGENT_FINISHED -> HarnessNotification.SubagentFinished(p.string("provider"), p.string("agentId"), p.string("parentSessionId"), p.string("childSessionId"),
                json.decodeFromJsonElement<RunStatus>(p.required("status")),
                SubagentStopReason.fromWire(p.string("stopReason")), (p["lastAssistantMessage"] as? JsonArray)?.map { content(it.objectValue()) }, p)
            NotificationMethod.Builtin.USER_QUESTION_CANCELLED -> HarnessNotification.QuestionCancelled(p.string("requestId"), p.string("sessionId"), p)
            is NotificationMethod.Extension -> HarnessNotification.Extension(name.wireName, p)
        }
    }
    internal fun query(value: JsonElement): QueryResult = boundary("session/query result") {
        val o = value.objectValue()
        when(o.string("kind")) {
            "prompt" -> QueryResult.Prompt(o.string("messageId"))
            "command" -> {
                val execution = o.objectAt("execution")
                val result = execution.objectAt("result")
                val r = when(result.string("kind")) {
                    "success" -> {
                        val seq = result["sourceEventSeq"]?.let { (it as? JsonPrimitive)?.longOrNull
                            ?.takeIf { n -> n in 0..9007199254740991L } ?: throw ProtocolException("Invalid sourceEventSeq") }
                        CommandResult.Success(result.optionalString("text"), seq)
                    }
                    "error" -> CommandResult.Error(result.string("text").also { require(it.isNotBlank()) })
                    else -> throw ProtocolException("Invalid command result kind")
                }
                QueryResult.Command(CommandExecution(execution.string("commandId"), r))
            }
            else -> throw ProtocolException("Invalid query kind")
        }
    }
}
internal inline fun <T> boundary(description: String, block: () -> T): T = try { block() }
    catch (e: ProtocolException) { throw e }
    catch (e: IllegalArgumentException) { throw ProtocolException("Invalid $description", e) }
    catch (e: SerializationException) { throw ProtocolException("Invalid $description", e) }
internal fun JsonElement.objectValue() = this as? JsonObject ?: throw ProtocolException("Expected JSON object")
internal fun JsonObject.objectAt(key: String) = get(key)?.objectValue() ?: throw ProtocolException("Missing object: $key")
internal fun JsonObject.arrayAt(key: String) = get(key) as? JsonArray ?: throw ProtocolException("Missing array: $key")
internal fun JsonObject.string(key: String, allowEmpty: Boolean = false): String {
    val p = get(key) as? JsonPrimitive
    return p?.takeIf { it.isString }?.content?.takeIf { allowEmpty || it.isNotEmpty() } ?: throw ProtocolException("Invalid string: $key")
}
internal fun JsonObject.optionalString(key: String) = if (containsKey(key)) string(key, true) else null

internal fun JsonObject.required(key: String): JsonElement = get(key) ?: throw ProtocolException("Missing field: $key")
