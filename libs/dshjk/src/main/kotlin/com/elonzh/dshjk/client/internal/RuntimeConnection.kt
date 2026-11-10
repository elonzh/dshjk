package com.elonzh.dshjk.client.internal

import com.elonzh.dshjk.client.NotificationSubscription
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.error.*
import com.elonzh.dshjk.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedWriter
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class RuntimeConnection(private val config: HarnessConfig) {
    private val stateLock = Any()
    private val writeLock = Any()
    private val closeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<JsonElement, CompletableDeferred<JsonElement>>()
    private val subscriptions = ConcurrentHashMap.newKeySet<NotificationSubscription>()
    private val parents = ConcurrentHashMap<String, String>()
    private data class Callback(val token: CancellationToken, val job: Job, val questionId: String?)
    private val callbacks = ConcurrentHashMap<JsonElement, Callback>()
    @Volatile private var failure: Throwable? = null
    @Volatile private var closing = false
    @Volatile private var disposed = false
    @Volatile private var process: Process? = null
    private var patchFiles: RuntimePatchFiles? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null
    private var stderrThread: Thread? = null
    private var stderrTail = ""
    val pid: Long? get() = process?.pid()
    val isAlive: Boolean get() = process?.isAlive == true
    fun stderr(): String = synchronized(stateLock) { stderrTail }
    private fun diagnostic(reason: String, cause: Throwable? = null) = TransportClosedException(
        "dsh profile '${config.profile}': $reason; exit=${process?.let { if (it.isAlive) "running" else it.exitValue() }}\n${stderr()}", cause)

    fun start() = synchronized(stateLock) {
        checkUsable()
        if (process != null) return@synchronized
        val child = try {
            val files = RuntimePatchFiles(config).also { patchFiles = it }
            val argv = listOf(config.runtime.executable) + config.runtime.arguments + listOf("--profile", config.profile) +
                files.paths.flatMap { listOf("--patch", it.toString()) }
            val builder = ProcessBuilder(argv).directory(config.processCwd.toAbsolutePath().normalize().toFile())
            if (!config.inheritEnvironment) builder.environment().clear()
            builder.environment().putAll(config.environment)
            config.dshHome?.let { builder.environment()["DSH_HOME"] = it.toAbsolutePath().normalize().toString() }
            builder.start()
        } catch (error: Exception) {
            try { patchFiles?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            val failure = diagnostic("Cannot start ${config.runtime.executable}", error)
            fail(failure)
            throw failure
        }
        process = child
        writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
        stderrThread = thread(name = "dshjk-stderr-${child.pid()}", isDaemon = true) {
            try {
                child.errorStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(stateLock) { stderrTail = (stderrTail + String(buffer, 0, count)).takeLast(65536) }
                    }
                }
            } catch (_: java.io.IOException) { /* Process teardown can close its pipe. */ }
        }
        readerThread = thread(name = "dshjk-reader-${child.pid()}", isDaemon = true) {
            try {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) receive(reader.readLine() ?: break)
                }
                child.waitFor(100, TimeUnit.MILLISECONDS)
                stderrThread?.join(100)
                fail(diagnostic("Runtime stdout closed"))
            } catch (error: Exception) { fail(if (error is HarnessException) error else diagnostic("Runtime reader failed", error)) }
        }
    }
    private fun checkUsable(allowClosing: Boolean = false) {
        failure?.let { throw it }
        if (disposed || (closing && !allowClosing)) throw diagnostic("Client closed")
    }
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeout: Duration? = config.requestTimeout,
        allowClosing: Boolean = false): JsonElement {
        if (!allowClosing) start()
        val id = JsonPrimitive("req_${UUID.randomUUID()}")
        val waiter = CompletableDeferred<JsonElement>()
        synchronized(stateLock) { checkUsable(allowClosing); pending[id] = waiter }
        try {
            withContext(Dispatchers.IO) {
                write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            }
            return if (timeout == null) waiter.await() else {
                // A nullable wrapper distinguishes JSON null from timeout.
                withTimeoutOrNull(timeout.toMillis()) { listOf(waiter.await()) }?.single()
                    ?: throw RequestTimeoutException("$method timed out waiting for dsh profile '${config.profile}' after $timeout\n${stderr()}")
            }
        } finally { pending.remove(id); waiter.cancel() }
    }
    private fun write(frame: JsonObject) = synchronized(writeLock) {
        failure?.let { throw it }
        try {
            val output = writer ?: throw diagnostic("Runtime not started")
            output.write(frame.toString()); output.newLine(); output.flush()
        } catch (error: java.io.IOException) {
            val e = diagnostic("Runtime write failed", error); fail(e); throw e
        }
    }
    private fun receive(line: String) {
        val frame = try { HarnessProtocol.json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null } ?: return
        val id = frame["id"]?.takeIf { it is JsonPrimitive && (it.isString || it.doubleOrNull != null) }
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id != null && method != null) {
            if (!closing) handleRequest(id, method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))
        } else if (id != null) {
            val waiter = pending.remove(id) ?: return
            val error = frame["error"] as? JsonObject
            if (error != null) waiter.completeExceptionally(RpcException((error["code"] as? JsonPrimitive)?.intOrNull,
                (error["message"] as? JsonPrimitive)?.content ?: "JSON-RPC error", error["data"]))
            else waiter.complete(frame["result"] ?: JsonNull)
        } else if (method != null) {
            val n = HarnessProtocol.notification(method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))
            if (n is HarnessNotification.SubagentStarted && n.childSessionId != n.parentSessionId) parents[n.childSessionId] = n.parentSessionId
            if (n is HarnessNotification.QuestionCancelled) callbacks.entries.toList().forEach { (rpcId, callback) ->
                if (callback.questionId == n.requestId && callbacks.remove(rpcId, callback)) {
                    callback.token.cancel(); callback.job.cancel()
                }
            }
            subscriptions.toList().forEach { it.push(n) }
        }
    }
    private fun handleRequest(id: JsonElement, method: String, params: JsonObject) {
        val token = CancellationToken()
        val questionId = if (method == SdkHostMethod.USER_QUESTION_ASK.wireName) params.optionalString("requestId") else null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result: JsonElement = when {
                    method == SdkHostMethod.APPROVAL_REQUEST.wireName && config.approvalHandler != null -> {
                        val request = boundary(method) { HarnessProtocol.json.decodeFromJsonElement<ApprovalRequest>(params) }
                        val answer = runInterruptible { config.approvalHandler.handle(request, token) }
                        HarnessProtocol.json.encodeToJsonElement(answer)
                    }
                    method == SdkHostMethod.USER_QUESTION_ASK.wireName && config.userQuestionHandler != null -> {
                        val request = boundary(method) { HarnessProtocol.json.decodeFromJsonElement<UserQuestionRequest>(params) }
                        require(request.questions.isNotEmpty() && request.requestId.isNotEmpty())
                        when (val answer = runInterruptible { config.userQuestionHandler.handle(request, token) }) {
                            is UserQuestionResult.Answer -> {
                                val ids = answer.answers.map { it.id }
                                require(ids.size == ids.toSet().size && ids.toSet() == request.questions.map { it.id }.toSet()) { "Answer each question exactly once" }
                                buildJsonObject { put("answers", HarnessProtocol.json.encodeToJsonElement(answer.answers)) }
                            }
                            UserQuestionResult.Cancelled -> buildJsonObject { put("cancelled", true) }
                        }
                    }
                    else -> {
                        sendError(id, -32601, "method not found: $method")
                        return@launch
                    }
                }
                if (!token.isCancelled) write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            } catch (_: CancellationException) { /* Runtime cancelled the question or client closed. */ }
            catch (error: Exception) { if (!token.isCancelled) runCatching { sendError(id, -32603, error.message ?: "Host callback failed") } }
            finally { callbacks.remove(id) }
        }
        callbacks[id] = Callback(token, job, questionId)
        if (closing || failure != null) { token.cancel(); callbacks.remove(id); job.cancel() } else job.start()
    }
    private fun sendError(id: JsonElement, code: Int, message: String) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) }
    })
    fun subscribe(root: String? = null): NotificationSubscription {
        val sub = NotificationSubscription(config.notificationBufferCapacity, { n -> root == null || belongs(n, root) }, { subscriptions.remove(it) })
        synchronized(stateLock) {
            val e = failure ?: if (closing || disposed) diagnostic("Client closed") else null
            if (e != null) sub.fail(e) else subscriptions.add(sub)
        }
        return sub
    }
    private fun descendant(id: String, root: String): Boolean {
        var current: String? = id
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (current == root) return true
            current = parents[current]
        }
        return false
    }
    private fun belongs(n: HarnessNotification, root: String): Boolean = when(n) {
        is HarnessNotification.SubagentStarted -> descendant(n.parentSessionId, root) || n.childSessionId == root
        is HarnessNotification.SubagentFinished -> descendant(n.parentSessionId, root) || n.childSessionId == root
        else -> (n.params["sessionId"] as? JsonPrimitive)?.content?.let { descendant(it, root) } ?: false
    }
    private fun fail(error: Throwable) {
        synchronized(stateLock) {
            if (failure == null) failure = error
            pending.values.forEach { it.completeExceptionally(failure!!) }; pending.clear()
            subscriptions.toList().forEach { it.fail(failure!!) }
            callbacks.values.forEach { it.token.cancel(); it.job.cancel() }; callbacks.clear()
        }
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        closeMutex.withLock {
            if (disposed) return@withLock
            synchronized(stateLock) { closing = true }
            callbacks.values.forEach { it.token.cancel(); it.job.cancel() }
            val child = process
            if (child != null) {
                runCatching { request(SdkRequestMethod.SHUTDOWN.wireName, timeout = config.shutdownTimeout, allowClosing = true) }
                runCatching { child.outputStream.close() }
                if (!child.waitFor(config.eofGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                    child.destroy()
                    if (!child.waitFor(config.terminateGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                        child.destroyForcibly()
                        if (!child.waitFor(config.terminateGrace.toMillis(), TimeUnit.MILLISECONDS)) throw diagnostic("Runtime termination could not be confirmed")
                    }
                }
                child.inputStream.close(); child.errorStream.close()
                readerThread?.join(500); stderrThread?.join(500)
            }
            patchFiles?.close()
            disposed = true
            fail(diagnostic("Client closed"))
            parents.clear()
            scope.cancel()
        }
    }
}
