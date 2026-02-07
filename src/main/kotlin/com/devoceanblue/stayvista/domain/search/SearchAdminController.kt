package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/admin/search")
class SearchAdminController(
    private val searchIndexSyncService: SearchIndexSyncService,
) {
    @PostMapping("/reindex")
    fun reindex(
        @RequestParam(required = false) @Min(1) @Max(20000) limit: Int?,
    ) = ApiResponses.ok(
        searchIndexSyncService.reindexAll(limit),
    )
}
