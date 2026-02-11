package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ChatShadowService(
    private val llmClient: LlmClient,
    private val modelRegistry: LlmModelRegistry,
    private val routingPolicy: ChatRoutingPolicy,
    private val ragRetriever: LocalRagRetriever,
    private val promptFactory: ChatPromptFactory,
    private val structuredChatParser: StructuredChatParser,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val piiRedactor: PiiRedactor,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.shadow.enabled:false}") private val enabled: Boolean,
    @Value("\${stayvista.chat.shadow.model:}") private val configuredShadowModel: String,
) {
    private val executor = Executors.newFixedThreadPool(2)

    fun submit(
        request: ChatRecommendRequest,
        primaryResponse: ChatRecommendData,
        routePrimary: String,
        modelPrimary: String?,
        promptVersion: String? = null,
    ) {
        if (!enabled) {
            return
        }

        CompletableFuture.runAsync(
            {
                executeShadow(request, primaryResponse, routePrimary, modelPrimary, promptVersion)
            },
            executor,
        ).exceptionally {
            meterRegistry.counter("chat_shadow_total", "result", "async_error").increment()
            null
        }
    }

    private fun executeShadow(
        request: ChatRecommendRequest,
        primaryResponse: ChatRecommendData,
        routePrimary: String,
        modelPrimary: String?,
        promptVersion: String?,
    ) {
        val requestRedacted = piiRedactor.redact(request.message)
        val responseRedacted = piiRedactor.redact(primaryResponse.assistant_text)
        val shadowModel = resolveShadowModel()

        runCatching {
            val slots = routingPolicy.extractSlots(request)
            val retrieval = ragRetriever.searchItems(request.message, slots)
            val llmResponse = llmClient.generate(
                LlmGenerateRequest(
                    prompt = promptFactory.buildUserPrompt(
                        request = request,
                        slots = slots,
                        hits = retrieval.hits.take(6),
                        memory = ChatMemorySnapshot(),
                        promptVersion = promptVersion,
                    ),
                    systemPrompt = promptFactory.buildSystemPrompt(promptVersion),
                    model = shadowModel,
                ),
            )
            val parsed = runCatching { structuredChatParser.parseStrict(llmResponse.text) }.getOrNull()

            val metricsJson = objectMapper.writeValueAsString(
                mapOf(
                    "llm_elapsed_ms" to llmResponse.elapsedMs,
                    "retrieval_ms" to retrieval.retrievalMs,
                    "cards_count" to (parsed?.cards?.size ?: 0),
                    "sources_count" to (parsed?.cards?.flatMap { it.source }?.distinctBy { it.doc_id }?.size ?: 0),
                    "used_embedding" to retrieval.usedEmbedding,
                ),
            )

            val runId = insertRun(
                routePrimary = routePrimary,
                routeShadow = "LLM",
                modelPrimary = modelPrimary,
                modelShadow = shadowModel,
                metricsJson = metricsJson,
                errorMessage = null,
            )
            insertSample(
                runId = runId,
                requestRedacted = requestRedacted,
                responseRedacted = piiRedactor.redact(parsed?.assistantText ?: llmResponse.text),
            )
            meterRegistry.counter("chat_shadow_total", "result", "success").increment()
        }.onFailure { ex ->
            val runId = insertRun(
                routePrimary = routePrimary,
                routeShadow = "ERROR",
                modelPrimary = modelPrimary,
                modelShadow = shadowModel,
                metricsJson = "{}",
                errorMessage = (ex.message ?: ex.javaClass.simpleName).take(255),
            )
            insertSample(
                runId = runId,
                requestRedacted = requestRedacted,
                responseRedacted = responseRedacted,
            )
            meterRegistry.counter("chat_shadow_total", "result", "error").increment()
        }
    }

    private fun insertRun(
        routePrimary: String,
        routeShadow: String,
        modelPrimary: String?,
        modelShadow: String?,
        metricsJson: String,
        errorMessage: String?,
    ): Long {
        val keyHolder = org.springframework.jdbc.support.GeneratedKeyHolder()
        jdbcTemplate.update({ conn ->
            val ps = conn.prepareStatement(
                """
                INSERT INTO chat_shadow_run (
                  route_primary,
                  route_shadow,
                  model_primary,
                  model_shadow,
                  metrics_json,
                  error_message
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            )
            ps.setString(1, routePrimary)
            ps.setString(2, routeShadow)
            ps.setString(3, modelPrimary)
            ps.setString(4, modelShadow)
            ps.setString(5, metricsJson)
            ps.setString(6, errorMessage)
            ps
        }, keyHolder)
        return keyHolder.key?.toLong() ?: 0L
    }

    private fun insertSample(
        runId: Long,
        requestRedacted: String,
        responseRedacted: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO chat_shadow_sample (
              shadow_run_id,
              request_redacted,
              response_redacted
            ) VALUES (?, ?, ?)
            """.trimIndent(),
            runId,
            requestRedacted.take(4000),
            responseRedacted.take(4000),
        )
    }

    private fun resolveShadowModel(): String {
        if (configuredShadowModel.isNotBlank()) {
            return configuredShadowModel
        }
        return modelRegistry.fallbackModel() ?: modelRegistry.activeModel()
    }
}
