package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

interface LlmClient {
    fun generate(request: LlmGenerateRequest): LlmGenerateResponse

    fun generateStream(
        request: LlmGenerateRequest,
        onChunk: (String) -> Unit,
        cancelSignal: () -> Boolean = { false },
    ): LlmGenerateResponse
}

data class LlmGenerateRequest(
    val prompt: String,
    val systemPrompt: String,
    val model: String,
    val maxTokens: Int = 600,
    val temperature: Double = 0.2,
)

data class LlmGenerateResponse(
    val text: String,
    val model: String,
    val elapsedMs: Long,
)

class LlmUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LlmSoftTimeoutException(message: String) : RuntimeException(message)

@Component
class LocalLlmClient(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.llm.base-url:http://127.0.0.1:11434}") private val baseUrl: String,
    @Value("\${stayvista.chat.llm.soft-timeout-ms:2500}") private val softTimeoutMs: Long,
    @Value("\${stayvista.chat.llm.hard-timeout-ms:6000}") private val hardTimeoutMs: Long,
    @Value("\${stayvista.chat.llm.streaming-enabled:true}") private val streamingEnabled: Boolean,
) : LlmClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1200))
        .build()

    private val callExecutor = Executors.newCachedThreadPool()

    override fun generate(request: LlmGenerateRequest): LlmGenerateResponse {
        val startedAt = System.nanoTime()
        val task = CompletableFuture.supplyAsync(
            {
                sendGenerateRequest(request)
            },
            callExecutor,
        )

        try {
            val response = task.get(softTimeoutMs, TimeUnit.MILLISECONDS)
            val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            meterRegistry.timer("llm_ms").record(Duration.ofMillis(elapsedMs))
            return response.copy(elapsedMs = elapsedMs)
        } catch (_: TimeoutException) {
            task.cancel(true)
            meterRegistry.counter("llm_errors_total", "reason", "soft_timeout").increment()
            meterRegistry.counter("llm_timeout_count", "type", "soft").increment()
            meterRegistry.counter("llm_error_count", "reason", "soft_timeout").increment()
            throw LlmSoftTimeoutException("LLM soft timeout exceeded ${softTimeoutMs}ms")
        } catch (ex: Exception) {
            task.cancel(true)
            if (ex is CancellationException) {
                meterRegistry.counter("llm_errors_total", "reason", "cancelled").increment()
                meterRegistry.counter("llm_error_count", "reason", "cancelled").increment()
                throw LlmUnavailableException("LLM request cancelled", ex)
            }
            val root = ex.cause ?: ex
            if (root is ConnectException) {
                meterRegistry.counter("llm_errors_total", "reason", "connect").increment()
                meterRegistry.counter("llm_error_count", "reason", "connect").increment()
                throw LlmUnavailableException("LLM endpoint is unavailable", root)
            }
            if (root is HttpTimeoutException) {
                meterRegistry.counter("llm_errors_total", "reason", "hard_timeout").increment()
                meterRegistry.counter("llm_timeout_count", "type", "hard").increment()
                meterRegistry.counter("llm_error_count", "reason", "hard_timeout").increment()
                throw LlmUnavailableException("LLM hard timeout exceeded ${hardTimeoutMs}ms", root)
            }
            meterRegistry.counter("llm_errors_total", "reason", "unknown").increment()
            meterRegistry.counter("llm_error_count", "reason", "unknown").increment()
            throw LlmUnavailableException("LLM request failed", root)
        }
    }

    override fun generateStream(
        request: LlmGenerateRequest,
        onChunk: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): LlmGenerateResponse {
        if (!streamingEnabled) {
            val response = generate(request)
            if (response.text.isNotBlank()) {
                onChunk(response.text)
            }
            return response
        }

        val startedAt = System.nanoTime()
        val payload = mapOf(
            "model" to request.model,
            "prompt" to request.prompt,
            "system" to request.systemPrompt,
            "stream" to true,
            "options" to mapOf(
                "temperature" to request.temperature,
                "num_predict" to request.maxTokens,
            ),
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .timeout(Duration.ofMillis(hardTimeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        } catch (ex: Exception) {
            meterRegistry.counter("llm_errors_total", "reason", "stream_connect").increment()
            if (ex is HttpTimeoutException) {
                meterRegistry.counter("llm_timeout_count", "type", "hard").increment()
                meterRegistry.counter("llm_error_count", "reason", "stream_hard_timeout").increment()
            } else {
                meterRegistry.counter("llm_error_count", "reason", "stream_connect").increment()
            }
            throw LlmUnavailableException("LLM streaming endpoint is unavailable", ex)
        }

        if (response.statusCode() !in 200..299) {
            meterRegistry.counter("llm_errors_total", "reason", "stream_http_${response.statusCode()}").increment()
            meterRegistry.counter("llm_error_count", "reason", "stream_http_${response.statusCode()}").increment()
            throw LlmUnavailableException("LLM streaming failed: HTTP ${response.statusCode()}")
        }

        val buffer = StringBuilder()
        BufferedReader(InputStreamReader(response.body())).use { reader ->
            while (true) {
                if (cancelSignal()) {
                    meterRegistry.counter("llm_stream_cancel_total").increment()
                    meterRegistry.counter("llm_error_count", "reason", "stream_cancelled").increment()
                    throw CancellationException("client disconnected")
                }
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val chunkNode = parseJson(line) ?: continue
                val token = chunkNode.path("response").asText("")
                if (token.isNotBlank()) {
                    buffer.append(token)
                    onChunk(token)
                }
                if (chunkNode.path("done").asBoolean(false)) {
                    break
                }
            }
        }

        val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
        meterRegistry.timer("llm_ms").record(Duration.ofMillis(elapsedMs))
        return LlmGenerateResponse(
            text = buffer.toString(),
            model = request.model,
            elapsedMs = elapsedMs,
        )
    }

    private fun sendGenerateRequest(request: LlmGenerateRequest): LlmGenerateResponse {
        val payload = mapOf(
            "model" to request.model,
            "prompt" to request.prompt,
            "system" to request.systemPrompt,
            "stream" to false,
            "format" to "json",
            "options" to mapOf(
                "temperature" to request.temperature,
                "num_predict" to request.maxTokens,
            ),
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .timeout(Duration.ofMillis(hardTimeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            meterRegistry.counter("llm_errors_total", "reason", "http_${response.statusCode()}").increment()
            meterRegistry.counter("llm_error_count", "reason", "http_${response.statusCode()}").increment()
            throw LlmUnavailableException("LLM request failed: HTTP ${response.statusCode()} ${response.body()}")
        }
        val node = parseJson(response.body())
            ?: throw LlmUnavailableException("LLM response parse failed")
        val text = node.path("response").asText("").trim()
        if (text.isBlank()) {
            meterRegistry.counter("llm_errors_total", "reason", "empty_response").increment()
            meterRegistry.counter("llm_error_count", "reason", "empty_response").increment()
            throw LlmUnavailableException("LLM returned empty response")
        }
        return LlmGenerateResponse(
            text = text,
            model = node.path("model").asText(request.model),
            elapsedMs = 0,
        )
    }

    private fun parseJson(raw: String): JsonNode? {
        return try {
            objectMapper.readTree(raw)
        } catch (_: Exception) {
            null
        }
    }
}
