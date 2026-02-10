package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class InMemoryVectorStore(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @Value("\${stayvista.chat.vector-cache-ttl-seconds:86400}") private val ttlSeconds: Long,
) {
    private val store = ConcurrentHashMap<String, CacheEntry>()

    fun get(key: String): List<Double>? {
        val entry = store[key] ?: return null
        val now = clock.millis()
        if (entry.expiresAtMillis <= now) {
            store.remove(key)
            meterRegistry.counter("chat_vector_cache_total", "result", "expired").increment()
            return null
        }
        meterRegistry.counter("chat_vector_cache_total", "result", "hit").increment()
        return entry.vector
    }

    fun put(key: String, vector: List<Double>) {
        store[key] = CacheEntry(
            vector = vector,
            expiresAtMillis = clock.millis() + ttlSeconds * 1000,
        )
        meterRegistry.counter("chat_vector_cache_total", "result", "put").increment()
    }

    private data class CacheEntry(
        val vector: List<Double>,
        val expiresAtMillis: Long,
    )
}
