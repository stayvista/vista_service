package com.devoceanblue.stayvista.domain.booking

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/bookings")
class BookingController(
    private val bookingService: BookingService,
) {
    @PostMapping("/holds")
    fun createHold(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: BookingHoldRequest,
    ): ResponseEntity<Any> {
        val data = bookingService.createHold(userId, idempotencyKey, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(data))
    }

    @PostMapping("/{bookingId}/confirm")
    fun confirm(
        @PathVariable bookingId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: BookingConfirmRequest,
    ) = ApiResponses.ok(
        bookingService.confirm(
            userId = userId,
            rawBookingId = bookingId,
            idempotencyKey = idempotencyKey,
            request = request,
        ),
    )

    @PostMapping("/{bookingId}/cancel")
    fun cancel(
        @PathVariable bookingId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: BookingCancelRequest,
    ) = ApiResponses.ok(
        bookingService.cancel(
            userId = userId,
            rawBookingId = bookingId,
            idempotencyKey = idempotencyKey,
            request = request,
        ),
    )
}
