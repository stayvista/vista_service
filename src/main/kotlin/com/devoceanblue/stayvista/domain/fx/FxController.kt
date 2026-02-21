package com.devoceanblue.stayvista.domain.fx

import com.devoceanblue.stayvista.common.api.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class FxController(
    private val fxService: FxService,
) {
    @GetMapping("/fx")
    fun quote(
        @RequestParam(defaultValue = "KRW") base: String,
        @RequestParam(defaultValue = "USD") quote: String,
    ) = ApiResponses.ok(
        fxService.quote(base, quote),
    )
}
