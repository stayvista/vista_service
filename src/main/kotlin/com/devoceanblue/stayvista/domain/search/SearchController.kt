package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/search")
class SearchController(
    private val searchService: SearchService,
) {
    @GetMapping("/properties")
    fun searchProperties(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) place_id: String?,
        @RequestParam(required = false) check_in: String?,
        @RequestParam(required = false) check_out: String?,
        @RequestParam(required = false) adults: Int?,
        @RequestParam(required = false) children: Int?,
        @RequestParam(required = false) min_price: Long?,
        @RequestParam(required = false) max_price: Long?,
        @RequestParam(required = false) min_rating: Double?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ) = ApiResponses.ok(
        searchService.search(
            SearchRequest(
                q = q,
                city = city,
                place_id = place_id,
                check_in = check_in,
                check_out = check_out,
                adults = adults,
                children = children,
                min_price = min_price,
                max_price = max_price,
                min_rating = min_rating,
                sort = sort,
                cursor = cursor,
                limit = limit,
            ),
        ),
    )
}
