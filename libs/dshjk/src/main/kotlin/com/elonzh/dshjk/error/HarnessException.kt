package com.elonzh.dshjk.error

import kotlinx.serialization.json.JsonElement

open class HarnessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class TransportClosedException(message: String, cause: Throwable? = null) : HarnessException(message, cause)
class RequestTimeoutException(message: String) : HarnessException(message)
class ProtocolException(message: String, cause: Throwable? = null) : HarnessException(message, cause)
class RpcException(val code: Int?, message: String, val data: JsonElement? = null) : HarnessException(message)
class SlowConsumerException : HarnessException("Notification buffer exhausted; consume continuously or increase notificationBufferCapacity")
class CliException(val profile: String, message: String, cause: Throwable? = null) : HarnessException(message, cause)
