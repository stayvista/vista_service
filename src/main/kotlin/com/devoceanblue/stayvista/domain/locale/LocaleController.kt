package com.devoceanblue.stayvista.domain.locale

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/v1/locale")
class LocaleController(
    private val localeService: LocaleService,
) {
    @GetMapping
    fun getLocale(
        request: HttpServletRequest,
        @RequestHeader(value = "Accept-Language", required = false) acceptLanguage: String?,
        @RequestHeader(value = "X-Country-Code", required = false) countryHint: String?,
        @RequestHeader(value = "X-Anon-Id", required = false) anonId: String?,
    ) = ApiResponses.ok(
        localeService.getLocale(
            userId = request.getHeader("X-User-Id")?.toLongOrNull(),
            sessionId = localeService.resolveSessionId(anonId, request.remoteAddr, request.getHeader("User-Agent")),
            acceptLanguage = acceptLanguage,
            countryHint = countryHint,
        ),
    )

    @PostMapping
    fun overrideLocale(
        request: HttpServletRequest,
        @Valid @RequestBody body: LocaleOverrideRequest,
        @RequestHeader(value = "Accept-Language", required = false) acceptLanguage: String?,
        @RequestHeader(value = "X-Anon-Id", required = false) anonId: String?,
    ) = ApiResponses.ok(
        localeService.overrideLocale(
            userId = request.getHeader("X-User-Id")?.toLongOrNull(),
            sessionId = localeService.resolveSessionId(anonId, request.remoteAddr, request.getHeader("User-Agent")),
            request = body,
            acceptLanguage = acceptLanguage,
        ),
    )
}
