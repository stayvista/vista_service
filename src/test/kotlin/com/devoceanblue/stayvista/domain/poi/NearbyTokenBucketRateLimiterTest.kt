package com.devoceanblue.stayvista.domain.poi

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NearbyTokenBucketRateLimiterTest {
    @Test
    fun `token bucket should enforce burst and refill`() {
        val clock = MutableClock(Instant.parse("2026-02-11T00:00:00Z"))
        val limiter = NearbyTokenBucketRateLimiter(
            clock = clock,
            enabled = true,
            refillTokens = 20,
            refillWindowMs = 10_000,
            burstTokens = 10,
        )

        repeat(30) {
            assertTrue(limiter.allow("user-1").allowed)
        }

        val blocked = limiter.allow("user-1")
        assertFalse(blocked.allowed)
        assertTrue(blocked.retryAfterMs > 0)

        clock.plusMillis(500)
        assertTrue(limiter.allow("user-1").allowed)
    }

    private class MutableClock(
        private var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant

        fun plusMillis(ms: Long) {
            instant = instant.plusMillis(ms)
        }
    }
}
