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
@RequestMapping("/v1/destinations")
class DestinationRecommendationController(
    private val destinationRecommendationService: DestinationRecommendationService,
) {
    @GetMapping("/recommendations")
    fun recommendations(
        @RequestParam(name = "city_id", required = false) cityId: String?,
        @RequestParam(name = "place_id", required = false) placeId: String?,
        @RequestParam(defaultValue = "ko") lang: String,
        @RequestParam(defaultValue = "8") @Min(4) @Max(24) limit: Int,
    ) = ApiResponses.ok(
        destinationRecommendationService.recommend(
            cityId = cityId,
            placeId = placeId,
            lang = lang,
            limit = limit,
        ),
    )
}
