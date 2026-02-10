package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

interface EmbedClient {
    fun embed(text: String): List<Double>
}

@Component
class LocalEmbedClient(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val modelRegistry: LlmModelRegistry,
    @Value("\${stayvista.chat.embed.base-url:http://127.0.0.1:11434}") private val baseUrl: String,
    @Value("\${stayvista.chat.embed.timeout-ms:4000}") private val timeoutMs: Long,
) : EmbedClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1200))
        .build()

    override fun embed(text: String): List<Double> {
        val candidate = text.trim()
        if (candidate.isBlank()) {
            return emptyList()
        }

        runCatching {
            return requestEmbed(
                endpoint = "/api/embed",
                body = mapOf(
                    "model" to modelRegistry.embedModel(),
                    "input" to candidate,
                ),
                extractor = { node ->
                    val first = node.path("embeddings")
                    if (!first.isArray || first.size() == 0) emptyList() else first[0].toVector()
                },
            )
        }

        // Ollama compatibility for old endpoint
        return requestEmbed(
            endpoint = "/api/embeddings",
            body = mapOf(
                "model" to modelRegistry.embedModel(),
                "prompt" to candidate,
            ),
            extractor = { node -> node.path("embedding").toVector() },
        )
    }

    private fun requestEmbed(
        endpoint: String,
        body: Map<String, Any>,
        extractor: (tools.jackson.databind.JsonNode) -> List<Double>,
    ): List<Double> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$endpoint"))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            meterRegistry.counter("chat_embed_errors_total", "reason", "http_${response.statusCode()}").increment()
            throw LlmUnavailableException("Embedding endpoint failed: HTTP ${response.statusCode()}")
        }
        val node = objectMapper.readTree(response.body())
        val vector = extractor(node)
        if (vector.isEmpty()) {
            meterRegistry.counter("chat_embed_errors_total", "reason", "empty").increment()
            throw LlmUnavailableException("Embedding vector is empty")
        }
        return vector
    }

    private fun tools.jackson.databind.JsonNode.toVector(): List<Double> {
        if (!isArray) {
            return emptyList()
        }
        val result = ArrayList<Double>(size())
        for (i in 0 until size()) {
            result += get(i).asDouble(0.0)
        }
        return result
    }
}
