package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/v1/chat")
class ChatController(
    private val chatService: ChatService,
    private val chatCopilotOrchestratorService: ChatCopilotOrchestratorService,
) {
    @PostMapping("/recommend")
    fun recommend(@Valid @RequestBody request: ChatRecommendRequest) = ApiResponses.ok(
        chatService.recommend(request),
    )

    @PostMapping("/recommend:stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun recommendStream(@Valid @RequestBody request: ChatRecommendRequest): SseEmitter {
        val emitter = SseEmitter(0L)
        val cancelled = AtomicBoolean(false)

        emitter.onCompletion { cancelled.set(true) }
        emitter.onTimeout {
            cancelled.set(true)
            emitter.complete()
        }

        thread(start = true, isDaemon = true, name = "chat-stream-${System.nanoTime()}") {
            try {
                val result = chatService.recommendStream(
                    request = request,
                    onMeta = { payload -> sendEvent(emitter, cancelled, "meta", payload) },
                    onToken = { token -> sendEvent(emitter, cancelled, "token", mapOf("text" to token)) },
                    isCancelled = { cancelled.get() },
                )
                if (!cancelled.get()) {
                    sendEvent(emitter, cancelled, "done", result)
                }
                emitter.complete()
            } catch (_: CancellationException) {
                emitter.complete()
            } catch (ex: Exception) {
                if (!cancelled.get()) {
                    runCatching {
                        sendEvent(
                            emitter,
                            cancelled,
                            "error",
                            mapOf("code" to "STREAM_ERROR", "message" to (ex.message ?: "stream failed")),
                        )
                    }
                    emitter.completeWithError(ex)
                }
            }
        }

        return emitter
    }

    @PostMapping("/copilot/orchestrate")
    fun orchestrate(@Valid @RequestBody request: ChatCopilotOrchestrateRequest) = ApiResponses.ok(
        chatCopilotOrchestratorService.orchestrate(request),
    )

    private fun sendEvent(
        emitter: SseEmitter,
        cancelled: AtomicBoolean,
        event: String,
        payload: Any,
    ) {
        if (cancelled.get()) {
            throw CancellationException("stream already cancelled")
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload))
        } catch (_: Exception) {
            cancelled.set(true)
            throw CancellationException("failed to write sse event")
        }
    }
}
