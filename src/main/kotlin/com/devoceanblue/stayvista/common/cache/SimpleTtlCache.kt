package com.devoceanblue.stayvista.common.cache

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

@Component
class SimpleTtlCache(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val store = ConcurrentHashMap<String, CacheEntry<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val now = clock.millis()
        val entry = store[key] ?: return null
        return if (entry.expiresAtMillis > now) {
            entry.value as T
        } else {
            store.remove(key)
            null
        }
    }

    fun put(key: String, ttlMillis: Long, value: Any) {
        store[key] = CacheEntry(
            value = value,
            expiresAtMillis = clock.millis() + ttlMillis,
        )
    }

    private data class CacheEntry<T : Any>(
        val value: T,
        val expiresAtMillis: Long,
    )
}
