package com.devoceanblue.stayvista.domain.promotion

import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class PromotionServiceTest {
    private val jdbcTemplate: JdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
    private val domainSupportService: DomainSupportService = Mockito.mock(DomainSupportService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val promotionService = PromotionService(
        jdbcTemplate = jdbcTemplate,
        domainSupportService = domainSupportService,
        meterRegistry = meterRegistry,
    )

    @Test
    fun `listCampaigns should pass normalized exclude country for global pick`() {
        val capturedArgs = mutableListOf<Any?>()
        stubQuery(capturedArgs)

        val result = promotionService.listCampaigns(
            section = "global_pick",
            city = null,
            excludeCountry = "kr",
            limit = 120,
        )

        assertEquals("GLOBAL_PICK", capturedArgs[0])
        assertEquals("", capturedArgs[1])
        assertEquals("", capturedArgs[2])
        assertEquals("KR", capturedArgs[3])
        assertEquals("KR", capturedArgs[4])
        assertEquals(60, capturedArgs[5])
        assertEquals("GLOBAL_PICK", result.section)
        assertEquals(null, result.city)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `listCampaigns should keep city filter and blank exclude country when not provided`() {
        val capturedArgs = mutableListOf<Any?>()
        stubQuery(capturedArgs)

        val result = promotionService.listCampaigns(
            section = "hotel_sale",
            city = "Seoul",
            excludeCountry = null,
            limit = 12,
        )

        assertEquals("HOTEL_SALE", capturedArgs[0])
        assertEquals("Seoul", capturedArgs[1])
        assertEquals("Seoul", capturedArgs[2])
        assertEquals("", capturedArgs[3])
        assertEquals("", capturedArgs[4])
        assertEquals(12, capturedArgs[5])
        assertEquals("HOTEL_SALE", result.section)
        assertEquals("Seoul", result.city)
        assertEquals(1, result.items.size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubQuery(capturedArgs: MutableList<Any?>) {
        Mockito.`when`(
            jdbcTemplate.query(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any<RowMapper<PromotionCampaignSummary>>(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
            ),
        ).thenAnswer { invocation ->
            capturedArgs.clear()
            capturedArgs.add(invocation.getArgument(2))
            capturedArgs.add(invocation.getArgument(3))
            capturedArgs.add(invocation.getArgument(4))
            capturedArgs.add(invocation.getArgument(5))
            capturedArgs.add(invocation.getArgument(6))
            capturedArgs.add(invocation.getArgument(7))
            listOf(
                PromotionCampaignSummary(
                    campaign_id = 1L,
                    code = "PROMO_TEST",
                    section = "HOTEL_SALE",
                    title = "title",
                    subtitle = null,
                    description = null,
                    city = null,
                    image_url = null,
                    badge_text = null,
                    discount_text = null,
                    currency = "KRW",
                    coupon_value_type = "PERCENT",
                    coupon_value = 10.0,
                    min_order_amount = 10000L,
                    issue_limit = 100,
                    issued_count = 0,
                    remaining_count = 100,
                    starts_at = Instant.now().minusSeconds(3600),
                    ends_at = Instant.now().plusSeconds(3600),
                    priority = 1,
                    status = "ACTIVE",
                    claimable = true,
                ),
            )
        }
    }
}
