package com.devoceanblue.stayvista.domain.packagee

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class PackageController(
    private val packageService: PackageService,
) {
    @PostMapping("/admin/packages")
    fun createPackage(@Valid @RequestBody request: CreatePackageRequest) = ApiResponses.ok(
        mapOf("package_id" to packageService.createPackage(request)),
    )

    @GetMapping("/packages")
    fun listPackages() = ApiResponses.ok(packageService.listPackages())

    @GetMapping("/packages/{packageId}")
    fun getPackage(@PathVariable packageId: Long) = ApiResponses.ok(packageService.getPackage(packageId))

    @GetMapping("/admin/packages/orders")
    fun listPackageOrders(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = ApiResponses.ok(
        packageService.listOrders(
            status = status,
            limit = limit,
        ),
    )

    @PostMapping("/packages/{packageId}/holds")
    fun hold(
        @PathVariable packageId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: PackageHoldRequest,
    ): ResponseEntity<Any> {
        val data = packageService.hold(
            userId = userId,
            packageId = packageId,
            idempotencyKey = idempotencyKey,
            request = request,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(data))
    }

    @PostMapping("/packages/{packageId}/confirm")
    fun confirm(
        @PathVariable packageId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: PackageConfirmRequest,
    ) = ApiResponses.ok(
        packageService.confirm(
            userId = userId,
            packageId = packageId,
            idempotencyKey = idempotencyKey,
            request = request,
        ),
    )
}
