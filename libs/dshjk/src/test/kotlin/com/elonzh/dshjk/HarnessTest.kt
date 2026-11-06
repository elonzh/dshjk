package com.elonzh.dshjk

import com.elonzh.dshjk.client.*
import com.elonzh.dshjk.configuration.*
import com.elonzh.dshjk.error.*
import com.elonzh.dshjk.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@Timeout(20)
class HarnessTest {
    @TempDir lateinit var root: Path
    private fun config(mode: String = "normal", capacity: Int = 8192, approval: ApprovalHandler? = null, question: UserQuestionHandler? = null) = HarnessConfig(
        dshHome = root.resolve("home"), cwd = root,
        runtime = RuntimeCommand.node(Path.of(javaClass.getResource("/runtime.mjs")!!.toURI())),
        environment = mapOf("FIXTURE_MODE" to mode), requestTimeout = Duration.ofSeconds(2),
        initializeTimeout = Duration.ofMillis(if (mode == "hang") 200 else 2000), eofGrace = Duration.ofMillis(100), terminateGrace = Duration.ofMillis(100),
        notificationBufferCapacity = capacity, approvalHandler = approval, userQuestionHandler = question,
    )
    @Test fun `default entry points need no configuration or runtime launch`(): Unit = runBlocking {
        assertNull(HarnessConfig().dshHome)
        assertNull(HarnessConfig.builder().build().dshHome)
        assertEquals(root, HarnessConfig.builder().dshHome(root).build().dshHome)
        DeepSeekHarness().use { h ->
            assertNull(h.client.pid)
            assertTrue(h.session().id.matches(Regex("session-[0-9a-f]{32}")))
            assertEquals("custom-id", h.session("custom-id").id)
            assertNull(h.client.pid)
        }
    }
    @Test fun `implicit runs use fresh official SDK ids and a session handle keeps its identity`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            val session = h.session()
            assertEquals(session.id, session.run("first").sessionId)
            assertEquals(session.id, session.run("second").sessionId)
            val first = h.run("fresh one")
            val second = h.run("fresh two")
            assertTrue(first.sessionId.matches(Regex("session-[0-9a-f]{32}")))
            assertTrue(second.sessionId.matches(Regex("session-[0-9a-f]{32}")))
            assertNotEquals(first.sessionId, second.sessionId)
            assertNotEquals(session.id, first.sessionId)
        }
    }
    @Test fun `prompt and native commands collect correlated activity without child text replacing root`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            val r = h.run("你好 😀", "one")
            assertEquals("你好 😀", r.finalResponse)
            assertEquals(FinishReason.Builtin.COMPLETED, r.finishReason)
            assertTrue(r.notifications.any { it is HarnessNotification.SubagentStarted })
            assertEquals(1, r.events.count { it.type == SessionEventType.Builtin.ASSISTANT_MESSAGE })
            assertEquals("command complete", h.run("/compact", "one").finalResponse)
            assertEquals("command complete", h.run("/early", "one").finalResponse)
            assertFailsWith<ProtocolException> { h.run("/missing", "one") }
        }
    }
    @Test fun `independent sessions and overlapping runs fan out notifications`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            val results = listOf(async { h.run("a", "a") }, async { h.run("b", "b") }).awaitAll()
            assertEquals(listOf("a", "b"), results.map { it.finalResponse })
            val a = h.client.subscribe("a")
            val b = h.client.subscribe("a")
            h.run("again", "a")
            assertEquals(a.next().method, b.next().method)
            a.close(); b.close()
        }
    }
    @Test fun `RPC errors retain data and malformed native results fail at boundary`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            h.start()
            val e = assertFailsWith<RpcException> { h.client.request("error") }
            assertEquals(42, e.code); assertEquals("retained", e.data!!.jsonObject["detail"]!!.jsonPrimitive.content)
            assertFailsWith<ProtocolException> { h.run("malformed") }
        }
    }
    @Test fun `request timeout and coroutine cancellation leave client usable`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            h.start()
            assertFailsWith<RequestTimeoutException> { h.client.request("hang", timeout = Duration.ofMillis(30)) }
            val request = launch { h.client.request("hang", timeout = null) }
            delay(30); request.cancelAndJoin()
            assertEquals(root.toRealPath().toString(), h.session("ok").getWorkingDirectory())
        }
    }
    @Test fun `observer failure releases its subscription without rolling back runtime`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            assertFailsWith<IllegalStateException> { h.run("a", observer = NotificationObserver { error("observer") }) }
            assertEquals("b", h.run("b").finalResponse)
        }
    }
    @Test fun `approval callback can make a nested request while run waits`(): Unit = runBlocking {
        lateinit var h: DeepSeekHarness
        h = DeepSeekHarness(config(approval = ApprovalHandler { _, _ ->
            assertEquals("/nested", h.client.getWorkingDirectoryBlocking("nested"))
            ApprovalResult(ApprovalOutcome.REJECTED)
        }))
        h.use { assertEquals("rejected", h.run("approval").finalResponse) }
    }
    @Test fun `questions are answered and runtime cancellation reaches callback`(): Unit = runBlocking {
        val cancelled = AtomicBoolean()
        val entered = CountDownLatch(1)
        val left = CountDownLatch(1)
        DeepSeekHarness(config(question = UserQuestionHandler { q, token ->
            if (q.sessionId == "cancel") {
                entered.countDown()
                try { while (!token.isCancelled) Thread.sleep(5) } catch (_: InterruptedException) { }
                cancelled.set(token.isCancelled); left.countDown()
                UserQuestionResult.Cancelled
            } else UserQuestionResult.Answer(listOf(AnswerItem("q", listOf("yes"))))
        })).use { h ->
            assertEquals("yes", h.run("question", "answer").finalResponse)
            assertEquals("cancelled", h.run("cancel-question", "cancel").finalResponse)
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(left.await(1, TimeUnit.SECONDS)); assertTrue(cancelled.get())
        }
    }
    @Test fun `slow subscriber fails independently while transport continues`(): Unit = runBlocking {
        DeepSeekHarness(config(capacity = 2)).use { h ->
            h.start()
            val sub = h.client.subscribe()
            h.client.request("flood")
            sub.next(); sub.next()
            assertFailsWith<SlowConsumerException> { sub.next() }
            assertEquals(root.toRealPath().toString(), h.session().getWorkingDirectory())
        }
    }
    @Test fun `malformed consumed events and process death fail pending and future operations`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h -> assertFailsWith<ProtocolException> { h.run("bad-event") } }
        DeepSeekHarness(config()).use { h ->
            h.start(); val sub = h.client.subscribe()
            assertFailsWith<TransportClosedException> { h.client.request("crash") }
            assertFailsWith<TransportClosedException> { sub.next() }
            assertFailsWith<TransportClosedException> { h.client.request("later") }
        }
    }
    @Test fun `failed handshake and forced shutdown reap children and close is terminal`(): Unit = runBlocking {
        val h = DeepSeekHarness(config("hang"))
        assertFailsWith<RequestTimeoutException> { h.start() }
        assertFalse(h.client.isAlive); h.closeAsync()
        val stubborn = DeepSeekHarness(config("stubborn"))
        stubborn.start(); val pid = stubborn.client.pid!!
        stubborn.closeAsync(); stubborn.closeAsync()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFailsWith<IllegalStateException> { stubborn.start() }
    }
    @Test fun `close interrupts initialization without waiting for its timeout`(): Unit = runBlocking {
        val h = DeepSeekHarness(config("hang"))
        withTimeout(3000) {
            val init = async { runCatching { h.start() } }
            while (h.client.pid == null) delay(5)
            h.closeAsync()
            assertTrue(init.await().isFailure)
        }
        assertFalse(h.client.isAlive)
    }
    @Test fun `unconsumed subscription survives local timeout`(): Unit = runBlocking {
        DeepSeekHarness(config()).use { h ->
            h.start(); val sub = h.client.subscribe("x")
            assertFailsWith<RequestTimeoutException> { sub.next(Duration.ofMillis(20)) }
            h.run("x", "x"); assertNotNull(sub.next()); sub.close()
        }
    }
}
