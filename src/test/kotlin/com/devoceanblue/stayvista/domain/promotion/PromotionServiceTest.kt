package com.devoceanblue.stayvista.domain.promotion

import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class PromotionServiceTest {
    private val mapper: PromotionMapper = Mockito.mock(PromotionMapper::class.java)
    private val domainSupportService: DomainSupportService = Mockito.mock(DomainSupportService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val promotionService = PromotionService(
        mapper = mapper,
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

    private fun stubQuery(capturedArgs: MutableList<Any?>) {
        Mockito.`when`(
            mapper.listCampaigns(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
            ),
        ).thenAnswer { invocation ->
            capturedArgs.clear()
            capturedArgs.add(invocation.getArgument<String>(0))
            capturedArgs.add(invocation.getArgument<String>(1))
            capturedArgs.add(invocation.getArgument<String>(1))
            capturedArgs.add(invocation.getArgument<String>(2))
            capturedArgs.add(invocation.getArgument<String>(2))
            capturedArgs.add(invocation.getArgument<Int>(3))
            listOf(
                PromotionCampaignQueryRow(
                    id = 1L,
                    code = "PROMO_TEST",
                    section = "HOTEL_SALE",
                    title = "title",
                    subtitle = null,
                    description = null,
                    city = null,
                    imageUrl = null,
                    badgeText = null,
                    discountText = null,
                    currency = "KRW",
                    couponValueType = "PERCENT",
                    couponValue = 10.0,
                    minOrderAmount = 10000L,
                    issueLimit = 100,
                    issuedCount = 0,
                    startsAt = java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
                    endsAt = java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                    priority = 1,
                    status = "ACTIVE",
                    remainingCount = 100,
                ),
            )
        }
    }
}
