package com.devoceanblue.stayvista.domain.poi

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class NearbyTokenBucketRateLimiter(
    private val clock: Clock,
    @Value("\${stayvista.rate-limit.nearby.enabled:true}") private val enabled: Boolean,
    @Value("\${stayvista.rate-limit.nearby.refill-tokens:20}") private val refillTokens: Int,
    @Value("\${stayvista.rate-limit.nearby.refill-window-ms:10000}") private val refillWindowMs: Long,
    @Value("\${stayvista.rate-limit.nearby.burst-tokens:10}") private val burstTokens: Int,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(principal: String): PoiRateLimitDecision {
        if (!enabled) {
            return PoiRateLimitDecision(allowed = true, retryAfterMs = 0)
        }

        val now = clock.millis()
        val refillPerMs = refillTokens.toDouble() / refillWindowMs.coerceAtLeast(1)
        val capacity = (refillTokens + burstTokens).coerceAtLeast(1).toDouble()

        val bucket = buckets.computeIfAbsent(principal) {
            Bucket(tokens = capacity, updatedAtMs = now)
        }

        val decision = synchronized(bucket) {
            val elapsedMs = (now - bucket.updatedAtMs).coerceAtLeast(0)
            if (elapsedMs > 0) {
                val replenished = bucket.tokens + (elapsedMs * refillPerMs)
                bucket.tokens = min(capacity, replenished)
                bucket.updatedAtMs = now
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                PoiRateLimitDecision(allowed = true, retryAfterMs = 0)
            } else {
                val missing = 1.0 - bucket.tokens
                val retryMs = ceil(missing / refillPerMs).toLong().coerceAtLeast(50L)
                PoiRateLimitDecision(allowed = false, retryAfterMs = retryMs)
            }
        }

        if (buckets.size > 50_000) {
            purgeStale(now)
        }
        return decision
    }

    private fun purgeStale(now: Long) {
        val ttl = refillWindowMs.coerceAtLeast(1) * 6
        buckets.entries.removeIf { (_, bucket) -> now - bucket.updatedAtMs > ttl }
    }

    private data class Bucket(
        var tokens: Double,
        var updatedAtMs: Long,
    )
}
