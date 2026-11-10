package example.spring

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.protocol.NotificationObserver
import com.elonzh.dshjk.protocol.RunResult
import kotlinx.coroutines.runBlocking
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class HarnessController(private val harness: DeepSeekHarness, private val executor: AsyncTaskExecutor) {
    data class Input(val text: String) {
        init { require(text.isNotBlank()) { "text must not be blank" } }
    }

    data class Result(val sessionId: String, val text: String, val finishReason: String?)

    @PostMapping("/run")
    fun run(@RequestBody input: Input): Result = runBlocking { result(harness.run(input.text)) }

    @PostMapping("/run/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody input: Input): SseEmitter {
        val emitter = SseEmitter(0L)
        executor.execute {
            try {
                val output = runBlocking {
                    harness.run(input.text, observer = NotificationObserver { notification ->
                        emitter.send(SseEmitter.event().name(notification.method.wireName)
                            .data(notification.params.toString(), MediaType.APPLICATION_JSON))
                    })
                }
                emitter.send(SseEmitter.event().name("result").data(result(output)))
                emitter.complete()
            } catch (error: Exception) {
                emitter.completeWithError(error)
            }
        }
        return emitter
    }

    private fun result(result: RunResult): Result =
        Result(result.sessionId, result.finalResponse, result.finishReason?.wireName)
}
