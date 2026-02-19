package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class AutocompleteCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.autocomplete.cache-prefix:ac:stay:v1:}") private val cachePrefix: String,
    @Value("\${stayvista.autocomplete.query-cache-ttl-seconds:60}") private val queryCacheTtlSeconds: Long,
    @Value("\${stayvista.autocomplete.query-cache-jitter-seconds:20}") private val queryCacheJitterSeconds: Long,
    @Value("\${stayvista.autocomplete.popular-cache-ttl-seconds:600}") private val popularCacheTtlSeconds: Long,
    @Value("\${stayvista.autocomplete.recent-max-size:20}") private val recentMaxSize: Int,
    @Value("\${stayvista.autocomplete.recent-ttl-seconds:2592000}") private val recentTtlSeconds: Long,
) {
    fun queryKey(
        lang: String,
        types: Set<PlaceType>,
        normalizedQ: String,
        size: Int,
    ): String {
        return "${cachePrefix}${lang.lowercase()}:${types.hashKey()}:$normalizedQ:$size"
    }

    fun popularKey(lang: String, types: Set<PlaceType>, size: Int): String {
        return "${cachePrefix}${lang.lowercase()}:popular:${types.hashKey()}:$size"
    }

    fun getSuggestions(key: String): List<AutocompleteCandidate>? {
        return runCatching {
            val payload = redisTemplate.opsForValue().get(key) ?: return null
            decodeCandidates(payload)
        }.getOrNull()
    }

    fun putQuerySuggestions(key: String, items: List<AutocompleteCandidate>) {
        putSuggestions(
            key = key,
            items = items,
            ttlSeconds = jitteredTtlSeconds(queryCacheTtlSeconds, queryCacheJitterSeconds),
        )
    }

    fun putPopularSuggestions(key: String, items: List<AutocompleteCandidate>) {
        putSuggestions(
            key = key,
            items = items,
            ttlSeconds = popularCacheTtlSeconds.coerceAtLeast(1),
        )
    }

    fun getRecent(principalKey: String): List<AutocompleteCandidate> {
        return runCatching {
            val key = recentKey(principalKey)
            val values = redisTemplate.opsForList().range(key, 0, recentMaxSize.toLong() - 1) ?: emptyList()
            values.mapNotNull { decodeRecent(it) }
        }.getOrElse { emptyList() }
    }

    fun pushRecent(principalKey: String, candidate: AutocompleteCandidate) {
        runCatching {
            val existing = getRecent(principalKey)
            val merged = buildList {
                add(candidate.copy(bucket = "recent", source = "recent"))
                existing.forEach { row ->
                    if (row.dedupeKey() != candidate.dedupeKey()) {
                        add(row.copy(bucket = "recent", source = "recent"))
                    }
                }
            }.take(recentMaxSize.coerceAtLeast(1))

            val key = recentKey(principalKey)
            redisTemplate.delete(key)
            if (merged.isNotEmpty()) {
                val payload = merged.map { encodeRecent(it) }
                redisTemplate.opsForList().rightPushAll(key, payload)
                redisTemplate.expire(key, Duration.ofSeconds(recentTtlSeconds.coerceAtLeast(1)))
            }
        }
    }

    fun invalidateAutocompleteCaches() {
        runCatching {
            val keys = redisTemplate.keys("${cachePrefix}*")
            if (!keys.isNullOrEmpty()) {
                redisTemplate.delete(keys)
            }
        }
    }

    private fun putSuggestions(
        key: String,
        items: List<AutocompleteCandidate>,
        ttlSeconds: Long,
    ) {
        runCatching {
            redisTemplate.opsForValue().set(
                key,
                objectMapper.writeValueAsString(items),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)),
            )
        }
    }

    private fun decodeCandidates(payload: String): List<AutocompleteCandidate>? {
        return objectMapper.readValue(payload, CANDIDATE_LIST_TYPE)
    }

    private fun encodeRecent(candidate: AutocompleteCandidate): String {
        return objectMapper.writeValueAsString(candidate)
    }

    private fun decodeRecent(payload: String): AutocompleteCandidate? {
        return runCatching {
            objectMapper.readValue(payload, AutocompleteCandidate::class.java)
        }.getOrNull()
    }

    private fun recentKey(principalKey: String): String {
        return "ac:stay:recent:${principalKey.lowercase()}"
    }

    private fun jitteredTtlSeconds(base: Long, jitter: Long): Long {
        if (jitter <= 0) {
            return base.coerceAtLeast(1)
        }
        val delta = ThreadLocalRandom.current().nextLong(jitter + 1)
        return (base + delta).coerceAtLeast(1)
    }

    private fun Set<PlaceType>.hashKey(): String {
        return this
            .map { it.name }
            .sorted()
            .joinToString("-")
            .lowercase()
    }

    companion object {
        private val CANDIDATE_LIST_TYPE = object : TypeReference<List<AutocompleteCandidate>>() {}
    }
}
