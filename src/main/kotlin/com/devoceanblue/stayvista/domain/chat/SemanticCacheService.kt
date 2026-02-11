package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.sqrt

@Service
class SemanticCacheService(
    private val embedClient: EmbedClient,
    private val modelRegistry: LlmModelRegistry,
    private val vectorStore: InMemoryVectorStore,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @Value("\${stayvista.chat.semantic-cache.enabled:true}") private val enabled: Boolean,
    @Value("\${stayvista.chat.semantic-cache.similarity-threshold:0.92}") private val similarityThreshold: Double,
    @Value("\${stayvista.chat.semantic-cache.max-entries:800}") private val maxEntries: Int,
) {
    private val entries = ConcurrentHashMap<String, SemanticEntry>()

    fun lookup(namespace: String, query: String): ChatRecommendData? {
        if (!enabled) return null
        val queryVector = queryVector(query) ?: return null

        val now = clock.millis()
        var best: Pair<SemanticEntry, Double>? = null
        val threshold = similarityThreshold.coerceIn(0.5, 0.9999)

        entries.values.forEach { entry ->
            if (entry.namespace != namespace) return@forEach
            if (entry.expiresAtMillis <= now) {
                entries.remove(entry.key)
                return@forEach
            }

            val similarity = cosineSimilarity(queryVector, entry.vector)
            if (similarity >= threshold && (best == null || similarity > best!!.second)) {
                best = entry to similarity
            }
        }

        return if (best != null) {
            meterRegistry.counter("chat_semantic_cache_total", "result", "hit").increment()
            meterRegistry.summary("chat_semantic_cache_similarity").record(best!!.second)
            best!!.first.response.copy(
                context_used = best!!.first.response.context_used + mapOf(
                    "semantic_cache_hit" to true,
                    "semantic_cache_similarity" to "%.4f".format(best!!.second),
                ),
            )
        } else {
            meterRegistry.counter("chat_semantic_cache_total", "result", "miss").increment()
            null
        }
    }

    fun put(namespace: String, query: String, response: ChatRecommendData, ttlSeconds: Long) {
        if (!enabled) return
        val vector = queryVector(query) ?: return
        val normalized = normalize(query)
        val key = "$namespace|${sha256(normalized)}"
        val now = clock.millis()
        entries[key] = SemanticEntry(
            key = key,
            namespace = namespace,
            vector = vector,
            response = response,
            createdAtMillis = now,
            expiresAtMillis = now + (ttlSeconds.coerceAtLeast(30) * 1000),
        )
        trimToMaxSize()
        meterRegistry.counter("chat_semantic_cache_total", "result", "put").increment()
    }

    private fun trimToMaxSize() {
        val limit = maxEntries.coerceIn(100, 5000)
        while (entries.size > limit) {
            val victim = entries.values.minByOrNull { it.createdAtMillis } ?: return
            entries.remove(victim.key)
        }
    }

    private fun queryVector(query: String): List<Double>? {
        val normalized = normalize(query)
        if (normalized.isBlank()) return null

        val model = modelRegistry.embedModel()
        val key = "semantic:$model:${sha256(normalized)}"
        val cached = vectorStore.get(key)
        if (!cached.isNullOrEmpty()) {
            return cached
        }

        val embedded = runCatching { embedClient.embed(normalized) }
            .getOrElse {
                meterRegistry.counter("chat_semantic_cache_total", "result", "embed_error").increment()
                return null
            }
        if (embedded.isEmpty()) return null
        vectorStore.put(key, embedded)
        return embedded
    }

    private fun normalize(query: String): String {
        return query.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private data class SemanticEntry(
        val key: String,
        val namespace: String,
        val vector: List<Double>,
        val response: ChatRecommendData,
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
    )
}
