package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/autocomplete")
class AutocompleteController(
    private val autocompleteService: AutocompleteService,
    private val feedbackService: AutocompleteFeedbackService,
) {
    @GetMapping
    fun autocomplete(
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) types: String?,
        @RequestParam(defaultValue = "10") @Min(1) @Max(20) size: Int,
        @RequestParam(defaultValue = "ko") lang: String,
    ) = ApiResponses.ok(
        autocompleteService.autocomplete(
            AutocompleteQuery(
                q = q,
                types = parseRequestedTypes(types),
                size = size,
                lang = lang,
                principalKey = principalOf(request = request, explicitAnonId = request.getHeader("X-Anon-Id")),
            ),
        ),
    )

    @PostMapping("/feedback/impression")
    fun recordImpression(
        servletRequest: HttpServletRequest,
        @RequestBody request: AutocompleteImpressionRequest,
    ) = ApiResponses.ok(
        feedbackService.recordImpression(
            request = request,
            principalKey = principalOf(servletRequest, request.anon_id),
        ),
    )

    @PostMapping("/feedback/select")
    fun recordSelect(
        servletRequest: HttpServletRequest,
        @RequestBody request: AutocompleteSelectRequest,
    ) = ApiResponses.ok(
        feedbackService.recordSelect(
            request = request,
            principalKey = principalOf(servletRequest, request.anon_id),
        ),
    )

    private fun principalOf(request: HttpServletRequest, explicitAnonId: String?): String {
        val userId = request.getHeader("X-User-Id")?.trim().orEmpty()
        if (userId.isNotBlank()) {
            return "user:$userId"
        }

        val anonId = explicitAnonId?.trim().orEmpty()
        if (anonId.isNotBlank()) {
            return "anon:$anonId"
        }

        val remote = request.remoteAddr?.trim().orEmpty().ifBlank { "unknown" }
        return "ip:$remote"
    }
}
