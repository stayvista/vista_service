package com.devoceanblue.stayvista.domain.me

import com.devoceanblue.stayvista.common.api.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/me")
class MeController(
    private val myReservationService: MyReservationService,
) {
    @GetMapping("/reservations")
    fun listReservations(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = ApiResponses.ok(
        myReservationService.listReservations(
            userId = userId,
            limit = limit,
        ),
    )
}

