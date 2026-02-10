package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class ChatCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Any>>()

    fun getPromptCache(key: String): ChatRecommendData? {
        return readJson("chat:prompt:$key", object : TypeReference<ChatRecommendData>() {})
            ?.also { meterRegistry.counter("chat_prompt_cache_total", "result", "hit").increment() }
            ?: run {
                meterRegistry.counter("chat_prompt_cache_total", "result", "miss").increment()
                null
            }
    }

    fun putPromptCache(key: String, value: ChatRecommendData, ttlSeconds: Long) {
        writeJson("chat:prompt:$key", value, ttlSeconds)
    }

    fun getRetrievalCache(key: String): RagSearchResult? {
        return readJson("chat:retrieval:$key", object : TypeReference<RagSearchResult>() {})
            ?.also { meterRegistry.counter("chat_retrieval_cache_total", "result", "hit").increment() }
            ?: run {
                meterRegistry.counter("chat_retrieval_cache_total", "result", "miss").increment()
                null
            }
    }

    fun putRetrievalCache(key: String, value: RagSearchResult, ttlSeconds: Long) {
        writeJson("chat:retrieval:$key", value, ttlSeconds)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> singleFlight(scope: String, key: String, supplier: () -> T): T {
        val fullKey = "$scope:$key"
        val owned = CompletableFuture<Any>()
        val existing = inflight.putIfAbsent(fullKey, owned)
        if (existing != null) {
            meterRegistry.counter("chat_singleflight_total", "scope", scope, "result", "join").increment()
            return existing.join() as T
        }

        meterRegistry.counter("chat_singleflight_total", "scope", scope, "result", "leader").increment()
        return try {
            val value = supplier()
            owned.complete(value as Any)
            value
        } catch (ex: Exception) {
            owned.completeExceptionally(ex)
            throw ex
        } finally {
            inflight.remove(fullKey)
        }
    }

    private fun writeJson(key: String, value: Any, ttlSeconds: Long) {
        try {
            redisTemplate.opsForValue().set(
                key,
                objectMapper.writeValueAsString(value),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)),
            )
        } catch (_: Exception) {
            meterRegistry.counter("chat_cache_redis_errors_total", "op", "write").increment()
        }
    }

    private fun <T> readJson(key: String, typeRef: TypeReference<T>): T? {
        return try {
            val raw = redisTemplate.opsForValue().get(key) ?: return null
            objectMapper.readValue(raw, typeRef)
        } catch (_: Exception) {
            meterRegistry.counter("chat_cache_redis_errors_total", "op", "read").increment()
            null
        }
    }
}
