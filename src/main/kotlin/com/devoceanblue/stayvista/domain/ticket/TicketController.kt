package com.devoceanblue.stayvista.domain.ticket

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import java.time.LocalDate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class TicketController(
    private val ticketService: TicketService,
) {
    @PostMapping("/admin/tickets/products")
    fun createProduct(@Valid @RequestBody request: CreateTicketProductRequest) = ApiResponses.ok(
        mapOf("product_id" to ticketService.createProduct(request)),
    )

    @PostMapping("/admin/tickets/products/{productId}/events")
    fun createEvent(
        @PathVariable productId: Long,
        @Valid @RequestBody request: CreateTicketEventRequest,
    ) = ApiResponses.ok(
        mapOf("event_id" to ticketService.createEvent(productId, request)),
    )

    @PutMapping("/admin/tickets/events/{eventId}/inventory")
    fun putEventInventory(
        @PathVariable eventId: Long,
        @Valid @RequestBody request: PutTicketInventoryRequest,
    ) = ApiResponses.ok(
        mapOf("event_id" to eventId, "updated" to true),
    ).also { ticketService.putInventory(eventId, request) }

    @GetMapping("/tickets/products")
    fun listProducts() = ApiResponses.ok(ticketService.listProducts())

    @GetMapping("/tickets/products/{productId}")
    fun getProduct(@PathVariable productId: Long) = ApiResponses.ok(ticketService.getProduct(productId))

    @GetMapping("/tickets/events")
    fun listEvents(
        @RequestParam(name = "product_id", required = false) productId: Long?,
        @RequestParam(required = false) date: LocalDate?,
    ) = ApiResponses.ok(ticketService.listEvents(productId, date))

    @PostMapping("/tickets/orders/holds")
    fun hold(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: TicketHoldRequest,
    ): ResponseEntity<Any> {
        val data = ticketService.hold(
            userId = userId,
            idempotencyKey = idempotencyKey,
            request = request,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(data))
    }

    @PostMapping("/tickets/orders/{orderId}/confirm")
    fun confirm(
        @PathVariable orderId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: TicketConfirmRequest,
    ) = ApiResponses.ok(
        ticketService.confirm(
            userId = userId,
            rawOrderId = orderId,
            idempotencyKey = idempotencyKey,
            request = request,
        ),
    )

    @PostMapping("/admin/vouchers/validate")
    fun validateVoucher(@Valid @RequestBody request: ValidateVoucherRequest) = ApiResponses.ok(
        ticketService.validateVoucher(request),
    )
}
