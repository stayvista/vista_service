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
    private val citationVerifier: CitationVerifier,
    private val chatExperimentService: ChatExperimentService,
    private val chatShadowService: ChatShadowService,
    private val llmBudgetController: LlmBudgetController,
    private val semanticCacheService: SemanticCacheService,
    private val chatMemoryService: ChatMemoryService,
    private val preferenceProfileService: PreferenceProfileService,
    private val itineraryPlannerService: ItineraryPlannerService,
    @Value("\${stayvista.chat.cache.retrieval-ttl-seconds:900}") private val retrievalCacheTtlSeconds: Long,
    @Value("\${stayvista.chat.cache.prompt-ttl-seconds:180}") private val promptCacheTtlSeconds: Long,
    @Value("\${stayvista.chat.llm.enabled:true}") private val llmEnabled: Boolean,
) {
    fun recommend(request: ChatRecommendRequest): ChatRecommendData {
        val startedAt = System.nanoTime()
        meterRegistry.counter("chat_requests_total").increment()
        val message = request.message.trim()
        val sessionKey = chatMemoryService.resolveSessionKey(request)
        val profileKey = preferenceProfileService.resolveProfileKey(request)
        val memory = chatMemoryService.load(sessionKey)
        val experiment = chatExperimentService.assign(request, sessionKey)

        var route = "TEMPLATE"
        var model: String? = null
        var llmMs = 0L
        var ragMs = 0L

        val result = runCatching {
            val safetyDecision = safetyPolicy.evaluateInput(message)
            if (safetyDecision.blocked) {
                route = "BLOCKED"
                meterRegistry.counter("chat_route_total", "route", route.lowercase()).increment()
                return@runCatching buildBlockedResponse(safetyDecision.reason ?: "요청을 처리할 수 없습니다.")
            }

            val slots = resolveSlots(request, memory)
            val retrievalKey = sha256(retrievalCacheKey(slots, message))
            val retrieval = chatCacheService.singleFlight("retrieval", retrievalKey) {
                chatCacheService.getRetrievalCache(retrievalKey)
                    ?: ragRetriever.searchItems(message, slots)
                        .also { chatCacheService.putRetrievalCache(retrievalKey, it, retrievalCacheTtlSeconds) }
            }
            ragMs = retrieval.retrievalMs
            preferenceProfileService.recordImplicitFeedback(profileKey, message)
            val llmAllowedByBudget = llmEnabled && llmBudgetController.allowLlm(message)
            val llmBudgetMode = llmBudgetController.modeLabel()

            val routeDecision = routingPolicy.decide(message, slots, retrieval.hits, llmAllowed = llmAllowedByBudget)
            route = toDebugRoute(routeDecision.type)
            recordRouteRate(routeDecision.type)

            when (routeDecision.type) {
                ChatRouteType.ASK_CLARIFICATION -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    enrichResult(
                        response = buildClarificationResponse(slots, routeDecision, retrieval),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                        allowPlanner = false,
                    )
                }

                ChatRouteType.TEMPLATE -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    enrichResult(
                        response = buildTemplateResponse(
                            message = message,
                            slots = slots,
                            retrieval = retrieval,
                            followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
                            contextUsed = mapOf(
                                "route" to routeDecision.reason,
                                "embedding_used" to retrieval.usedEmbedding,
                                "llm_budget_mode" to llmBudgetMode,
                            ),
                        ),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                    )
                }

                ChatRouteType.LLM -> {
                    val selectedModel = experiment.model_override ?: modelRegistry.activeModel()
                    val promptCacheEnabled = safetyPolicy.cacheAllowed(message)
                    val promptCacheKey = buildPromptCacheKey(
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        memory = memory,
                        model = selectedModel,
                        promptVersion = experiment.prompt_version,
                    )
                    val semanticNamespace = semanticNamespace(slots, retrieval, selectedModel)

                    if (promptCacheEnabled) {
                        val cached = chatCacheService.getPromptCache(promptCacheKey)
                        if (cached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "false").increment()
                            return@runCatching enrichResult(
                                response = cached.copy(
                                    llm_used = false,
                                    context_used = cached.context_used + mapOf("cache" to "prompt"),
                                ),
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                profileKey = profileKey,
                                memory = memory,
                                experiment = experiment,
                            )
                        }

                        val semanticCached = semanticCacheService.lookup(semanticNamespace, message)
                        if (semanticCached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "false").increment()
                            return@runCatching enrichResult(
                                response = semanticCached.copy(llm_used = false),
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                profileKey = profileKey,
                                memory = memory,
                                experiment = experiment,
                            )
                        }
                    }

                    val llmResult = llmExecutionGate.run {
                        val llmResponse = llmClient.generate(
                            LlmGenerateRequest(
                                prompt = promptFactory.buildUserPrompt(
                                    request = request,
                                    slots = slots,
                                    hits = retrieval.hits.take(6),
                                    memory = memory,
                                    promptVersion = experiment.prompt_version,
                                ),
                                systemPrompt = promptFactory.buildSystemPrompt(experiment.prompt_version),
                                model = selectedModel,
                            ),
                        )

                        model = llmResponse.model
                        llmMs += llmResponse.elapsedMs

                        parseStructuredOutputWithRepair(llmResponse.text)
                    }

                    if (llmResult.rejected || llmResult.value == null) {
                        llmBudgetController.recordOutcome(
                            attempted = true,
                            queueWaitMs = llmResult.queueWaitMs,
                            llmElapsedMs = 0L,
                            rejected = true,
                            timeout = false,
                        )
                        meterRegistry.counter("chat_llm_fail_total", "reason", "queue_rejected").increment()
                        meterRegistry.counter("llm_used_rate", "used", "false").increment()
                        return@runCatching enrichResult(
                            response = buildTemplateResponse(
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                followups = routingPolicy.defaultFollowups(slots),
                                contextUsed = mapOf(
                                    "route" to "queue_rejected_fallback",
                                    "embedding_used" to retrieval.usedEmbedding,
                                    "llm_budget_mode" to llmBudgetMode,
                                ),
                            ),
                            message = message,
                            slots = slots,
                            retrieval = retrieval,
                            profileKey = profileKey,
                            memory = memory,
                            experiment = experiment,
                        )
                    }

                    val normalized = normalizeStructuredResult(
                        structured = llmResult.value,
                        retrieval = retrieval,
                        slots = slots,
                    )
                    val verifiedNormalized = citationVerifier.verifyOrMitigate(normalized)

                    if (promptCacheEnabled) {
                        chatCacheService.putPromptCache(promptCacheKey, verifiedNormalized, promptCacheTtlSeconds)
                        semanticCacheService.put(semanticNamespace, message, verifiedNormalized, promptCacheTtlSeconds)
                    }
                    llmBudgetController.recordOutcome(
                        attempted = true,
                        queueWaitMs = llmResult.queueWaitMs,
                        llmElapsedMs = llmMs,
                        rejected = false,
                        timeout = false,
                    )
                    meterRegistry.counter("llm_used_rate", "used", "true").increment()
                    enrichResult(
                        response = verifiedNormalized.copy(
                            context_used = verifiedNormalized.context_used + mapOf("llm_budget_mode" to llmBudgetMode),
                        ),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                    )
                }
            }
        }.getOrElse { ex ->
            if (isTimeoutFailure(ex)) {
                llmBudgetController.recordOutcome(
                    attempted = true,
                    queueWaitMs = 0L,
                    llmElapsedMs = 0L,
                    rejected = false,
                    timeout = true,
                )
            }
            meterRegistry.counter("chat_llm_fail_total", "reason", "fallback").increment()
            if (ex is StructuredRepairFailedException) {
                meterRegistry.counter("fallback_due_to_parse_rate", "route", "sync").increment()
            }
            val slots = resolveSlots(request, memory)
            val fallbackRetrieval = runCatching {
                ragRetriever.searchItems(request.message, slots)
            }.getOrElse {
                RagSearchResult(emptyList(), retrievalMs = 0L, usedEmbedding = false)
            }
            ragMs = fallbackRetrieval.retrievalMs
            enrichResult(
                response = buildTemplateResponse(
                    message = message,
                    slots = slots,
                    retrieval = fallbackRetrieval,
                    followups = routingPolicy.defaultFollowups(slots),
                    contextUsed = mapOf(
                        "route" to "exception_fallback",
                        "error_type" to ex.javaClass.simpleName,
                    ),
                ),
                message = message,
                slots = slots,
                retrieval = fallbackRetrieval,
                profileKey = profileKey,
                memory = memory,
                experiment = experiment,
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
        val guarded = safetyPolicy.enforceOutputPolicy(result)
        val verified = citationVerifier.verifyOrMitigate(guarded)
        val finalized = verified.copy(debug = debug)
        recordExperimentOutcome(experiment, finalized, totalMs)
        persistTurnIfNeeded(sessionKey, message, finalized, route)
        if (route != "BLOCKED") {
            chatShadowService.submit(
                request = request,
                primaryResponse = finalized,
                routePrimary = route,
                modelPrimary = model,
                promptVersion = experiment.prompt_version,
            )
        }
        return finalized
    }

    fun recommendStream(
        request: ChatRecommendRequest,
        onMeta: (Map<String, Any?>) -> Unit,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): ChatRecommendData {
        val startedAt = System.nanoTime()
        meterRegistry.counter("chat_requests_total").increment()
        val message = request.message.trim()
        val sessionKey = chatMemoryService.resolveSessionKey(request)
        val profileKey = preferenceProfileService.resolveProfileKey(request)
        val memory = chatMemoryService.load(sessionKey)
        val experiment = chatExperimentService.assign(request, sessionKey)

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
            val safetyDecision = safetyPolicy.evaluateInput(message)
            if (safetyDecision.blocked) {
                route = "BLOCKED"
                meterRegistry.counter("chat_route_total", "route", route.lowercase()).increment()
                emitMeta(mapOf("route" to route, "route_reason" to "guardrails_blocked", "llm_used" to false))
                return@runCatching buildBlockedResponse(safetyDecision.reason ?: "요청을 처리할 수 없습니다.")
            }

            val slots = resolveSlots(request, memory)
            val retrievalKey = sha256(retrievalCacheKey(slots, message))
            val retrieval = chatCacheService.singleFlight("retrieval", retrievalKey) {
                chatCacheService.getRetrievalCache(retrievalKey)
                    ?: ragRetriever.searchItems(message, slots)
                        .also { chatCacheService.putRetrievalCache(retrievalKey, it, retrievalCacheTtlSeconds) }
            }
            ragMs = retrieval.retrievalMs
            preferenceProfileService.recordImplicitFeedback(profileKey, message)
            val llmAllowedByBudget = llmEnabled && llmBudgetController.allowLlm(message)
            val llmBudgetMode = llmBudgetController.modeLabel()

            val routeDecision = routingPolicy.decide(message, slots, retrieval.hits, llmAllowed = llmAllowedByBudget)
            route = toDebugRoute(routeDecision.type)
            recordRouteRate(routeDecision.type)

            when (routeDecision.type) {
                ChatRouteType.ASK_CLARIFICATION -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to false))
                    enrichResult(
                        response = buildClarificationResponse(slots, routeDecision, retrieval),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                        allowPlanner = false,
                    )
                }

                ChatRouteType.TEMPLATE -> {
                    meterRegistry.counter("llm_used_rate", "used", "false").increment()
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to false))
                    enrichResult(
                        response = buildTemplateResponse(
                            message = message,
                            slots = slots,
                            retrieval = retrieval,
                            followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
                            contextUsed = mapOf(
                                "route" to routeDecision.reason,
                                "embedding_used" to retrieval.usedEmbedding,
                                "llm_budget_mode" to llmBudgetMode,
                            ),
                        ),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                    )
                }

                ChatRouteType.LLM -> {
                    val selectedModel = experiment.model_override ?: modelRegistry.activeModel()
                    emitMeta(mapOf("route" to route, "route_reason" to routeDecision.reason, "llm_used" to true))

                    val promptCacheEnabled = safetyPolicy.cacheAllowed(message)
                    val promptCacheKey = buildPromptCacheKey(
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        memory = memory,
                        model = selectedModel,
                        promptVersion = experiment.prompt_version,
                    )
                    val semanticNamespace = semanticNamespace(slots, retrieval, selectedModel)

                    if (promptCacheEnabled) {
                        val cached = chatCacheService.getPromptCache(promptCacheKey)
                        if (cached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "false").increment()
                            emitToken(cached.assistant_text)
                            return@runCatching enrichResult(
                                response = cached.copy(
                                    llm_used = false,
                                    context_used = cached.context_used + mapOf("cache" to "prompt"),
                                ),
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                profileKey = profileKey,
                                memory = memory,
                                experiment = experiment,
                            )
                        }

                        val semanticCached = semanticCacheService.lookup(semanticNamespace, message)
                        if (semanticCached != null) {
                            meterRegistry.counter("llm_used_rate", "used", "false").increment()
                            emitToken(semanticCached.assistant_text)
                            return@runCatching enrichResult(
                                response = semanticCached.copy(llm_used = false),
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                profileKey = profileKey,
                                memory = memory,
                                experiment = experiment,
                            )
                        }
                    }

                    val llmResult = llmExecutionGate.run {
                        val llmResponse = llmClient.generateStream(
                            request = LlmGenerateRequest(
                                prompt = promptFactory.buildUserPrompt(
                                    request = request,
                                    slots = slots,
                                    hits = retrieval.hits.take(6),
                                    memory = memory,
                                    promptVersion = experiment.prompt_version,
                                ),
                                systemPrompt = promptFactory.buildSystemPrompt(experiment.prompt_version),
                                model = selectedModel,
                            ),
                            onChunk = { chunk -> emitToken(chunk) },
                            cancelSignal = isCancelled,
                        )

                        model = llmResponse.model
                        llmMs += llmResponse.elapsedMs

                        parseStructuredOutputWithRepair(llmResponse.text)
                    }

                    if (llmResult.rejected || llmResult.value == null) {
                        llmBudgetController.recordOutcome(
                            attempted = true,
                            queueWaitMs = llmResult.queueWaitMs,
                            llmElapsedMs = 0L,
                            rejected = true,
                            timeout = false,
                        )
                        meterRegistry.counter("chat_llm_fail_total", "reason", "queue_rejected").increment()
                        meterRegistry.counter("llm_used_rate", "used", "false").increment()
                        return@runCatching enrichResult(
                            response = buildTemplateResponse(
                                message = message,
                                slots = slots,
                                retrieval = retrieval,
                                followups = routingPolicy.defaultFollowups(slots),
                                contextUsed = mapOf(
                                    "route" to "queue_rejected_fallback",
                                    "embedding_used" to retrieval.usedEmbedding,
                                    "llm_budget_mode" to llmBudgetMode,
                                ),
                            ),
                            message = message,
                            slots = slots,
                            retrieval = retrieval,
                            profileKey = profileKey,
                            memory = memory,
                            experiment = experiment,
                        )
                    }

                    val normalized = normalizeStructuredResult(
                        structured = llmResult.value,
                        retrieval = retrieval,
                        slots = slots,
                    )
                    val verifiedNormalized = citationVerifier.verifyOrMitigate(normalized)
                    if (promptCacheEnabled) {
                        chatCacheService.putPromptCache(promptCacheKey, verifiedNormalized, promptCacheTtlSeconds)
                        semanticCacheService.put(semanticNamespace, message, verifiedNormalized, promptCacheTtlSeconds)
                    }
                    llmBudgetController.recordOutcome(
                        attempted = true,
                        queueWaitMs = llmResult.queueWaitMs,
                        llmElapsedMs = llmMs,
                        rejected = false,
                        timeout = false,
                    )
                    meterRegistry.counter("llm_used_rate", "used", "true").increment()
                    enrichResult(
                        response = verifiedNormalized.copy(
                            context_used = verifiedNormalized.context_used + mapOf("llm_budget_mode" to llmBudgetMode),
                        ),
                        message = message,
                        slots = slots,
                        retrieval = retrieval,
                        profileKey = profileKey,
                        memory = memory,
                        experiment = experiment,
                    )
                }
            }
        }.getOrElse { ex ->
            if (ex is CancellationException) {
                throw ex
            }
            if (isTimeoutFailure(ex)) {
                llmBudgetController.recordOutcome(
                    attempted = true,
                    queueWaitMs = 0L,
                    llmElapsedMs = 0L,
                    rejected = false,
                    timeout = true,
                )
            }
            meterRegistry.counter("chat_llm_fail_total", "reason", "fallback").increment()
            if (ex is StructuredRepairFailedException) {
                meterRegistry.counter("fallback_due_to_parse_rate", "route", "stream").increment()
            }
            val slots = resolveSlots(request, memory)
            val fallbackRetrieval = runCatching {
                ragRetriever.searchItems(request.message, slots)
            }.getOrElse {
                RagSearchResult(emptyList(), retrievalMs = 0L, usedEmbedding = false)
            }
            ragMs = fallbackRetrieval.retrievalMs
            enrichResult(
                response = buildTemplateResponse(
                    message = message,
                    slots = slots,
                    retrieval = fallbackRetrieval,
                    followups = routingPolicy.defaultFollowups(slots),
                    contextUsed = mapOf(
                        "route" to "exception_fallback",
                        "error_type" to ex.javaClass.simpleName,
                    ),
                ),
                message = message,
                slots = slots,
                retrieval = fallbackRetrieval,
                profileKey = profileKey,
                memory = memory,
                experiment = experiment,
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

        val guarded = safetyPolicy.enforceOutputPolicy(result)
        val verified = citationVerifier.verifyOrMitigate(guarded)
        val finalized = verified.copy(debug = debug)
        recordExperimentOutcome(experiment, finalized, totalMs)
        persistTurnIfNeeded(sessionKey, message, finalized, route)
        if (route != "BLOCKED") {
            chatShadowService.submit(
                request = request,
                primaryResponse = finalized,
                routePrimary = route,
                modelPrimary = model,
                promptVersion = experiment.prompt_version,
            )
        }
        return finalized
    }

    private fun buildPromptCacheKey(
        message: String,
        slots: ChatSlots,
        retrieval: RagSearchResult,
        memory: ChatMemorySnapshot,
        model: String,
        promptVersion: String?,
    ): String {
        return sha256(
            buildString {
                append("v4|")
                append(model)
                append('|')
                append(message.lowercase())
                append('|')
                append(slots.city ?: "-")
                append('|')
                append(slots.intent)
                append('|')
                append(promptVersion ?: "-")
                append('|')
                append(memory.state)
                append('|')
                append(memory.turnCount)
                append('|')
                append(sha256(memory.runningSummary.takeLast(240)))
                append('|')
                append(retrieval.hits.take(4).joinToString(";") { it.document.docId })
            },
        )
    }

    private fun semanticNamespace(slots: ChatSlots, retrieval: RagSearchResult, model: String): String {
        return buildString {
            append(model)
            append('|')
            append(slots.city ?: "-")
            append('|')
            append(slots.intent)
            append('|')
            append(retrieval.hits.take(3).joinToString(";") { it.document.docId })
        }
    }

    private fun resolveSlots(request: ChatRecommendRequest, memory: ChatMemorySnapshot): ChatSlots {
        val slots = routingPolicy.extractSlots(request)
        if (slots.city != null) {
            return slots
        }
        val inferredCity = inferCityFromMemory(memory.runningSummary) ?: return slots
        return slots.copy(city = inferredCity)
    }

    private fun inferCityFromMemory(summary: String): String? {
        if (summary.isBlank()) {
            return null
        }
        val normalized = summary.lowercase()
        val cityMentions = listOf(
            "Seoul" to listOf("seoul", "서울"),
            "Busan" to listOf("busan", "부산"),
            "Jeju" to listOf("jeju", "제주"),
            "Incheon" to listOf("incheon", "인천"),
        )
        var bestCity: String? = null
        var bestIndex = -1
        cityMentions.forEach { (city, keywords) ->
            keywords.forEach { keyword ->
                val index = normalized.lastIndexOf(keyword.lowercase())
                if (index > bestIndex) {
                    bestIndex = index
                    bestCity = city
                }
            }
        }
        return bestCity
    }

    private fun enrichResult(
        response: ChatRecommendData,
        message: String,
        slots: ChatSlots,
        retrieval: RagSearchResult,
        profileKey: String,
        memory: ChatMemorySnapshot,
        experiment: ChatExperimentAssignment,
        allowPlanner: Boolean = true,
    ): ChatRecommendData {
        val rerankedCards = preferenceProfileService.rerank(profileKey, message, response.cards)
        val rerankedSources = rerankedCards
            .flatMap { card -> if (card.source.isNotEmpty()) card.source else card.sources }
            .distinctBy { it.doc_id }
            .ifEmpty { response.sources }
        val itinerary = if (allowPlanner && routingPolicy.needsItinerary(message)) {
            itineraryPlannerService.plan(
                cards = rerankedCards,
                fallbackHits = retrieval.hits,
                days = slots.days,
            )
        } else {
            emptyList()
        }

        val enrichedContext = response.context_used + mapOf(
            "memory_state" to memory.state,
            "memory_turn_count" to memory.turnCount,
            "pref_profile_applied" to (profileKey != "anon"),
            "experiment_bucket" to experiment.bucket,
            "experiment_prompt_version" to experiment.prompt_version,
        ) + when {
            itinerary.isNotEmpty() -> mapOf(
                "planner_mode" to "document_grounded",
                "itinerary_item_count" to itinerary.size,
            )

            else -> emptyMap()
        }

        return response.copy(
            cards = rerankedCards,
            itinerary = itinerary,
            sources = rerankedSources,
            context_used = enrichedContext,
        )
    }

    private fun persistTurnIfNeeded(
        sessionKey: String,
        message: String,
        response: ChatRecommendData,
        route: String,
    ) {
        if (route == "BLOCKED") {
            return
        }
        runCatching {
            chatMemoryService.appendTurn(
                sessionKey = sessionKey,
                userMessage = message,
                assistantMessage = response.assistant_text,
            )
        }.onFailure {
            meterRegistry.counter("chat_memory_total", "result", "append_error").increment()
        }
    }

    private fun recordExperimentOutcome(
        assignment: ChatExperimentAssignment,
        response: ChatRecommendData,
        totalMs: Long,
    ) {
        if (assignment.bucket == "OFF") {
            return
        }

        meterRegistry.timer("chat_experiment_latency_ms", "bucket", assignment.bucket)
            .record(Duration.ofMillis(totalMs.coerceAtLeast(1)))
        if (response.cards.isEmpty()) {
            meterRegistry.counter("chat_experiment_zero_result_total", "bucket", assignment.bucket).increment()
        }
        val route = response.context_used["route"]?.toString().orEmpty()
        if (route.contains("fallback", ignoreCase = true)) {
            meterRegistry.counter("chat_experiment_fallback_total", "bucket", assignment.bucket).increment()
        }
    }

    private fun isTimeoutFailure(ex: Throwable): Boolean {
        if (ex is LlmSoftTimeoutException) return true
        if (ex is LlmUnavailableException) {
            return ex.message?.contains("timeout", ignoreCase = true) == true
        }
        return false
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
        retrieval: RagSearchResult,
    ): ChatRecommendData {
        val noDataMessage = buildCategoryNoDataMessage(slots, routeDecision, retrieval)
        val text = if (slots.city == null) {
            "도시 정보가 필요합니다. 목적지를 알려주시면 숙소, 티켓, 주변 추천을 바로 정리해 드릴게요."
        } else if (noDataMessage != null) {
            noDataMessage
        } else {
            "조건을 조금만 더 알려주시면 맞춤 추천 정확도를 높일 수 있어요."
        }

        val noDataContext = if (noDataMessage != null) {
            mapOf(
                "no_data_reason" to "poi_category_insufficient",
                "no_data_city" to (slots.city ?: ""),
                "no_data_categories" to retrieval.requestedPoiCategories.toList(),
            )
        } else {
            emptyMap()
        }

        return ChatRecommendData(
            answer = text,
            assistant_text = text,
            cards = emptyList(),
            followups = routeDecision.followups.ifEmpty { routingPolicy.defaultFollowups(slots) },
            context_used = mapOf("route" to routeDecision.reason) + noDataContext,
            llm_used = false,
            sources = emptyList(),
        )
    }

    private fun buildCategoryNoDataMessage(
        slots: ChatSlots,
        routeDecision: ChatRouteDecision,
        retrieval: RagSearchResult,
    ): String? {
        if (routeDecision.reason != "insufficient_sources") {
            return null
        }
        if (slots.city == null || retrieval.hits.isNotEmpty()) {
            return null
        }
        if ("POI" !in retrieval.sourceTypes || retrieval.requestedPoiCategories.isEmpty()) {
            return null
        }
        val categories = retrieval.requestedPoiCategories
            .map { poiCategoryLabel(it) }
            .distinct()
        val categoryLabel = categories.joinToString(", ")
        return "${slots.city}의 ${categoryLabel} 카테고리는 현재 추천 데이터가 부족합니다. 다른 카테고리나 조건으로 다시 요청해 주세요."
    }

    private fun poiCategoryLabel(category: String): String {
        return when (category.lowercase()) {
            "food" -> "맛집"
            "shopping" -> "쇼핑"
            "museum" -> "전시"
            "attraction" -> "관광"
            else -> category
        }
    }

    private fun buildTemplateResponse(
        message: String,
        slots: ChatSlots,
        retrieval: RagSearchResult,
        followups: List<String>,
        contextUsed: Map<String, Any?>,
    ): ChatRecommendData {
        val singleRecommendationRequested = wantsSingleRecommendation(message)
        val cardLimit = if (singleRecommendationRequested) 1 else 4
        val cards = retrieval.hits
            .take(cardLimit)
            .map { hit -> toCardFromHit(hit) }
            .distinctBy { card -> card.id ?: card.title }
            .let { safetyPolicy.filterCardsWithSource(it) }

        val sources = retrieval.hits
            .take(cardLimit)
            .map { hit -> hit.document.toSource() }
            .ifEmpty {
                cards.flatMap { it.source }.take(2)
            }

        val answer = if (cards.isEmpty()) {
            "현재 조건으로는 신뢰 가능한 추천 근거가 부족합니다. 도시와 일정을 조금 더 구체적으로 입력해 주세요."
        } else {
            buildTemplateAnswer(slots, cards, singleRecommendationRequested)
        }

        return ChatRecommendData(
            answer = answer,
            assistant_text = answer,
            cards = cards,
            followups = followups.take(2),
            context_used = contextUsed + mapOf(
                "sources" to sources.map { it.doc_id },
                "rag_ms" to retrieval.retrievalMs,
                "requested_card_limit" to cardLimit,
            ),
            llm_used = false,
            sources = sources,
        )
    }

    private fun buildTemplateAnswer(
        slots: ChatSlots,
        cards: List<ChatCard>,
        singleRecommendationRequested: Boolean,
    ): String {
        val cityLabel = slots.city ?: "요청하신 조건"
        val dayHint = slots.days?.let { "${it}일 일정 기준으로" } ?: "기준으로"
        if (singleRecommendationRequested) {
            val topCardTitle = cards.firstOrNull()?.title?.trim().orEmpty()
            if (topCardTitle.isNotEmpty()) {
                return "$cityLabel $dayHint 딱 한 곳만 고르면 ${topCardTitle}을(를) 추천해요. 카드에서 상세를 확인해 보세요."
            }
            return "$cityLabel $dayHint 단일 추천 후보를 정리했어요. 카드에서 상세를 확인해 보세요."
        }

        val highlights = cards
            .take(2)
            .map { it.title.trim() }
            .filter { it.isNotBlank() }
        val mix = cards
            .groupingBy { it.type.uppercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .joinToString(", ") { (type, count) -> "${cardTypeLabel(type)} ${count}개" }

        return buildString {
            append(cityLabel)
            append(" ")
            append(dayHint)
            append(" 추천 후보를 정리했어요.")
            if (highlights.isNotEmpty()) {
                append(" 우선 ")
                append(highlights.joinToString(", "))
                append("을(를) 확인해 보세요.")
            }
            if (mix.isNotBlank()) {
                append(" 이번 결과는 ")
                append(mix)
                append("로 구성됐어요.")
            }
            append(" 카드에서 상세를 비교하고 바로 진행하실 수 있어요.")
        }
    }

    private fun wantsSingleRecommendation(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("1곳") ||
            normalized.contains("한곳") ||
            normalized.contains("한 곳") ||
            normalized.contains("하나만") ||
            normalized.contains("딱 한")
    }

    private fun cardTypeLabel(type: String): String {
        return when (type.uppercase()) {
            "PROPERTY" -> "숙소"
            "TICKET" -> "티켓"
            "PACKAGE" -> "패키지"
            "POI" -> "주변 스팟"
            else -> "추천"
        }
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
        val sourceTypePart = if (slots.sourceTypes.isEmpty()) "-" else slots.sourceTypes.sorted().joinToString(",")
        return "retrieval_v4|${slots.city ?: "-"}|${slots.intent}|$sourceTypePart|$normalized"
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
