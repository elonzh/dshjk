package com.elonzh.dshjk.protocol

class CancellationToken internal constructor() {
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    internal fun cancel() { cancelled = true }
}

/** Callbacks run independently of the protocol reader and may make further SDK calls. */
fun interface ApprovalHandler {
    fun handle(request: ApprovalRequest, cancellation: CancellationToken): ApprovalResult
}

/** Callbacks run independently of the protocol reader and may make further SDK calls. */
fun interface UserQuestionHandler {
    fun handle(request: UserQuestionRequest, cancellation: CancellationToken): UserQuestionResult
}
