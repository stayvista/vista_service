package com.devoceanblue.stayvista.domain.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class LocalLlmClientTest {
    @Test
    fun `generate should throw soft timeout and increase timeout metric`() {
        withGenerateServer(
            handler = { exchange ->
                Thread.sleep(120)
                respondJson(exchange, 200, """{"response":"ok","model":"unit"}""")
            },
            block = { baseUrl ->
                val meterRegistry = SimpleMeterRegistry()
                val client = LocalLlmClient(
                    objectMapper = jacksonObjectMapper(),
                    meterRegistry = meterRegistry,
                    baseUrl = baseUrl,
                    softTimeoutMs = 30,
                    hardTimeoutMs = 1000,
                    streamingEnabled = true,
                )

                assertThrows(LlmSoftTimeoutException::class.java) {
                    client.generate(
                        LlmGenerateRequest(
                            prompt = "hello",
                            systemPrompt = "system",
                            model = "unit",
                        ),
                    )
                }

                val timeoutCount = meterRegistry.get("llm_timeout_count").tag("type", "soft").counter().count()
                assertEquals(1.0, timeoutCount)
            },
        )
    }

    @Test
    fun `generateStream should cancel when client disconnect signal is raised`() {
        withGenerateServer(
            handler = { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { body ->
                    body.write("{".toByteArray())
                    body.write("\"response\":\"hello \",\"done\":false}".toByteArray())
                    body.write("\n".toByteArray())
                    body.flush()
                    Thread.sleep(40)
                    body.write("{".toByteArray())
                    body.write("\"response\":\"world\",\"done\":true}".toByteArray())
                    body.write("\n".toByteArray())
                    body.flush()
                }
            },
            block = { baseUrl ->
                val meterRegistry = SimpleMeterRegistry()
                val client = LocalLlmClient(
                    objectMapper = jacksonObjectMapper(),
                    meterRegistry = meterRegistry,
                    baseUrl = baseUrl,
                    softTimeoutMs = 1000,
                    hardTimeoutMs = 2000,
                    streamingEnabled = true,
                )

                val chunks = AtomicInteger(0)
                assertThrows(CancellationException::class.java) {
                    client.generateStream(
                        request = LlmGenerateRequest(
                            prompt = "hello",
                            systemPrompt = "system",
                            model = "unit",
                        ),
                        onChunk = { chunks.incrementAndGet() },
                        cancelSignal = { chunks.get() >= 1 },
                    )
                }

                assertEquals(1, chunks.get())
                assertEquals(1.0, meterRegistry.get("llm_stream_cancel_total").counter().count())
            },
        )
    }

    @Test
    fun `generateStream should fallback to sync generate when streaming is disabled`() {
        withGenerateServer(
            handler = { exchange ->
                respondJson(exchange, 200, """{"response":"final output","model":"unit"}""")
            },
            block = { baseUrl ->
                val meterRegistry = SimpleMeterRegistry()
                val client = LocalLlmClient(
                    objectMapper = jacksonObjectMapper(),
                    meterRegistry = meterRegistry,
                    baseUrl = baseUrl,
                    softTimeoutMs = 1000,
                    hardTimeoutMs = 2000,
                    streamingEnabled = false,
                )

                var chunk = ""
                val response = client.generateStream(
                    request = LlmGenerateRequest(
                        prompt = "hello",
                        systemPrompt = "system",
                        model = "unit",
                    ),
                    onChunk = { token -> chunk += token },
                )

                assertEquals("final output", response.text)
                assertEquals("final output", chunk)
            },
        )
    }

    private fun withGenerateServer(
        handler: (HttpExchange) -> Unit,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/generate") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            block(baseUrl)
        } finally {
            server.stop(0)
        }
    }

    private fun respondJson(exchange: HttpExchange, status: Int, payload: String) {
        val bytes = payload.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { body ->
            body.write(bytes)
            body.flush()
        }
    }
}
