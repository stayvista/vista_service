package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.common.api.ApiResponses
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/autocomplete")
class AutocompleteAdminController(
    private val openSearchGateway: AutocompleteOpenSearchGateway,
    private val aggregationJob: AutocompleteAggregationJob,
) {
    @PostMapping("/ensure-index")
    fun ensureIndex() = ApiResponses.ok(
        runCatching {
            openSearchGateway.ensureIndexAndAlias()
            mapOf("ready" to true)
        }.getOrElse {
            mapOf("ready" to false, "error" to (it.message ?: "unknown"))
        },
    )

    @PostMapping("/aggregate")
    fun aggregate() = ApiResponses.ok(
        runCatching {
            aggregationJob.aggregate()
            mapOf("accepted" to true)
        }.getOrElse {
            mapOf("accepted" to false, "error" to (it.message ?: "unknown"))
        },
    )
}
