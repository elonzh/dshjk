package com.elonzh.dshjk

import com.elonzh.dshjk.protocol.*
import com.elonzh.dshjk.error.ProtocolException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProtocolTest {
    private fun obj(s: String) = HarnessProtocol.json.parseToJsonElement(s).jsonObject
    @Test fun `known content blocks have typed values and extensions retain original JSON`() {
        val values = listOf(
            ContentBlock.Text("text"), ContentBlock.Reasoning("reasoning"), ContentBlock.EncodedImage("YQ==", ImageMediaType.IMAGE_PNG),
            ContentBlock.Image(ImageAttachment("image", ImageMediaType.IMAGE_PNG, 10, 1, 1)), ContentBlock.File(FileAttachment("file", "file.txt", 10)),
            ContentBlock.ToolCall("id", "tool", "{}"), ContentBlock.ToolAddition("tool"), ContentBlock.ToolRemoval("tool"),
            ContentBlock.Extension(obj("""{"type":"plugin-block","nested":{"values":[null,1,true]}}""")),
        )
        values.forEach { assertEquals(it, HarnessProtocol.content(it.toJson())) }
    }
    @Test fun `SDK notifications decode their named variants without discarding params`() {
        val values = mapOf(
            "session.status" to """{"sessionId":"s","status":"running"}""",
            "session.event" to """{"sessionId":"s","event":{"type":"plugin/log","seq":1,"time":2,"data":{"extra":true},"ignorable":true}}""",
            "session.query.completed" to """{"sessionId":"s","commandId":"c","finalResponse":"","finishReason":"completed"}""",
            "subagent.started" to """{"parentSessionId":"s","childSessionId":"c"}""",
            "subagent.finished" to """{"provider":"local","agentId":"c","parentSessionId":"s","childSessionId":"c","status":"ok","stopReason":"completed","lastAssistantMessage":[]}""",
            "user-question.cancelled" to """{"sessionId":"s","requestId":"r"}""",
        )
        values.forEach { (method, value) ->
            val params = obj(value)
            val n = HarnessProtocol.notification(method, params)
            assertEquals(params, n.params); assertFalse(n is HarnessNotification.Extension)
        }
        val e = HarnessProtocol.event(obj("""{"type":"plugin/log","seq":1,"time":2,"data":{"extra":true},"ignorable":true}"""))
        assertEquals(true, e.raw["ignorable"]!!.jsonPrimitive.boolean)
        assertEquals(1L, e.seq)
        val scalar = obj("""{"type":"plugin/scalar","seq":2,"time":2,"data":[1,null,true]}""")
        assertEquals(scalar, HarnessProtocol.event(scalar).raw)
    }
    @Test fun `command results reject null invalid sequence and blank errors`() {
        listOf("null", "-1", "1.5", "9007199254740992").forEach { seq ->
            assertFailsWith<ProtocolException> {
                HarnessProtocol.query(obj("""{"kind":"command","execution":{"commandId":"c","result":{"kind":"success","sourceEventSeq":$seq}}}"""))
            }
        }
        assertFailsWith<ProtocolException> { HarnessProtocol.query(obj("""{"kind":"command","execution":{"commandId":"c","result":{"kind":"error","text":" "}}}""")) }
    }
    @Test fun `the pinned native catalog has a concrete decoder for every builtin`() {
        val names = HarnessProtocol.json.decodeFromString<List<String>>(javaClass.getResource("/builtin-session-event-types-0.2.2-rc.1.json")!!.readText())
        assertEquals(names.toSet(), SessionEventType.Builtin.entries.map { it.wireName }.toSet())
        assertEquals(names.size, SessionEventType.Builtin.entries.size)
        SessionEventType.Builtin.entries.forEach {
            assertEquals(it, SessionEventType.fromWire(it.wireName))
        }
    }
    @Test fun `builtin payloads expose typed lifecycle content and states`() {
        fun event(type: String, data: String) = HarnessProtocol.event(obj("""{"type":"$type","seq":3,"time":42,"data":$data}"""))
        val started = event("turn/start", """{"turn":7}""")
        assertEquals(SessionEventType.Builtin.TURN_START, started.type)
        assertEquals(7L, assertIs<SessionEventData.TurnStarted>(started.data).turn)
        val todo = event("todo/write", """{"todos":[{"content":"Review","status":"in_progress"}]}""")
        assertEquals(TodoStatus.IN_PROGRESS, assertIs<SessionEventData.TodosWritten>(todo.data).todos.single().status)
        val call = event("tool/call", """{"turn":1,"step":2,"callId":"c","name":"read","arguments":"{}"}""")
        assertEquals("read", assertIs<SessionEventData.ToolCalled>(call.data).name)
        val ended = event("turn/end", """{"turn":7,"reason":{"kind":"aborted","reason":{"kind":"hook","reason":"stop"}}}""")
        val reason = assertIs<TurnEndReason.Aborted>(assertIs<SessionEventData.TurnEnded>(ended.data).reason)
        assertEquals("stop", assertIs<AbortReason.Hook>(reason.reason).reason)
        val failed = event("turn/end", """{"turn":7,"reason":{"kind":"error","error":{"message":"bad route","code":"ROUTE"}}}""")
        assertEquals("ROUTE", assertIs<TurnEndReason.Error>(assertIs<SessionEventData.TurnEnded>(failed.data).reason).error.code)
    }
    @Test fun `known malformed events and closed states cannot silently become extensions`() {
        listOf(
            """{"type":"turn/start","seq":0,"time":0,"data":{"turn":"one"}}""",
            """{"type":"step/start","seq":0,"time":0,"data":{"turn":1}}""",
            """{"type":"command/done","seq":0,"time":0,"data":{"commandId":"c","kind":"ok"}}""",
            """{"type":"todo/write","seq":0,"time":0,"data":{"todos":[{"content":"x","status":"done"}]}}""",
            """{"type":"turn/end","seq":0,"time":0,"data":{"turn":1,"reason":{"kind":"error"}}}""",
        ).forEach { assertFailsWith<ProtocolException>(it) { HarnessProtocol.event(obj(it)) } }
        assertFailsWith<ProtocolException> { HarnessProtocol.notification("session.status", obj("""{"sessionId":"s","status":"busy"}""")) }
        assertFailsWith<ProtocolException> { HarnessProtocol.notification("session.status", obj("""{"sessionId":"s"}""")) }
        assertFailsWith<IllegalArgumentException> { FinishReason.Extension("completed") }
    }
    @Test fun `open protocol values preserve custom names and nested details`() {
        val raw = obj("""{"type":"plugin/custom","seq":0,"time":42,"data":[1,null,true],"extra":"keep"}""")
        val event = HarnessProtocol.event(raw)
        assertEquals("plugin/custom", assertIs<SessionEventType.Extension>(event.type).wireName)
        assertEquals(raw["data"], assertIs<SessionEventData.Extension>(event.data).value)
        assertEquals(raw, event.raw)
        val notification = HarnessProtocol.notification("session.query.completed", obj("""{"sessionId":"s","commandId":"c","finalResponse":"","finishReason":"plugin-stop"}"""))
        assertEquals("plugin-stop", assertIs<FinishReason.Extension>(assertIs<HarnessNotification.QueryCompleted>(notification).finishReason).wireName)
        val reasonRaw = obj("""{"kind":"plugin-stop","detail":{"why":"custom"}}""")
        val reason = HarnessProtocol.json.decodeFromJsonElement<TurnEndReason>(reasonRaw)
        assertEquals(reasonRaw, assertIs<TurnEndReason.Extension>(reason).raw)
        assertEquals(reasonRaw, HarnessProtocol.json.encodeToJsonElement<TurnEndReason>(reason))
        assertEquals(NotificationMethod.Builtin.SESSION_QUERY_COMPLETED, notification.method)
    }
    @Test fun `typed stream records and native question intent keep their discriminators`() {
        val raw = obj("""{"type":"assistant/message","seq":1,"time":42,"data":{"turn":1,"step":1,"message":{"id":"a","role":"assistant","source":{"kind":"model","provider":"test"},"content":[{"type":"reasoning","text":"think"},{"type":"text","text":"hi"}]},"stream":[{"type":"text-chunks","time0":42,"index":0,"dt":[0],"texts":["hi"]}],"usage":{"inputTokens":3,"outputTokens":1}}}""")
        val data = assertIs<SessionEventData.AssistantMessage>(HarnessProtocol.event(raw).data)
        assertEquals(MessageRole.ASSISTANT, data.message.role)
        assertEquals(ContentType.Builtin.REASONING, data.message.content.first().type)
        assertEquals("hi", assertIs<AssistantStreamRecord.TextChunks>(data.stream.single()).texts.single())
        val q = HarnessProtocol.json.decodeFromJsonElement<UserQuestion>(obj("""{"id":"q","question":"Review?","intent":{"kind":"plan-review","approve":"Yes","callId":"plan-call"}}"""))
        assertEquals("Yes", assertIs<QuestionIntent.PlanReview>(q.intent).approve)
        val chunk = HarnessProtocol.json.decodeFromJsonElement<StreamChunk>(obj("""{"type":"finish","reason":{"kind":"stop"}}"""))
        assertEquals(ModelFinishReason.Stop, assertIs<StreamChunk.Finish>(chunk).reason)
    }
    @Test fun `native retry jitter retains fractional millisecond delays`() {
        val e = HarnessProtocol.event(obj("""{"type":"llm/retry","seq":3,"time":42,"data":{"retryId":"r","turn":1,"step":1,"provider":"test","mode":"normal","policyKey":"policy","retry":1,"maxRetries":3,"delayMs":123.75,"failure":{"message":"wait","code":"RATE_LIMIT","providerRetryAfterMs":45.5}}}"""))
        val retry = assertIs<SessionEventData.LlmRetry>(e.data)
        assertEquals(RetryMode.NORMAL, retry.mode)
        assertEquals(123.75, retry.delayMs)
        assertEquals(45.5, retry.failure.providerRetryAfterMs)
    }
}
