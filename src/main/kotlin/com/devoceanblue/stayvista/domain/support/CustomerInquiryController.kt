package com.devoceanblue.stayvista.domain.support

import com.devoceanblue.stayvista.common.api.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/me/inquiries")
class CustomerInquiryController(
    private val customerInquiryService: CustomerInquiryService,
) {
    @GetMapping
    fun listInquiries(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = ApiResponses.ok(
        customerInquiryService.listInquiries(
            userId = userId,
            limit = limit,
        ),
    )

    @GetMapping("/{inquiryId}")
    fun getInquiry(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @PathVariable inquiryId: Long,
    ) = ApiResponses.ok(
        customerInquiryService.getInquiry(
            userId = userId,
            inquiryId = inquiryId,
        ),
    )

    @PostMapping
    fun createInquiry(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @RequestBody request: CustomerInquiryCreateRequest,
    ) = ApiResponses.ok(
        customerInquiryService.createInquiry(
            userId = userId,
            request = request,
        ),
    )
}
