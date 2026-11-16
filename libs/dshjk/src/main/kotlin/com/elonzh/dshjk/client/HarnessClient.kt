package com.elonzh.dshjk.client

import com.elonzh.dshjk.client.internal.RuntimeConnection
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.error.ProtocolException
import com.elonzh.dshjk.protocol.*
import com.elonzh.dshjk.runtime.internal.DshRuntime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Duration

/** Low-level native SDK operations. Initialize once before creating or restoring sessions. */
class HarnessClient internal constructor(val config: HarnessConfig, runtime: DshRuntime) : AutoCloseable {
    @JvmOverloads constructor(config: HarnessConfig = HarnessConfig()) : this(config, DshRuntime(config))

    private val connection = RuntimeConnection(config, runtime)
    val pid: Long? get() = connection.pid
    val isAlive: Boolean get() = connection.isAlive
    fun stderr(): String = connection.stderr()
    fun start() = connection.start()
    @JvmOverloads fun subscribe(sessionTreeRoot: String? = null): NotificationSubscription = connection.subscribe(sessionTreeRoot)
    @JvmSynthetic suspend fun initialize(params: InitializeParams): InitializeResult {
        val value = request(SdkRequestMethod.INITIALIZE, HarnessProtocol.json.encodeToJsonElement(params).jsonObject, config.initializeTimeout)
        val result = boundary("initialize result") { HarnessProtocol.json.decodeFromJsonElement<InitializeResult>(value) }
        if (result.serverInfo.name != "deepseek-harness-sdk-runtime" || result.serverInfo.version.isBlank())
            throw ProtocolException("Unexpected SDK server identity: ${result.serverInfo}")
        return result
    }
    @JvmSynthetic suspend fun prompt(sessionId: String, blocks: List<ContentBlock>): PromptReceipt =
        PromptReceipt(request(SdkRequestMethod.SESSION_PROMPT, input(sessionId, blocks)).objectValue().string("messageId"))
    @JvmSynthetic suspend fun query(sessionId: String, blocks: List<ContentBlock>): QueryResult =
        HarnessProtocol.query(request(SdkRequestMethod.SESSION_QUERY, input(sessionId, blocks)))
    @JvmSynthetic suspend fun resumeSession(sessionId: String) { request(SdkRequestMethod.SESSION_RESUME, session(sessionId)).objectValue() }
    @JvmSynthetic suspend fun waitForSession(sessionId: String) { request(SdkRequestMethod.SESSION_WAIT, session(sessionId)).objectValue() }
    @JvmSynthetic suspend fun getWorkingDirectory(sessionId: String): String =
        request(SdkRequestMethod.SESSION_WORKING_DIRECTORY_GET, session(sessionId)).objectValue().string("cwd")
    @JvmSynthetic suspend fun setWorkingDirectory(sessionId: String, path: String): String =
        request(SdkRequestMethod.SESSION_WORKING_DIRECTORY_SET, buildJsonObject { put("sessionId", sessionId); put("path", path) }).objectValue().string("cwd")
    /** Explicit low-level protocol shutdown. close() additionally reaps the process. */
    @JvmSynthetic suspend fun shutdown() { request(SdkRequestMethod.SHUTDOWN).objectValue() }
    /** Escape hatch for plugin-added RPC methods; errors preserve wire code and data. */
    @JvmSynthetic suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeout: Duration? = config.requestTimeout): JsonElement =
        connection.request(method, params, timeout)
    @JvmSynthetic suspend fun request(method: SdkRequestMethod, params: JsonObject = JsonObject(emptyMap()), timeout: Duration? = config.requestTimeout): JsonElement =
        request(method.wireName, params, timeout)
    @JvmName("request") @JvmOverloads fun requestBlocking(method: SdkRequestMethod, params: JsonObject = JsonObject(emptyMap())): JsonElement = runBlocking { request(method, params) }
    @JvmSynthetic suspend fun closeAsync() = connection.close()
    override fun close() = runBlocking { closeAsync() }
    @JvmName("initialize") fun initializeBlocking(params: InitializeParams): InitializeResult = runBlocking { initialize(params) }
    @JvmName("prompt") fun promptBlocking(sessionId: String, blocks: List<ContentBlock>): PromptReceipt = runBlocking { prompt(sessionId, blocks) }
    @JvmName("query") fun queryBlocking(sessionId: String, blocks: List<ContentBlock>): QueryResult = runBlocking { query(sessionId, blocks) }
    @JvmName("resumeSession") fun resumeSessionBlocking(sessionId: String) = runBlocking { resumeSession(sessionId) }
    @JvmName("waitForSession") fun waitForSessionBlocking(sessionId: String) = runBlocking { waitForSession(sessionId) }
    @JvmName("getWorkingDirectory") fun getWorkingDirectoryBlocking(sessionId: String): String = runBlocking { getWorkingDirectory(sessionId) }
    @JvmName("setWorkingDirectory") fun setWorkingDirectoryBlocking(sessionId: String, path: String): String = runBlocking { setWorkingDirectory(sessionId, path) }
    @JvmName("shutdown") fun shutdownBlocking() = runBlocking { shutdown() }
    @JvmName("request") @JvmOverloads fun requestBlocking(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement = runBlocking { request(method, params) }
    private fun session(id: String) = buildJsonObject { require(id.isNotBlank()); put("sessionId", id) }
    private fun input(id: String, blocks: List<ContentBlock>) = buildJsonObject {
        require(id.isNotBlank()); put("sessionId", id); put("contentBlocks", JsonArray(blocks.map { it.toJson() }))
    }
}
