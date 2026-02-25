package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.domain.catalog.CatalogService
import com.devoceanblue.stayvista.domain.catalog.Money
import com.devoceanblue.stayvista.domain.catalog.PropertyDetail
import com.devoceanblue.stayvista.domain.catalog.RoomTypeListData
import com.devoceanblue.stayvista.domain.catalog.RoomTypeSummary
import com.devoceanblue.stayvista.domain.search.PriceCalendarData
import com.devoceanblue.stayvista.domain.search.PriceCalendarDay
import com.devoceanblue.stayvista.domain.search.PriceCalendarMeta
import com.devoceanblue.stayvista.domain.search.PriceCalendarRequest
import com.devoceanblue.stayvista.domain.search.PriceCalendarService
import com.devoceanblue.stayvista.domain.search.SearchData
import com.devoceanblue.stayvista.domain.search.SearchItem
import com.devoceanblue.stayvista.domain.search.SearchRequest
import com.devoceanblue.stayvista.domain.search.SearchService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class ChatCopilotOrchestratorServiceTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val searchService = mock(SearchService::class.java)
    private val priceCalendarService = mock(PriceCalendarService::class.java)
    private val catalogService = mock(CatalogService::class.java)
    private val safetyPolicy = ChatSafetyPolicy(meterRegistry, PiiRedactor())
    private val service = ChatCopilotOrchestratorService(
        searchService = searchService,
        priceCalendarService = priceCalendarService,
        catalogService = catalogService,
        safetyPolicy = safetyPolicy,
        meterRegistry = meterRegistry,
    )

    @Test
    fun `orchestrate should keep deterministic tool sequence for same input`() {
        given(searchService.search(anySearchRequest())).willReturn(
            SearchData(
                items = listOf(
                    SearchItem(
                        property_id = 1001L,
                        name = "Harborline Seoul Hotel",
                        city = "Seoul",
                        district = "Gangnam",
                        price_min = 145000,
                        rating = 4.6,
                        location_rating = 4.4,
                        star_rating = 4,
                        review_count = 812,
                        thumbnail_url = "https://img.test/1001.jpg",
                        distance_m = 900,
                        currency = "KRW",
                    ),
                ),
            ),
        )
        given(priceCalendarService.calendar(anyPriceCalendarRequest())).willReturn(
            PriceCalendarData(
                place_id = "Seoul",
                place_type = "CITY",
                currency = "KRW",
                from = "2026-03-02",
                to = "2026-03-05",
                days = listOf(
                    PriceCalendarDay(
                        date = "2026-03-02",
                        min_price = 132000,
                        currency = "KRW",
                        available = true,
                    ),
                ),
                meta = PriceCalendarMeta(
                    nights = 2,
                    cache_ttl_seconds = 300,
                ),
            ),
        )
        given(catalogService.getProperty(1001L)).willReturn(
            PropertyDetail(
                property_id = 1001L,
                name = "Harborline Seoul Hotel",
                city = "Seoul",
                country = "KR",
                address1 = "Teheran-ro 10",
                lat = 37.5,
                lng = 127.0,
                status = "ACTIVE",
                rating = 4.6,
                thumbnail_url = "https://img.test/1001.jpg",
                district_name = "Gangnam",
                property_type_code = "HOTEL",
                property_type_label = "호텔",
                star_rating = 4,
                location_rating = 4.4,
                review_count = 812,
                beach_distance_m = null,
                is_beachfront = false,
                kid_free_stay = false,
                popularity_score = 120,
                brand_names = listOf("StayVista"),
                amenity_groups = emptyList(),
                payment_options = emptyList(),
                themes = emptyList(),
            ),
        )
        given(catalogService.listRoomTypes(anyLong(), anyLocalDate(), anyLocalDate(), anyInt())).willReturn(
            RoomTypeListData(
                items = listOf(
                    RoomTypeSummary(
                        room_type_id = 9001L,
                        name = "Deluxe Double",
                        max_guests = 2,
                        status = "ACTIVE",
                        base_price = Money("KRW", 145000),
                        bed_type = "DOUBLE",
                        view_type = "CITY",
                        bedrooms = 1,
                        available_rooms = 3,
                        is_available = true,
                    ),
                ),
            ),
        )

        val request = ChatCopilotOrchestrateRequest(
            message = "서울 2박 호텔 추천해줘",
            session_state = ChatCopilotSessionState(
                destination = "Seoul",
                date_range = ChatCopilotDateRange(
                    check_in = "2026-03-02",
                    check_out = "2026-03-04",
                ),
                guests = ChatCopilotGuests(rooms = 1, adults = 2, children = 0),
                budget = ChatCopilotBudget(max_price = 250000, currency = "KRW"),
            ),
            limit = 3,
        )

        val first = service.orchestrate(request)
        val second = service.orchestrate(request)

        assertEquals(
            listOf(
                "search_properties:success",
                "get_price_calendar:success",
                "get_property_detail:success",
                "check_availability:success",
            ),
            first.tool_trace.map { "${it.tool}:${it.status}" },
        )
        assertEquals(
            first.tool_trace.map { "${it.tool}:${it.status}" },
            second.tool_trace.map { "${it.tool}:${it.status}" },
        )
        assertFalse(first.degraded)
        assertTrue(first.actions.any { it.type == "apply_filters" })
        assertTrue(first.actions.any { it.type == "open_property" })
        assertNotNull(first.request_id)
        assertNotNull(first.trace_id)

        verify(searchService, times(2)).search(anySearchRequest())
        verify(priceCalendarService, times(2)).calendar(anyPriceCalendarRequest())
        verify(catalogService, times(2)).getProperty(1001L)
        verify(catalogService, times(2)).listRoomTypes(anyLong(), anyLocalDate(), anyLocalDate(), anyInt())
    }

    @Test
    fun `orchestrate should return degraded response with retry action when tool fails`() {
        given(searchService.search(anySearchRequest())).willReturn(
            SearchData(
                items = listOf(
                    SearchItem(
                        property_id = 2001L,
                        name = "Busan Bay Hotel",
                        city = "Busan",
                        district = "Haeundae",
                        price_min = 110000,
                        rating = 4.3,
                        location_rating = 4.2,
                        star_rating = 4,
                        review_count = 501,
                        thumbnail_url = null,
                        distance_m = 1300,
                        currency = "KRW",
                    ),
                ),
            ),
        )
        given(priceCalendarService.calendar(anyPriceCalendarRequest())).willThrow(RuntimeException("calendar timeout"))
        given(catalogService.getProperty(2001L)).willReturn(
            PropertyDetail(
                property_id = 2001L,
                name = "Busan Bay Hotel",
                city = "Busan",
                country = "KR",
                address1 = "Marine City",
                lat = 35.15,
                lng = 129.15,
                status = "ACTIVE",
                rating = 4.3,
                thumbnail_url = null,
                district_name = "Haeundae",
                property_type_code = "HOTEL",
                property_type_label = "호텔",
                star_rating = 4,
                location_rating = 4.2,
                review_count = 501,
                beach_distance_m = 320,
                is_beachfront = false,
                kid_free_stay = false,
                popularity_score = 100,
                brand_names = emptyList(),
                amenity_groups = emptyList(),
                payment_options = emptyList(),
                themes = emptyList(),
            ),
        )
        given(catalogService.listRoomTypes(anyLong(), anyLocalDate(), anyLocalDate(), anyInt())).willReturn(
            RoomTypeListData(
                items = listOf(
                    RoomTypeSummary(
                        room_type_id = 9901L,
                        name = "Standard Twin",
                        max_guests = 2,
                        status = "ACTIVE",
                        base_price = Money("KRW", 110000),
                        bed_type = "TWIN",
                        view_type = "CITY",
                        bedrooms = 1,
                        available_rooms = 2,
                        is_available = true,
                    ),
                ),
            ),
        )

        val result = service.orchestrate(
            ChatCopilotOrchestrateRequest(
                message = "부산 가족여행 숙소 추천",
                session_state = ChatCopilotSessionState(
                    destination = "Busan",
                    date_range = ChatCopilotDateRange(
                        check_in = "2026-04-10",
                        check_out = "2026-04-12",
                    ),
                    guests = ChatCopilotGuests(rooms = 1, adults = 2, children = 1, children_ages = listOf(7)),
                ),
            ),
        )

        assertTrue(result.degraded)
        assertTrue(result.tool_trace.any { it.tool == "get_price_calendar" && it.status == "failed" })
        assertTrue(result.actions.any { it.type == "retry_with_patch" })
        assertTrue(result.actions.any { it.type == "apply_filters" })
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(result.evidence.all { it.why_recommended.isNotEmpty() && it.cautions.isNotEmpty() })
    }

    private fun anyLong(): Long {
        ArgumentMatchers.anyLong()
        return 0L
    }

    private fun anyInt(): Int {
        ArgumentMatchers.anyInt()
        return 0
    }

    private fun anyLocalDate(): LocalDate {
        ArgumentMatchers.any(LocalDate::class.java)
        return LocalDate.of(2026, 1, 1)
    }

    private fun anySearchRequest(): SearchRequest {
        ArgumentMatchers.any(SearchRequest::class.java)
        return SearchRequest(
            q = null,
            city = null,
            check_in = null,
            check_out = null,
            adults = null,
            children = null,
            min_price = null,
            max_price = null,
            min_rating = null,
            sort = null,
            cursor = null,
            limit = 1,
        )
    }

    private fun anyPriceCalendarRequest(): PriceCalendarRequest {
        ArgumentMatchers.any(PriceCalendarRequest::class.java)
        val today = LocalDate.of(2026, 1, 1)
        return PriceCalendarRequest(
            place_id = "Seoul",
            from = today,
            to = today.plusDays(1),
            currency = "KRW",
            rooms = 1,
            adults = 2,
            children = 0,
            children_ages = emptyList(),
        )
    }
}
