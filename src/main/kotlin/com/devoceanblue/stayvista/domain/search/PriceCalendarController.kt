package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.ApiResponses
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/prices")
class PriceCalendarController(
    private val priceCalendarService: PriceCalendarService,
) {
    @GetMapping("/calendar")
    fun calendar(
        @RequestParam place_id: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "KRW") currency: String,
        @RequestParam(defaultValue = "1") rooms: Int,
        @RequestParam(defaultValue = "2") adults: Int,
        @RequestParam(defaultValue = "0") children: Int,
        @RequestParam(required = false) children_ages: String?,
    ) = ApiResponses.ok(
        priceCalendarService.calendar(
            PriceCalendarRequest(
                place_id = place_id,
                from = from?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                to = to?.let { LocalDate.parse(it) } ?: LocalDate.now().plusDays(59),
                currency = currency,
                rooms = rooms,
                adults = adults,
                children = children,
                children_ages = parseChildrenAges(children_ages),
            ),
        ),
    )

    private fun parseChildrenAges(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(",")
            .mapNotNull { token -> token.trim().toIntOrNull() }
    }
}
