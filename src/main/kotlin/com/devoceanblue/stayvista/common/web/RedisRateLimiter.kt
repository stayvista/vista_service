package com.devoceanblue.stayvista.common.web

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class RedisRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    fun allow(policyName: String, principal: String, limitPerMinute: Int): RateLimitDecision {
        val now = Instant.now(clock)
        val minuteBucket = now.epochSecond / 60
        val key = "rate:v1:$policyName:$principal:$minuteBucket"

        return try {
            val count = redisTemplate.opsForValue().increment(key) ?: 0L
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(120))
            }
            val retryAfterSeconds = (60 - (now.epochSecond % 60)).toInt().coerceAtLeast(1)
            RateLimitDecision(
                allowed = count <= limitPerMinute,
                retryAfterSeconds = retryAfterSeconds,
            )
        } catch (_: Exception) {
            meterRegistry.counter("rate_limit_redis_errors_total").increment()
            // fail-open to avoid total outage if Redis is down
            RateLimitDecision(
                allowed = true,
                retryAfterSeconds = 1,
            )
        }
    }
}

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Int,
)
