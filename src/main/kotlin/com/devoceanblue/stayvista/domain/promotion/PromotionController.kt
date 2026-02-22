package com.devoceanblue.stayvista.domain.promotion

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/promotions")
class PromotionController(
    private val promotionService: PromotionService,
) {
    @GetMapping("/campaigns")
    fun listCampaigns(
        @RequestParam(defaultValue = "HOTEL_SALE") section: String,
        @RequestParam(required = false) city: String?,
        @RequestParam(name = "exclude_country", required = false) excludeCountry: String?,
        @RequestParam(defaultValue = "12") @Min(1) @Max(60) limit: Int,
    ) = ApiResponses.ok(
        promotionService.listCampaigns(
            section = section,
            city = city,
            excludeCountry = excludeCountry,
            limit = limit,
        ),
    )

    @PostMapping("/campaigns/{campaignId}/claim")
    fun claimCampaign(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @PathVariable campaignId: Long,
    ) = ApiResponses.ok(
        promotionService.claimCampaign(
            userId = userId,
            campaignId = campaignId,
        ),
    )
}
