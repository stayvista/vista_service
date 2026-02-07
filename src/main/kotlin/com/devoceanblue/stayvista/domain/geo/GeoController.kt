package com.devoceanblue.stayvista.domain.geo

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
@RequestMapping("/v1/geo")
class GeoController(
    private val geoService: GeoService,
) {
    @GetMapping("/pois/nearby")
    fun nearbyPois(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(name = "radius_m", defaultValue = "2000") @Min(100) @Max(10000) radiusM: Int,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ) = ApiResponses.ok(
        geoService.nearbyPois(
            NearbyPoiRequest(
                lat = lat,
                lng = lng,
                radius_m = radiusM,
                category = category,
                limit = limit,
            ),
        ),
    )
}
