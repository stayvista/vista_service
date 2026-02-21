package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/ai/search")
class SearchCopilotController(
    private val searchCopilotService: SearchCopilotService,
) {
    @PostMapping("/copilot")
    fun copilot(@Valid @RequestBody request: SearchCopilotRequest) = ApiResponses.ok(
        searchCopilotService.recommend(request),
    )
}
