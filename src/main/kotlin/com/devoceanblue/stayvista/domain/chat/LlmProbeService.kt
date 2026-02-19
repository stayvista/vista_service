package com.devoceanblue.stayvista.domain.chat

import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class LlmProbeService(
    private val objectMapper: ObjectMapper,
    private val modelRegistry: LlmModelRegistry,
    @Value("\${stayvista.chat.llm.base-url:http://127.0.0.1:23434}") private val baseUrl: String,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(800))
        .build()

    fun healthz(): LlmProbeResult {
        return probeTags()
    }

    fun readyz(): LlmProbeResult {
        val health = probeTags()
        if (!health.ok) {
            return health
        }
        return probeModelReady(modelRegistry.activeModel())
    }

    private fun probeTags(): LlmProbeResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return LlmProbeResult(
                    ok = false,
                    status = "down",
                    detail = "HTTP ${response.statusCode()}",
                )
            }
            LlmProbeResult(
                ok = true,
                status = "up",
                detail = "tags endpoint reachable",
            )
        } catch (ex: ConnectException) {
            LlmProbeResult(
                ok = false,
                status = "down",
                detail = ex.message ?: "connect error",
            )
        } catch (ex: Exception) {
            LlmProbeResult(
                ok = false,
                status = "down",
                detail = ex.javaClass.simpleName,
            )
        }
    }

    private fun probeModelReady(model: String): LlmProbeResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return LlmProbeResult(
                    ok = false,
                    status = "not_ready",
                    detail = "HTTP ${response.statusCode()}",
                )
            }
            val root = objectMapper.readTree(response.body())
            val found = root.path("models").any { node ->
                node.path("name").asText("").startsWith(model)
            }
            if (!found) {
                return LlmProbeResult(
                    ok = false,
                    status = "not_ready",
                    detail = "model '$model' is not pulled",
                )
            }
            LlmProbeResult(
                ok = true,
                status = "ready",
                detail = "model '$model' is available",
            )
        } catch (ex: Exception) {
            LlmProbeResult(
                ok = false,
                status = "not_ready",
                detail = ex.javaClass.simpleName,
            )
        }
    }
}

data class LlmProbeResult(
    val ok: Boolean,
    val status: String,
    val detail: String,
)
