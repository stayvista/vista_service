package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class CatalogController(
    private val catalogService: CatalogService,
    private val homeContentService: HomeContentService,
    private val propertyContentService: PropertyContentService,
) {
    @PostMapping("/admin/properties")
    fun createProperty(@Valid @RequestBody request: CreatePropertyRequest) = ApiResponses.ok(
        mapOf("property_id" to catalogService.createProperty(request)),
    )

    @PatchMapping("/admin/properties/{propertyId}")
    fun patchProperty(
        @PathVariable propertyId: Long,
        @Valid @RequestBody request: PatchPropertyRequest,
    ) = ApiResponses.ok(
        mapOf("property_id" to propertyId, "updated" to true),
    ).also { catalogService.patchProperty(propertyId, request) }

    @PostMapping("/admin/properties/{propertyId}/room-types")
    fun createRoomType(
        @PathVariable propertyId: Long,
        @Valid @RequestBody request: CreateRoomTypeRequest,
    ) = ApiResponses.ok(
        mapOf("room_type_id" to catalogService.createRoomType(propertyId, request)),
    )

    @PatchMapping("/admin/room-types/{roomTypeId}")
    fun patchRoomType(
        @PathVariable roomTypeId: Long,
        @Valid @RequestBody request: PatchRoomTypeRequest,
    ) = ApiResponses.ok(
        mapOf("room_type_id" to roomTypeId, "updated" to true),
    ).also { catalogService.patchRoomType(roomTypeId, request) }

    @PutMapping("/admin/room-types/{roomTypeId}/inventory")
    fun putInventory(
        @PathVariable roomTypeId: Long,
        @Valid @RequestBody request: PutInventoryRequest,
    ) = ApiResponses.ok(
        mapOf(
            "room_type_id" to roomTypeId,
            "updated_nights" to catalogService.putInventory(roomTypeId, request),
        ),
    )

    @GetMapping("/properties/{propertyId}")
    fun getProperty(@PathVariable propertyId: Long) = ApiResponses.ok(catalogService.getProperty(propertyId))

    @GetMapping("/home/content")
    fun getHomeContent() = ApiResponses.ok(homeContentService.getContent())

    @GetMapping("/properties")
    fun listProperties(
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = ApiResponses.ok(
        catalogService.listProperties(
            limit = limit,
            cursor = cursor?.toLongOrNull(),
        ),
    )

    @GetMapping("/properties/{propertyId}/room-types")
    fun listRoomTypes(
        @PathVariable propertyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) check_in: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) check_out: LocalDate?,
        @RequestParam(required = false, defaultValue = "1") @Min(1) rooms: Int,
    ) = ApiResponses.ok(
        catalogService.listRoomTypes(
            propertyId = propertyId,
            checkIn = check_in,
            checkOut = check_out,
            rooms = rooms,
        ),
    )

    @GetMapping("/properties/{propertyId}/reviews")
    fun listPropertyReviews(
        @PathVariable propertyId: Long,
        @RequestParam(required = false) tag: String?,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "12") @Min(1) @Max(50) size: Int,
    ) = ApiResponses.ok(
        catalogService.listPropertyReviews(
            propertyId = propertyId,
            tag = tag?.trim()?.takeIf { it.isNotEmpty() },
            page = page,
            size = size,
        ),
    )

    @GetMapping("/properties/{propertyId}/content")
    fun getPropertyContent(@PathVariable propertyId: Long) = ApiResponses.ok(propertyContentService.getContent(propertyId))
}
