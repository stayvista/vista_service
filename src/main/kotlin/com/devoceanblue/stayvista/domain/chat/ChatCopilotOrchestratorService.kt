package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.web.RequestIdContext
import com.devoceanblue.stayvista.domain.catalog.CatalogService
import com.devoceanblue.stayvista.domain.catalog.PropertyDetail
import com.devoceanblue.stayvista.domain.catalog.RoomTypeListData
import com.devoceanblue.stayvista.domain.search.CityCanonicalizer
import com.devoceanblue.stayvista.domain.search.PriceCalendarData
import com.devoceanblue.stayvista.domain.search.PriceCalendarRequest
import com.devoceanblue.stayvista.domain.search.PriceCalendarService
import com.devoceanblue.stayvista.domain.search.SearchData
import com.devoceanblue.stayvista.domain.search.SearchRequest
import com.devoceanblue.stayvista.domain.search.SearchService
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChatCopilotOrchestratorService(
    private val searchService: SearchService,
    private val priceCalendarService: PriceCalendarService,
    private val catalogService: CatalogService,
    private val safetyPolicy: ChatSafetyPolicy,
    private val meterRegistry: MeterRegistry,
) {
    fun orchestrate(request: ChatCopilotOrchestrateRequest): ChatCopilotOrchestrateData {
        val startedAt = System.nanoTime()
        val requestId = RequestIdContext.current()
        val traceId = requestId
        val normalizedSession = normalizeSession(request.session_state, request.message)
        val searchLimit = request.limit.coerceIn(1, 10)
        val toolTrace = mutableListOf<ChatCopilotToolTrace>()

        val searchRequest = buildSearchRequest(request.message, normalizedSession, searchLimit)
        val searchResult = invokeTool(
            tool = "search_properties",
            trace = toolTrace,
            traceId = traceId,
        ) {
            val result = searchService.search(searchRequest)
            result to mapOf("item_count" to result.items.size)
        }

        val calendarPlaceId = normalizedSession.place_id ?: normalizedSession.destination
        val calendarResult = if (calendarPlaceId.isNullOrBlank()) {
            markToolSkipped(
                tool = "get_price_calendar",
                reason = "missing_place",
                trace = toolTrace,
                traceId = traceId,
            )
            null
        } else {
            invokeTool(
                tool = "get_price_calendar",
                trace = toolTrace,
                traceId = traceId,
            ) {
                val dateRange = normalizedSession.date_range ?: defaultDateRange()
                val fromDate = parseLocalDate(dateRange.check_in)
                    ?: throw IllegalArgumentException("invalid_check_in")
                val toDate = parseLocalDate(dateRange.check_out)
                    ?: throw IllegalArgumentException("invalid_check_out")
                val guests = normalizedSession.guests
                val result = priceCalendarService.calendar(
                    PriceCalendarRequest(
                        place_id = calendarPlaceId,
                        from = fromDate,
                        to = toDate,
                        currency = normalizedSession.budget?.currency ?: "KRW",
                        rooms = guests.rooms,
                        adults = guests.adults,
                        children = guests.children,
                        children_ages = guests.children_ages,
                    ),
                )
                result to mapOf("available_days" to result.days.count { it.available })
            }
        }

        val primaryPropertyId = searchResult?.items?.firstOrNull()?.property_id
        val propertyDetail = if (primaryPropertyId == null) {
            markToolSkipped(
                tool = "get_property_detail",
                reason = "missing_property_from_search",
                trace = toolTrace,
                traceId = traceId,
            )
            null
        } else {
            invokeTool(
                tool = "get_property_detail",
                trace = toolTrace,
                traceId = traceId,
            ) {
                val result = catalogService.getProperty(primaryPropertyId)
                result to mapOf(
                    "property_id" to primaryPropertyId,
                    "district" to result.district_name,
                )
            }
        }

        val availabilityResult = if (primaryPropertyId == null || normalizedSession.date_range == null) {
            val reason = when {
                primaryPropertyId == null -> "missing_property_from_search"
                normalizedSession.date_range == null -> "missing_date_range"
                else -> "unavailable_context"
            }
            markToolSkipped(
                tool = "check_availability",
                reason = reason,
                trace = toolTrace,
                traceId = traceId,
            )
            null
        } else {
            invokeTool(
                tool = "check_availability",
                trace = toolTrace,
                traceId = traceId,
            ) {
                val range = normalizedSession.date_range
                val checkIn = parseLocalDate(range.check_in)
                    ?: throw IllegalArgumentException("invalid_check_in")
                val checkOut = parseLocalDate(range.check_out)
                    ?: throw IllegalArgumentException("invalid_check_out")
                val result = catalogService.listRoomTypes(
                    propertyId = primaryPropertyId,
                    checkIn = checkIn,
                    checkOut = checkOut,
                    rooms = normalizedSession.guests.rooms,
                )
                result to mapOf(
                    "available_room_types" to result.items.count { it.is_available == true },
                    "total_room_types" to result.items.size,
                )
            }
        }

        val response = buildResponse(
            message = request.message,
            session = normalizedSession,
            search = searchResult,
            calendar = calendarResult,
            property = propertyDetail,
            availability = availabilityResult,
            trace = toolTrace,
            requestId = requestId,
            traceId = traceId,
        )

        val guarded = safetyPolicy.enforceCopilotOutputPolicy(response)
        val latency = Duration.ofNanos(System.nanoTime() - startedAt)
        meterRegistry.timer("chat_copilot_orchestrator_latency_ms").record(latency)
        meterRegistry.counter(
            "chat_copilot_orchestrator_requests_total",
            "result",
            if (guarded.degraded) "degraded" else "success",
        ).increment()
        if (guarded.degraded) {
            meterRegistry.counter("chat_copilot_orchestrator_degraded_total").increment()
        }
        if (searchResult?.items.isNullOrEmpty()) {
            meterRegistry.counter("chat_copilot_orchestrator_no_result_total").increment()
        }
        meterRegistry.summary("chat_copilot_orchestrator_confidence").record(guarded.confidence)
        logger.info(
            "chat_copilot_orchestrate done request_id={} trace_id={} degraded={} confidence={} tools={}",
            requestId,
            traceId,
            guarded.degraded,
            guarded.confidence,
            guarded.tool_trace.joinToString(",") { "${it.tool}:${it.status}" },
        )
        return guarded
    }

    private fun buildResponse(
        message: String,
        session: ChatCopilotSessionState,
        search: SearchData?,
        calendar: PriceCalendarData?,
        property: PropertyDetail?,
        availability: RoomTypeListData?,
        trace: List<ChatCopilotToolTrace>,
        requestId: String,
        traceId: String,
    ): ChatCopilotOrchestrateData {
        val searchItems = search?.items.orEmpty()
        val availabilityCount = availability?.items?.count { it.is_available == true } ?: 0
        val degraded = searchItems.isEmpty() || trace.any { it.status == "failed" }

        if (searchItems.isEmpty()) {
            val fallbackEvidence = ChatCopilotEvidence(
                subject = "검색 결과 없음",
                why_recommended = listOf("현재 조건으로 조회 가능한 숙소를 찾지 못했습니다."),
                cautions = listOf("도시/일정/예산 중 하나를 완화해 다시 확인해 주세요."),
                source_refs = listOf(
                    ChatCopilotSourceRef(
                        source_type = "search_properties",
                        source_id = "none",
                        title = "검색 결과",
                        value = "item_count=0",
                    ),
                ),
            )
            return ChatCopilotOrchestrateData(
                answer = "조건에 맞는 숙소를 찾지 못했어요. 날짜 또는 예산을 조정하면 결과를 다시 찾아볼게요.",
                actions = listOf(
                    ChatCopilotAction(
                        type = "retry_with_patch",
                        label = "조건 완화 후 다시 검색",
                        payload = mapOf(
                            "patch" to mapOf(
                                "check_in" to session.date_range?.check_in?.toString(),
                                "check_out" to session.date_range?.check_out?.toString(),
                                "max_price" to session.budget?.max_price?.let { it + (it / 5) },
                            ),
                        ),
                    ),
                    ChatCopilotAction(
                        type = "apply_filters",
                        label = "현재 조건 유지",
                        payload = buildFilterPayload(session),
                    ),
                ),
                evidence = listOf(fallbackEvidence),
                confidence = 0.28,
                session_state = session,
                tool_trace = trace,
                degraded = true,
                request_id = requestId,
                trace_id = traceId,
            )
        }

        val topProperty = searchItems.first()
        val evidence = searchItems.take(3).map { item ->
            val why = mutableListOf<String>()
            why += "${item.city ?: "도시 미지정"} 기준 최저가 ${formatMoney(item.price_min, item.currency)}입니다."
            why += "투숙객 평점 ${formatScore(item.rating)} · 위치 평점 ${formatScore(item.location_rating)} 기준 상위권입니다."
            if (property?.property_id == item.property_id && !property.district_name.isNullOrBlank()) {
                why += "${property.district_name} 권역에 있어 이동 동선이 간결합니다."
            }

            val cautions = mutableListOf<String>()
            if (availability != null && availabilityCount == 0 && item.property_id == topProperty.property_id) {
                cautions += "선택 일정의 잔여 객실이 부족할 수 있어 확인이 필요합니다."
            } else {
                cautions += "재고와 최종 요금은 결제 직전에 다시 확인됩니다."
            }
            val minCalendarPrice = calendar?.days?.mapNotNull { it.min_price }?.minOrNull()
            if (minCalendarPrice != null) {
                cautions += "날짜별 요금 변동이 있어 최저가 날짜와 선택 날짜 가격은 다를 수 있습니다."
            }

            val refs = mutableListOf(
                ChatCopilotSourceRef(
                    source_type = "search_properties",
                    source_id = "property:${item.property_id}",
                    title = "검색 결과",
                    value = "price=${item.price_min},rating=${formatScore(item.rating)}",
                ),
            )
            if (property?.property_id == item.property_id) {
                refs += ChatCopilotSourceRef(
                    source_type = "get_property_detail",
                    source_id = "property:${property.property_id}",
                    title = "숙소 상세",
                    value = property.address1,
                )
            }
            if (minCalendarPrice != null) {
                refs += ChatCopilotSourceRef(
                    source_type = "get_price_calendar",
                    source_id = calendar.place_id,
                    title = "가격 캘린더",
                    value = "min_price=${formatMoney(minCalendarPrice, calendar.currency)}",
                )
            }
            if (availability != null && item.property_id == topProperty.property_id) {
                refs += ChatCopilotSourceRef(
                    source_type = "check_availability",
                    source_id = "property:${item.property_id}",
                    title = "재고 조회",
                    value = "available_room_types=$availabilityCount",
                )
            }

            ChatCopilotEvidence(
                subject = item.name,
                why_recommended = why,
                cautions = cautions,
                source_refs = refs,
            )
        }

        val confidence = calculateConfidence(
            search = searchItems,
            property = property,
            calendar = calendar,
            availability = availability,
            degraded = degraded,
        )

        val answer = buildString {
            append("${session.destination ?: topProperty.city ?: "현재 조건"} 기준으로 후보를 정리했어요. ")
            append("우선 ${topProperty.name}을(를) 확인해 보세요.")
            if (availability != null) {
                if (availabilityCount > 0) {
                    append(" 선택 일정 기준 예약 가능한 객실이 확인되었습니다.")
                } else {
                    append(" 선택 일정 기준 재고는 확인이 필요해 대체 옵션을 함께 제안합니다.")
                }
            } else {
                append(" 재고는 결제 단계에서 다시 확인할 수 있어요.")
            }
        }

        val actions = mutableListOf(
            ChatCopilotAction(
                type = "apply_filters",
                label = "조건으로 숙소 검색",
                payload = buildFilterPayload(session),
            ),
            ChatCopilotAction(
                type = "open_property",
                label = "${topProperty.name} 상세 보기",
                payload = mapOf("property_id" to topProperty.property_id),
            ),
        )
        if (degraded || availabilityCount == 0) {
            actions += ChatCopilotAction(
                type = "retry_with_patch",
                label = "대체 날짜/필터로 다시 추천",
                payload = mapOf(
                            "patch" to mapOf(
                                "check_in" to shiftDate(session.date_range?.check_in, 1),
                                "check_out" to shiftDate(session.date_range?.check_out, 1),
                                "max_price" to session.budget?.max_price?.let { it + (it / 10) },
                            ),
                            "reason" to if (availabilityCount == 0) "inventory_uncertain" else "tool_degraded",
                ),
            )
        }

        return ChatCopilotOrchestrateData(
            answer = answer,
            actions = actions,
            evidence = evidence,
            confidence = confidence,
            session_state = session,
            tool_trace = trace,
            degraded = degraded,
            request_id = requestId,
            trace_id = traceId,
        )
    }

    private fun calculateConfidence(
        search: List<com.devoceanblue.stayvista.domain.search.SearchItem>,
        property: PropertyDetail?,
        calendar: PriceCalendarData?,
        availability: RoomTypeListData?,
        degraded: Boolean,
    ): Double {
        var score = 0.35
        if (search.isNotEmpty()) score += 0.25
        if (property != null) score += 0.15
        if (calendar != null) score += 0.1
        if (availability != null) {
            score += if (availability.items.any { it.is_available == true }) 0.1 else -0.05
        }
        if (degraded) score -= 0.15
        return ((score.coerceIn(0.1, 0.92) * 100.0).roundToInt() / 100.0)
    }

    private fun buildFilterPayload(session: ChatCopilotSessionState): Map<String, Any?> {
        return mapOf(
            "city" to session.destination,
            "place_id" to session.place_id,
            "check_in" to session.date_range?.check_in,
            "check_out" to session.date_range?.check_out,
            "rooms" to session.guests.rooms,
            "adults" to session.guests.adults,
            "children" to session.guests.children,
            "children_ages" to session.guests.children_ages,
            "currency" to (session.budget?.currency ?: "KRW"),
            "min_price" to session.budget?.min_price,
            "max_price" to session.budget?.max_price,
            "stars" to session.preferences.stars,
            "amenities" to session.preferences.amenities,
            "property_type" to session.preferences.property_type,
            "districts" to session.preferences.districts,
            "payment_options" to session.preferences.payment_options,
            "themes" to session.preferences.themes,
            "brands" to session.preferences.brands,
            "bed_types" to session.preferences.bed_types,
            "nearby_attractions" to session.preferences.nearby_attractions,
            "family_options" to session.preferences.family_options,
            "beach_options" to session.preferences.beach_options,
            "sort" to (session.constraints.sort ?: "best_match"),
            "min_guest_rating" to session.constraints.min_guest_rating,
            "min_location_rating" to session.constraints.min_location_rating,
            "max_distance_m" to session.constraints.max_distance_m,
            "bedrooms" to session.constraints.bedrooms,
        )
    }

    private fun buildSearchRequest(
        message: String,
        session: ChatCopilotSessionState,
        limit: Int,
    ): SearchRequest {
        return SearchRequest(
            q = message.trim().takeIf { it.length in 2..80 },
            city = session.destination,
            place_id = session.place_id,
            check_in = session.date_range?.check_in,
            check_out = session.date_range?.check_out,
            adults = session.guests.adults,
            children = session.guests.children,
            rooms = session.guests.rooms,
            children_ages = session.guests.children_ages,
            currency = session.budget?.currency ?: "KRW",
            min_price = session.budget?.min_price,
            max_price = session.budget?.max_price,
            min_rating = null,
            sort = session.constraints.sort ?: "best_match",
            cursor = null,
            limit = limit,
            min_guest_rating = session.constraints.min_guest_rating,
            min_location_rating = session.constraints.min_location_rating,
            max_distance_m = session.constraints.max_distance_m,
            stars = session.preferences.stars,
            amenities = session.preferences.amenities,
            property_type = session.preferences.property_type,
            districts = session.preferences.districts,
            payment_options = session.preferences.payment_options,
            themes = session.preferences.themes,
            brands = session.preferences.brands,
            bed_types = session.preferences.bed_types,
            bedrooms = session.constraints.bedrooms,
            nearby_attractions = session.preferences.nearby_attractions,
            family_options = session.preferences.family_options,
            beach_options = session.preferences.beach_options,
        )
    }

    private fun normalizeSession(
        input: ChatCopilotSessionState,
        message: String,
    ): ChatCopilotSessionState {
        val inferredDestination = inferDestination(message)
        val destination = CityCanonicalizer.canonicalize(input.destination ?: inferredDestination)
        val dateRange = normalizeDateRange(input.date_range)
        val guests = input.guests.let { guest ->
            val normalizedChildren = guest.children.coerceIn(0, 8)
            ChatCopilotGuests(
                rooms = guest.rooms.coerceIn(1, 8),
                adults = guest.adults.coerceIn(1, 16),
                children = normalizedChildren,
                children_ages = guest.children_ages.map { it.coerceIn(0, 17) }.take(normalizedChildren),
            )
        }
        val budget = input.budget?.let {
            ChatCopilotBudget(
                min_price = it.min_price?.coerceAtLeast(0L),
                max_price = it.max_price?.coerceAtLeast(0L),
                currency = it.currency.trim().uppercase(Locale.ROOT).ifBlank { "KRW" },
            )
        }

        return ChatCopilotSessionState(
            destination = destination,
            place_id = input.place_id?.trim()?.takeIf { it.isNotBlank() } ?: destination,
            date_range = dateRange,
            guests = guests,
            budget = budget,
            preferences = normalizePreferences(input.preferences),
            constraints = normalizeConstraints(input.constraints),
        )
    }

    private fun normalizePreferences(preferences: ChatCopilotPreferences): ChatCopilotPreferences {
        fun normalizeStrings(values: List<String>): List<String> {
            return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        }
        return ChatCopilotPreferences(
            amenities = normalizeStrings(preferences.amenities),
            property_type = normalizeStrings(preferences.property_type),
            districts = normalizeStrings(preferences.districts),
            payment_options = normalizeStrings(preferences.payment_options),
            themes = normalizeStrings(preferences.themes),
            brands = normalizeStrings(preferences.brands),
            bed_types = normalizeStrings(preferences.bed_types),
            stars = preferences.stars.map { it.coerceIn(1, 5) }.distinct().sorted(),
            nearby_attractions = preferences.nearby_attractions.filter { it > 0 }.distinct(),
            family_options = normalizeStrings(preferences.family_options),
            beach_options = normalizeStrings(preferences.beach_options),
        )
    }

    private fun normalizeConstraints(constraints: ChatCopilotConstraints): ChatCopilotConstraints {
        return ChatCopilotConstraints(
            sort = constraints.sort?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() },
            min_guest_rating = constraints.min_guest_rating?.coerceIn(0.0, 10.0),
            min_location_rating = constraints.min_location_rating?.coerceIn(0.0, 10.0),
            max_distance_m = constraints.max_distance_m?.coerceIn(100, 100_000),
            bedrooms = constraints.bedrooms?.coerceIn(1, 8),
        )
    }

    private fun normalizeDateRange(range: ChatCopilotDateRange?): ChatCopilotDateRange? {
        range ?: return null
        val checkIn = runCatching { LocalDate.parse(range.check_in) }.getOrNull() ?: return null
        val checkOut = runCatching { LocalDate.parse(range.check_out) }.getOrNull() ?: return null
        if (!checkOut.isAfter(checkIn)) {
            return null
        }
        return ChatCopilotDateRange(
            check_in = checkIn.toString(),
            check_out = checkOut.toString(),
        )
    }

    private fun defaultDateRange(): ChatCopilotDateRange {
        val start = LocalDate.now().plusDays(1)
        return ChatCopilotDateRange(
            check_in = start.toString(),
            check_out = start.plusDays(2).toString(),
        )
    }

    private fun inferDestination(message: String): String? {
        return KNOWN_CITY_ALIASES.firstOrNull { alias ->
            message.contains(alias, ignoreCase = true)
        }?.let { CityCanonicalizer.canonicalize(it) }
    }

    private fun formatMoney(amount: Long, currency: String): String {
        val code = currency.uppercase(Locale.ROOT)
        val symbol = when (code) {
            "KRW" -> "₩"
            "USD" -> "$"
            "JPY" -> "¥"
            "EUR" -> "€"
            else -> "$code "
        }
        return "$symbol${"%,d".format(Locale.US, amount)}"
    }

    private fun formatScore(value: Double): String = String.format(Locale.US, "%.1f", value.coerceIn(0.0, 10.0))

    private fun <T> invokeTool(
        tool: String,
        trace: MutableList<ChatCopilotToolTrace>,
        traceId: String,
        block: () -> Pair<T, Map<String, Any?>>,
    ): T? {
        val startedAt = System.nanoTime()
        return runCatching(block).fold(
            onSuccess = { (value, detail) ->
                val tookMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(0)
                trace += ChatCopilotToolTrace(
                    tool = tool,
                    status = "success",
                    took_ms = tookMs,
                    detail = detail,
                )
                meterRegistry.counter("chat_copilot_orchestrator_tool_total", "tool", tool, "status", "success").increment()
                logger.info(
                    "chat_copilot_tool tool={} status=success took_ms={} request_id={} trace_id={} detail={}",
                    tool,
                    tookMs,
                    RequestIdContext.current(),
                    traceId,
                    detail,
                )
                value
            },
            onFailure = { ex ->
                val tookMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(0)
                trace += ChatCopilotToolTrace(
                    tool = tool,
                    status = "failed",
                    took_ms = tookMs,
                    detail = mapOf("error" to (ex.message ?: ex.javaClass.simpleName)),
                )
                meterRegistry.counter("chat_copilot_orchestrator_tool_total", "tool", tool, "status", "failed").increment()
                logger.warn(
                    "chat_copilot_tool tool={} status=failed took_ms={} request_id={} trace_id={} error={}",
                    tool,
                    tookMs,
                    RequestIdContext.current(),
                    traceId,
                    ex.message ?: ex.javaClass.simpleName,
                )
                null
            },
        )
    }

    private fun markToolSkipped(
        tool: String,
        reason: String,
        trace: MutableList<ChatCopilotToolTrace>,
        traceId: String,
    ) {
        trace += ChatCopilotToolTrace(
            tool = tool,
            status = "skipped",
            took_ms = 0,
            detail = mapOf("reason" to reason),
        )
        meterRegistry.counter("chat_copilot_orchestrator_tool_total", "tool", tool, "status", "skipped").increment()
        logger.info(
            "chat_copilot_tool tool={} status=skipped request_id={} trace_id={} reason={}",
            tool,
            RequestIdContext.current(),
            traceId,
            reason,
        )
    }

    private fun parseLocalDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }

    private fun shiftDate(value: String?, days: Long): String? {
        val parsed = parseLocalDate(value) ?: return null
        return parsed.plusDays(days).toString()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatCopilotOrchestratorService::class.java)
        private val KNOWN_CITY_ALIASES = listOf(
            "서울", "Seoul", "부산", "Busan", "제주", "Jeju", "인천", "Incheon",
            "대전", "Daejeon", "대구", "Daegu", "광주", "Gwangju", "울산", "Ulsan",
            "강릉", "Gangneung",
        )
    }
}
