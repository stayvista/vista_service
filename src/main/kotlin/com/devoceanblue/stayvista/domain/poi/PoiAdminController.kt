package com.devoceanblue.stayvista.domain.poi

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/admin/poi")
class PoiAdminController(
    private val poiService: PoiService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(required = false) keyword: String?,
    ) = ApiResponses.ok(
        poiService.listAdminPois(
            limit = limit,
            offset = offset,
            keyword = keyword,
        ),
    )

    @GetMapping("/{poiId}")
    fun detail(@PathVariable poiId: Long) = ApiResponses.ok(poiService.getAdminPoi(poiId))

    @PostMapping
    fun create(@Valid @RequestBody request: AdminPoiCreateRequest) = ApiResponses.ok(
        mapOf("poi_id" to poiService.createAdminPoi(request)),
    )

    @PatchMapping("/{poiId}")
    fun patch(
        @PathVariable poiId: Long,
        @Valid @RequestBody request: AdminPoiPatchRequest,
    ) = ApiResponses.ok(
        mapOf("poi_id" to poiId, "updated" to true),
    ).also {
        poiService.patchAdminPoi(
            poiId = poiId,
            request = request,
        )
    }

    @PostMapping("/geohash/backfill")
    fun backfill(
        @RequestParam(defaultValue = "1000") @Min(1) @Max(5000) limit: Int,
    ) = ApiResponses.ok(
        poiService.backfillGeohash(limit),
    )
}
