package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

class StructuredRepairFailedException(cause: Throwable? = null) : RuntimeException("structured_output_repair_failed", cause)

@Service
class ChatService(
    private val meterRegistry: MeterRegistry,
    private val routingPolicy: ChatRoutingPolicy,
    private val safetyPolicy: ChatSafetyPolicy,
    private val chatCacheService: ChatCacheService,
    private val ragRetriever: LocalRagRetriever,
    private val llmExecutionGate: LlmExecutionGate,
    private val llmClient: LlmClient,
    private val modelRegistry: LlmModelRegistry,
    private val promptFactory: ChatPromptFactory,
    private val structuredChatParser: StructuredChatParser,
    @Value("\${stayvista.chat.cache.retrieval-ttl-seconds:900}") private val retrievalCacheTtlSeconds: Long,
    @Value("\${stayvista.chat.cache.prompt-ttl-seconds:180}") private val promptCacheTtlSeconds: Long,
    @Value("\${stayvista.chat.llm.enabled:true}") private val llmEnabled: Boolean,
) {
    fun recommend(request: ChatRecommendRequest): ChatRecommendData {
        val startedAt = System.nanoTime()
        meterRegistry.counter("chat_requests_total").increment()

        var route = "TEMPLATE"
        var model: String? = null
        var llmMs = 0L
        var ragMs = 0L

        val result = runCatching {
            val message = request.message.trim()
            val safetyDecision = safetyPolicy.evaluateInput(message)
            if (safetyDecision.blocked) {
                route = "BLOCKED"
                meterRegistry.counter("chat_route_total", "route", route.lowercase()).increment()
                return@runCatching buildBlockedResponse(safetyDecision.reason ?: "요청을 처리할 수 없습니다.")
            }

            val slots = routingPolicy.extractSlots(request)
            val retrievalKey = sha256(retrievalCacheKey(slots, message))
            val retrieval = chatCacheService.singleFlight("retrieval", retrievalKey) {
                chatCacheService.getRetrievalCache(retrievalKey)
                    ?: ragRetriever.searchItems(message, slots)
                        .also { chatCacheService.putRetrievalCache(retrievalKey, it, retrievalCacheTtlSeconds) }
            }
            ragMs = retrieval.retrievalMs

            val routeDecision = routingPolicy.decide(message, slots, retrieval.hits, llmAllowed = llmEnabled)
            route = toDebugRoute(routeDecision.type)
            recordRouteRate(routeDecision.type)

            when (routeDecision.type) {
                ChatRouteType.ASK_CLARIFICATION -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    buildClarificationResponse(slots, routeDecision)
                }

                ChatRouteType.TEMPLATE -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    buildTemplateResponse(
                        slots = slots,
                        retrieval = retrieval,
                        followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
                        contextUsed = mapOf(
                            "route" to routeDecision.reason,
                            "embedding_used" to retrieval.usedEmbedding,
                        ),
                    )
                }

                ChatRouteType.LLM -> {
                    val promptCacheEnabled = safetyPolicy.cacheAllowed(message)
                    val promptCacheKey = sha256(
                        buildString {
                            append("v3|")
                            append(modelRegistry.activeModel())
                            append('|')
                            append(message.lowercase())
                            append('|')
                            append(slots.city ?: "-")
                            append('|')
                            append(retrieval.hits.take(4).joinToString(";") { it.document.docId })
                        },
                    )

                    if (promptCacheEnabled) {
                        val cached = chatCacheService.getPromptCache(promptCacheKey)
                        if (cached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "true").increment()
                            return@runCatching cached
                        }
                    }

                    val llmResult = llmExecutionGate.run {
                        val llmResponse = llmClient.generate(
                            LlmGenerateRequest(
                                prompt = promptFactory.buildUserPrompt(request, slots, retrieval.hits.take(6)),
                                systemPrompt = promptFactory.buildSystemPrompt(),
                                model = modelRegistry.activeModel(),
                            ),
                        )

                        model = llmResponse.model
                        llmMs += llmResponse.elapsedMs

                        parseStructuredOutputWithRepair(llmResponse.text)
                    }

                    if (llmResult.rejected || llmResult.value == null) {
                        meterRegistry.counter("chat_llm_fail_total", "reason", "queue_rejected").increment()
                        meterRegistry.counter("llm_used_rate", "used", "false").increment()
                        return@runCatching buildTemplateResponse(
                            slots = slots,
                            retrieval = retrieval,
                            followups = routingPolicy.defaultFollowups(slots),
                            contextUsed = mapOf(
                                "route" to "queue_rejected_fallback",
                                "embedding_used" to retrieval.usedEmbedding,
                            ),
                        )
                    }

                    val normalized = normalizeStructuredResult(
                        structured = llmResult.value,
                        retrieval = retrieval,
                        slots = slots,
                    )

                    if (promptCacheEnabled) {
                        chatCacheService.putPromptCache(promptCacheKey, normalized, promptCacheTtlSeconds)
                    }
                    meterRegistry.counter("llm_used_rate", "used", "true").increment()
                    normalized
                }
            }
        }.getOrElse { ex ->
            meterRegistry.counter("chat_llm_fail_total", "reason", "fallback").increment()
            if (ex is StructuredRepairFailedException) {
                meterRegistry.counter("fallback_due_to_parse_rate", "route", "sync").increment()
            }
            val slots = routingPolicy.extractSlots(request)
            val fallbackRetrieval = runCatching {
                ragRetriever.searchItems(request.message, slots)
            }.getOrElse {
                RagSearchResult(emptyList(), retrievalMs = 0L, usedEmbedding = false)
            }
            ragMs = fallbackRetrieval.retrievalMs
            buildTemplateResponse(
                slots = slots,
                retrieval = fallbackRetrieval,
                followups = routingPolicy.defaultFollowups(slots),
                contextUsed = mapOf(
                    "route" to "exception_fallback",
                    "error_type" to ex.javaClass.simpleName,
                ),
            )
        }

        val totalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
        meterRegistry.timer("chat_latency_seconds").record(Duration.ofMillis(totalMs))
        val debug = if (modelRegistry.shouldExposeModelVersion()) {
            ChatDebug(
                route = route,
                model = model,
                llm_ms = llmMs,
                rag_ms = ragMs,
                total_ms = totalMs,
            )
        } else {
            null
        }
        return result.copy(debug = debug)
    }

    fun recommendStream(
        request: ChatRecommendRequest,
        onMeta: (Map<String, Any?>) -> Unit,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): ChatRecommendData {
        val startedAt = System.nanoTime()
        meterRegistry.counter("chat_requests_total").increment()

        var route = "TEMPLATE"
        var model: String? = null
        var llmMs = 0L
        var ragMs = 0L
        var firstEventAtNanos: Long? = null

        fun markFirstEvent() {
            if (firstEventAtNanos == null) {
                firstEventAtNanos = System.nanoTime()
                val ttfbMs = Duration.ofNanos(firstEventAtNanos!! - startedAt).toMillis().coerceAtLeast(1)
                meterRegistry.timer("chat_ttfb_ms").record(Duration.ofMillis(ttfbMs))
            }
        }

        fun emitMeta(payload: Map<String, Any?>) {
            if (isCancelled()) throw CancellationException("stream cancelled before meta")
            markFirstEvent()
            onMeta(payload)
        }

        fun emitToken(token: String) {
            if (token.isBlank() || isCancelled()) return
            markFirstEvent()
            onToken(token)
        }

        val result = runCatching {
            val message = request.message.trim()
            val safetyDecision = safetyPolicy.evaluateInput(message)
            if (safetyDecision.blocked) {
                route = "BLOCKED"
                meterRegistry.counter("chat_route_total", "route", route.lowercase()).increment()
                emitMeta(mapOf("route" to route, "route_reason" to "guardrails_blocked", "llm_used" to false))
                return@runCatching buildBlockedResponse(safetyDecision.reason ?: "요청을 처리할 수 없습니다.")
            }

            val slots = routingPolicy.extractSlots(request)
            val retrievalKey = sha256(retrievalCacheKey(slots, message))
            val retrieval = chatCacheService.singleFlight("retrieval", retrievalKey) {
                chatCacheService.getRetrievalCache(retrievalKey)
                    ?: ragRetriever.searchItems(message, slots)
                        .also { chatCacheService.putRetrievalCache(retrievalKey, it, retrievalCacheTtlSeconds) }
            }
            ragMs = retrieval.retrievalMs

            val routeDecision = routingPolicy.decide(message, slots, retrieval.hits, llmAllowed = llmEnabled)
            route = toDebugRoute(routeDecision.type)
            recordRouteRate(routeDecision.type)

            when (routeDecision.type) {
                ChatRouteType.ASK_CLARIFICATION -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to false))
                    buildClarificationResponse(slots, routeDecision)
                }

                ChatRouteType.TEMPLATE -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to false))
                    buildTemplateResponse(
                        slots = slots,
                        retrieval = retrieval,
                        followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
                        contextUsed = mapOf(
                            "route" to routeDecision.reason,
                            "embedding_used" to retrieval.usedEmbedding,
                        ),
                    )
                }

                ChatRouteType.LLM -> {
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to true))

                    val promptCacheEnabled = safetyPolicy.cacheAllowed(message)
                    val promptCacheKey = sha256(
                        buildString {
                            append("v3|")
                            append(modelRegistry.activeModel())
                            append('|')
                            append(message.lowercase())
                            append('|')
                            append(slots.city ?: "-")
                            append('|')
                            append(retrieval.hits.take(4).joinToString(";") { it.document.docId })
                        },
                    )

                    if (promptCacheEnabled) {
                        val cached = chatCacheService.getPromptCache(promptCacheKey)
                        if (cached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "true").increment()
                            emitToken(cached.assistant_text)
                            return@runCatching cached
                        }
                    }

                    val llmResult = llmExecutionGate.run {
                        val llmResponse = llmClient.generateStream(
                            request = LlmGenerateRequest(
                                prompt = promptFactory.buildUserPrompt(request, slots, retrieval.hits.take(6)),
                                systemPrompt = promptFactory.buildSystemPrompt(),
                                model = modelRegistry.activeModel(),
                            ),
                            onChunk = { chunk -> emitToken(chunk) },
                            cancelSignal = isCancelled,
                        )

                        model = llmResponse.model
                        llmMs += llmResponse.elapsedMs

                        parseStructuredOutputWithRepair(llmResponse.text)
                    }

                    if (llmResult.rejected || llmResult.value == null) {
                        meterRegistry.counter("chat_llm_fail_total", "reason", "queue_rejected").increment()
                        meterRegistry.counter("llm_used_rate", "used", "false").increment()
                        return@runCatching buildTemplateResponse(
                            slots = slots,
                            retrieval = retrieval,
                            followups = routingPolicy.defaultFollowups(slots),
                            contextUsed = mapOf(
                                "route" to "queue_rejected_fallback",
                                "embedding_used" to retrieval.usedEmbedding,
                            ),
                        )
                    }

                    val normalized = normalizeStructuredResult(
                        structured = llmResult.value,
                        retrieval = retrieval,
                        slots = slots,
                    )
                    if (promptCacheEnabled) {
                        chatCacheService.putPromptCache(promptCacheKey, normalized, promptCacheTtlSeconds)
                    }
                    meterRegistry.counter("llm_used_rate", "used", "true").increment()
                    normalized
                }
            }
        }.getOrElse { ex ->
            if (ex is CancellationException) {
                throw ex
            }
            meterRegistry.counter("chat_llm_fail_total", "reason", "fallback").increment()
            if (ex is StructuredRepairFailedException) {
                meterRegistry.counter("fallback_due_to_parse_rate", "route", "stream").increment()
            }
            val slots = routingPolicy.extractSlots(request)
            val fallbackRetrieval = runCatching {
                ragRetriever.searchItems(request.message, slots)
            }.getOrElse {
                RagSearchResult(emptyList(), retrievalMs = 0L, usedEmbedding = false)
            }
            ragMs = fallbackRetrieval.retrievalMs
            buildTemplateResponse(
                slots = slots,
                retrieval = fallbackRetrieval,
                followups = routingPolicy.defaultFollowups(slots),
                contextUsed = mapOf(
                    "route" to "exception_fallback",
                    "error_type" to ex.javaClass.simpleName,
                ),
            )
        }

        val totalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
        meterRegistry.timer("chat_stream_duration_ms").record(Duration.ofMillis(totalMs))
        meterRegistry.timer("chat_latency_seconds").record(Duration.ofMillis(totalMs))
        if (firstEventAtNanos == null) {
            meterRegistry.timer("chat_ttfb_ms").record(Duration.ofMillis(totalMs))
        }
        val debug = if (modelRegistry.shouldExposeModelVersion()) {
            ChatDebug(
                route = route,
                model = model,
                llm_ms = llmMs,
                rag_ms = ragMs,
                total_ms = totalMs,
            )
        } else {
            null
        }

        return result.copy(debug = debug)
    }

    private fun toDebugRoute(type: ChatRouteType): String {
        return when (type) {
            ChatRouteType.ASK_CLARIFICATION -> "CLARIFY"
            ChatRouteType.TEMPLATE -> "TEMPLATE"
            ChatRouteType.LLM -> "LLM"
        }
    }

    private fun recordRouteRate(type: ChatRouteType) {
        val route = toDebugRoute(type)
        meterRegistry.counter("chat_route_total", "route", route.lowercase()).increment()
        when (type) {
            ChatRouteType.ASK_CLARIFICATION -> meterRegistry.counter("route_clarify_rate").increment()
            ChatRouteType.TEMPLATE -> meterRegistry.counter("route_template_rate").increment()
            ChatRouteType.LLM -> meterRegistry.counter("route_llm_rate").increment()
        }
    }

    private fun parseStructuredOutputWithRepair(raw: String): StructuredLlmOutput {
        return try {
            structuredChatParser.parseStrict(raw)
        } catch (_: Exception) {
            meterRegistry.counter("chat_json_parse_fail_total", "phase", "primary").increment()
            meterRegistry.counter("structured_parse_fail_count", "phase", "primary").increment()
            val repaired = llmClient.generate(
                LlmGenerateRequest(
                    prompt = promptFactory.buildRepairPrompt(raw),
                    systemPrompt = promptFactory.buildSystemPrompt(),
                    model = modelRegistry.activeModel(),
                    maxTokens = 420,
                ),
            )
            meterRegistry.timer("llm_ms").record(Duration.ofMillis(repaired.elapsedMs.coerceAtLeast(1)))
            try {
                val repairedStructured = structuredChatParser.parseStrict(repaired.text)
                meterRegistry.counter("structured_repair_success_rate", "result", "success").increment()
                repairedStructured
            } catch (repairException: Exception) {
                meterRegistry.counter("chat_json_parse_fail_total", "phase", "repair").increment()
                meterRegistry.counter("structured_parse_fail_count", "phase", "repair").increment()
                meterRegistry.counter("structured_repair_success_rate", "result", "fail").increment()
                throw StructuredRepairFailedException(repairException)
            }
        }
    }

    private fun buildBlockedResponse(reason: String): ChatRecommendData {
        val text = "안전한 서비스 운영을 위해 요청을 진행할 수 없어요. $reason"
        return ChatRecommendData(
            answer = text,
            assistant_text = text,
            cards = emptyList(),
            followups = listOf("개인정보를 제외하고 여행 조건만 다시 입력해 주세요."),
            context_used = mapOf("route" to "guardrails_blocked"),
            llm_used = false,
            sources = emptyList(),
        )
    }

    private fun buildClarificationResponse(
        slots: ChatSlots,
        routeDecision: ChatRouteDecision,
    ): ChatRecommendData {
        val text = if (slots.city == null) {
            "도시 정보가 필요합니다. 목적지를 알려주시면 숙소, 티켓, 주변 추천을 바로 정리해 드릴게요."
        } else {
            "조건을 조금만 더 알려주시면 맞춤 추천 정확도를 높일 수 있어요."
        }

        return ChatRecommendData(
            answer = text,
            assistant_text = text,
            cards = emptyList(),
            followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
            context_used = mapOf("route" to routeDecision.reason),
            llm_used = false,
            sources = emptyList(),
        )
    }

    private fun buildTemplateResponse(
        slots: ChatSlots,
        retrieval: RagSearchResult,
        followups: List<String>,
        contextUsed: Map<String, Any?>,
    ): ChatRecommendData {
        val cards = retrieval.hits
            .take(4)
            .map { hit -> toCardFromHit(hit) }
            .let { safetyPolicy.filterCardsWithSource(it) }

        val sources = retrieval.hits
            .take(4)
            .map { hit -> hit.document.toSource() }
            .ifEmpty {
                cards.flatMap { it.source }.take(2)
            }

        val answer = if (cards.isEmpty()) {
            "현재 조건으로는 신뢰 가능한 추천 근거가 부족합니다. 도시와 일정을 조금 더 구체적으로 입력해 주세요."
        } else {
            "${slots.city ?: "요청하신 조건"} 기준으로 바로 예약 가능한 후보를 정리했어요. 카드에서 상세를 확인하고 바로 진행하실 수 있어요."
        }

        return ChatRecommendData(
            answer = answer,
            assistant_text = answer,
            cards = cards,
            followups = followups.take(2),
            context_used = contextUsed + mapOf(
                "sources" to sources.map { it.doc_id },
                "rag_ms" to retrieval.retrievalMs,
            ),
            llm_used = false,
            sources = sources,
        )
    }

    private fun normalizeStructuredResult(
        structured: StructuredLlmOutput,
        retrieval: RagSearchResult,
        slots: ChatSlots,
    ): ChatRecommendData {
        val fallbackSources = retrieval.hits.take(4).map { it.document.toSource() }
        val cards = structured.cards
            .map { card ->
                val normalizedType = card.type.uppercase()
                val source = if (card.source.isNotEmpty()) card.source else fallbackSources.take(1)
                val sourceDoc = source.firstOrNull()
                val sourceDocId = sourceDoc?.doc_id ?: card.id

                val propertyId = if (normalizedType == "PROPERTY") parseId(sourceDocId ?: card.id) else null
                val productId = if (normalizedType == "TICKET") parseId(sourceDocId ?: card.id) else null
                val packageId = if (normalizedType == "PACKAGE") parseId(sourceDocId ?: card.id) else null
                val poiId = if (normalizedType == "POI") {
                    val raw = sourceDocId ?: card.id
                    raw?.substringAfter(':')?.toLongOrNull()?.let { "poi_$it" }
                } else {
                    null
                }

                ChatCard(
                    type = normalizedType,
                    id = card.id ?: sourceDocId,
                    title = card.title,
                    price = card.price,
                    why = card.why,
                    source = source,
                    property_id = propertyId,
                    product_id = productId,
                    package_id = packageId,
                    poi_id = poiId,
                )
            }
            .let { safetyPolicy.filterCardsWithSource(it) }

        val finalCards = if (cards.isEmpty()) {
            retrieval.hits.take(3).map { toCardFromHit(it) }
        } else {
            cards
        }

        val finalSources = finalCards.flatMap { it.source }
            .distinctBy { it.doc_id }
            .ifEmpty { fallbackSources }
            .take(6)

        val finalText = safetyPolicy.sanitizeText(structured.assistantText)
            .ifBlank {
                "${slots.city ?: "요청하신 도시"} 기준으로 후보를 정리했어요. 각 카드에서 상세 정보를 확인해 주세요."
            }

        return ChatRecommendData(
            answer = finalText,
            assistant_text = finalText,
            cards = finalCards,
            followups = structured.followups.ifEmpty { routingPolicy.defaultFollowups(slots) }.take(2),
            context_used = structured.contextUsed + mapOf(
                "sources" to finalSources.map { it.doc_id },
                "embedding_used" to retrieval.usedEmbedding,
                "rag_ms" to retrieval.retrievalMs,
            ),
            llm_used = true,
            sources = finalSources,
        )
    }

    private fun toCardFromHit(hit: RagHit): ChatCard {
        val doc = hit.document
        val source = listOf(doc.toSource())
        val type = doc.sourceType.uppercase()
        return when (type) {
            "PROPERTY" -> ChatCard(
                type = "PROPERTY",
                id = doc.docId,
                title = doc.title,
                why = doc.snippet,
                source = source,
                property_id = parseId(doc.docId),
            )

            "TICKET" -> ChatCard(
                type = "TICKET",
                id = doc.docId,
                title = doc.title,
                why = doc.snippet,
                source = source,
                product_id = parseId(doc.docId),
            )

            "PACKAGE" -> ChatCard(
                type = "PACKAGE",
                id = doc.docId,
                title = doc.title,
                why = doc.snippet,
                source = source,
                package_id = parseId(doc.docId),
            )

            else -> ChatCard(
                type = "POI",
                id = doc.docId,
                title = doc.title,
                why = doc.snippet,
                source = source,
                poi_id = parseId(doc.docId)?.let { "poi_$it" },
            )
        }
    }

    private fun parseId(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.substringAfter(':', raw).toLongOrNull()
    }

    private fun RagDocument.toSource(): ChatSource {
        return ChatSource(
            doc_id = docId,
            title = title,
            snippet = snippet,
            source_type = sourceType,
        )
    }

    private fun retrievalCacheKey(slots: ChatSlots, message: String): String {
        val normalized = message.lowercase().replace(Regex("\\s+"), " ").trim()
        return "${slots.city ?: "-"}|${slots.intent}|$normalized"
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
