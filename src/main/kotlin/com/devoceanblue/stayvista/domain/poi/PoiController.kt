package com.devoceanblue.stayvista.domain.poi

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/poi")
class PoiController(
    private val poiService: PoiService,
) {
    @GetMapping("/nearby")
    fun nearby(
        @RequestParam bbox: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "distance") sort: String,
        @RequestParam(required = false) center: String?,
        @RequestParam(name = "radius_m", required = false) @Min(100) @Max(10000) radiusM: Int?,
    ) = ApiResponses.ok(
        poiService.nearby(
            PoiNearbyQuery(
                bbox = PoiBoundingBox.parse(bbox),
                category = category,
                limit = limit.coerceIn(1, 200),
                offset = offset.coerceAtLeast(0),
                sort = PoiSort.parse(sort),
                center = PoiCenter.parse(center),
                radius_m = radiusM,
            ),
        ),
    )

    @GetMapping("/{poiId}")
    fun detail(@PathVariable poiId: Long) = ApiResponses.ok(poiService.getPoiDetail(poiId))
}
