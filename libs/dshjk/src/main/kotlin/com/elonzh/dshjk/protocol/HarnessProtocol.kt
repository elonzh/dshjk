package com.elonzh.dshjk.protocol

import com.elonzh.dshjk.error.ProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

object HarnessProtocol {
    @JvmField val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    @JvmStatic fun text(input: String): List<ContentBlock> = listOf(ContentBlock.Text(input))
    @JvmStatic fun content(value: JsonObject): ContentBlock = boundary("content block") {
        when (value.string("type")) {
            "text" -> ContentBlock.Text(value.string("text", allowEmpty = true))
            "reasoning" -> ContentBlock.Reasoning(value.string("text", allowEmpty = true))
            "image" -> if (value.containsKey("data")) ContentBlock.EncodedImage(value.string("data"), value.string("mimeType"), value.optionalString("name"))
                else ContentBlock.Image(value.objectAt("attachment"), value["offloaded"] == JsonPrimitive(true))
            "file" -> ContentBlock.File(value.objectAt("attachment"))
            "tool-call" -> ContentBlock.ToolCall(value.string("id"), value.string("name"), value.string("arguments", true))
            "tool-addition" -> ContentBlock.ToolAddition(value.string("toolName"))
            "tool-removal" -> ContentBlock.ToolRemoval(value.string("toolName"))
            else -> ContentBlock.Extension(value)
        }
    }
    @JvmStatic fun event(value: JsonObject): SessionEvent = boundary("session event") {
        val e = SessionEvent(value.string("type"), value["data"] ?: throw ProtocolException("Missing event data"), value)
        // Validate the fields the run collector consumes before exposing them to observers.
        when (e.type) {
            "assistant/message" -> e.assistantMessage
            "turn/end" -> {
                val end = e.turnEnd!!
                if (end.kind == "aborted") {
                    val reason = end.reason.objectAt("reason")
                    when (reason.string("kind")) {
                        "user", "parent", "disposed", "legacy" -> Unit
                        "hook" -> reason.string("reason", true)
                        else -> throw ProtocolException("Unknown abort reason")
                    }
                }
            }
            "command/run" -> e.commandRun
            "command/done" -> e.commandDone
            "agent/inbox/spliced" -> e.inboxInsertedIds
        }
        e
    }
    @JvmStatic fun notification(method: String, p: JsonObject): HarnessNotification = boundary(method) {
        when (method) {
            "session.event" -> HarnessNotification.Event(p.string("sessionId"), event(p.objectAt("event")), p)
            "session.status" -> HarnessNotification.Status(p.string("sessionId"), when(p.string("status")) {
                "idle" -> AgentStatus.IDLE; "running" -> AgentStatus.RUNNING; else -> throw ProtocolException("Invalid agent status")
            }, p)
            "session.query.completed" -> HarnessNotification.QueryCompleted(p.string("sessionId"), p.string("commandId"), p.string("finalResponse", true), p.string("finishReason"), p)
            "subagent.started" -> HarnessNotification.SubagentStarted(p.string("parentSessionId"), p.string("childSessionId"), p)
            "subagent.finished" -> HarnessNotification.SubagentFinished(p.string("provider"), p.string("agentId"), p.string("parentSessionId"), p.string("childSessionId"),
                when(p.string("status")) { "ok" -> RunStatus.OK; "error" -> RunStatus.ERROR; else -> throw ProtocolException("Invalid run status") },
                p["stopReason"] ?: throw ProtocolException("Missing stopReason"), (p["lastAssistantMessage"] as? JsonArray)?.map { content(it.objectValue()) }, p)
            "user-question.cancelled" -> HarnessNotification.QuestionCancelled(p.string("requestId"), p.string("sessionId"), p)
            else -> HarnessNotification.Extension(method, p)
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
