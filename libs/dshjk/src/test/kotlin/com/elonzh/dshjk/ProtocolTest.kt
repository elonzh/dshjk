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
            ContentBlock.Text("text"), ContentBlock.Reasoning("reasoning"), ContentBlock.EncodedImage("YQ==", "image/png"),
            ContentBlock.Image(obj("""{"id":"image","path":"/image.png"}""")), ContentBlock.File(obj("""{"id":"file","path":"/file"}""")),
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
            "subagent.finished" to """{"provider":"local","agentId":"c","parentSessionId":"s","childSessionId":"c","status":"ok","stopReason":{"kind":"completed"},"lastAssistantMessage":[]}""",
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
}
