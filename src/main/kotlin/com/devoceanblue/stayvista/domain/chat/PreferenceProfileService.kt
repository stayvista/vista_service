package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class PreferenceProfileService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.preference.ttl-seconds:2592000}") private val ttlSeconds: Long,
) {
    private val fallbackStore = ConcurrentHashMap<String, StoredPreferenceProfile>()

    fun resolveProfileKey(request: ChatRecommendRequest): String {
        val context = request.context
        val userId = context["user_id"]?.toString()?.trim().orEmpty()
        if (userId.isNotBlank()) return "user:$userId"

        val sessionId = context["session_id"]?.toString()?.trim().orEmpty()
        if (sessionId.isNotBlank()) return "session:$sessionId"

        val conversationId = context["conversation_id"]?.toString()?.trim().orEmpty()
        if (conversationId.isNotBlank()) return "conversation:$conversationId"

        return "anon"
    }

    fun load(profileKey: String): PreferenceProfileSnapshot {
        val key = prefKey(profileKey)
        val raw = runCatching { redisTemplate.opsForValue().get(key) }
            .getOrElse {
                meterRegistry.counter("chat_pref_profile_total", "result", "read_error").increment()
                null
            }
        if (raw.isNullOrBlank()) {
            val fallback = fallbackStore[key]
            if (fallback != null) {
                meterRegistry.counter("chat_pref_profile_total", "result", "fallback_hit").increment()
                return fallback.toSnapshot()
            }
            meterRegistry.counter("chat_pref_profile_total", "result", "miss").increment()
            return PreferenceProfileSnapshot()
        }

        return runCatching {
            val stored = objectMapper.readValue(raw, StoredPreferenceProfile::class.java)
            meterRegistry.counter("chat_pref_profile_total", "result", "hit").increment()
            stored.toSnapshot()
        }.getOrElse {
            meterRegistry.counter("chat_pref_profile_total", "result", "parse_error").increment()
            PreferenceProfileSnapshot()
        }
    }

    fun recordImplicitFeedback(profileKey: String, message: String) {
        if (profileKey == "anon") return

        val normalized = message.lowercase()
        val plusTags = mutableListOf<String>()
        if (normalized.contains("전시") || normalized.contains("museum")) plusTags += "culture"
        if (normalized.contains("맛집") || normalized.contains("food")) plusTags += "food"
        if (normalized.contains("자연") || normalized.contains("힐링") || normalized.contains("park")) plusTags += "nature"
        if (normalized.contains("액티비티") || normalized.contains("activity")) plusTags += "activity"
        if (normalized.contains("커플")) plusTags += "couple"
        if (normalized.contains("가족")) plusTags += "family"

        val plusCategories = mutableListOf<String>()
        if (normalized.contains("숙소") || normalized.contains("hotel")) plusCategories += "PROPERTY"
        if (normalized.contains("티켓") || normalized.contains("체험") || normalized.contains("ticket")) plusCategories += "TICKET"
        if (normalized.contains("패키지") || normalized.contains("package")) plusCategories += "PACKAGE"
        if (normalized.contains("주변") || normalized.contains("poi")) plusCategories += "POI"

        if (plusTags.isEmpty() && plusCategories.isEmpty()) {
            return
        }

        val current = load(profileKey)
        val merged = mergeProfile(
            current = current,
            likeTags = plusTags,
            dislikeTags = emptyList(),
            likeCategories = plusCategories,
            dislikeCategories = emptyList(),
        )
        store(profileKey, merged)
    }

    fun applyExplicitFeedback(request: ChatPreferenceFeedbackRequest): PreferenceProfileSnapshot {
        val profileKey = when {
            request.user_id.isNotBlank() -> "user:${request.user_id}"
            request.session_id.isNotBlank() -> "session:${request.session_id}"
            request.conversation_id.isNotBlank() -> "conversation:${request.conversation_id}"
            else -> "anon"
        }

        if (profileKey == "anon") {
            return PreferenceProfileSnapshot()
        }

        val current = load(profileKey)
        val merged = mergeProfile(
            current = current,
            likeTags = request.like_tags,
            dislikeTags = request.dislike_tags,
            likeCategories = request.like_categories,
            dislikeCategories = request.dislike_categories,
        )
        store(profileKey, merged)
        meterRegistry.counter("chat_pref_feedback_total", "result", "accepted").increment()
        return merged
    }

    fun rerank(profileKey: String, query: String, cards: List<ChatCard>): List<ChatCard> {
        if (cards.size <= 1) {
            return cards
        }

        val profile = load(profileKey)
        if (profile.tagWeights.isEmpty() && profile.categoryWeights.isEmpty()) {
            return cards
        }

        val queryTokens = query.lowercase()
            .split(Regex("[^a-z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()

        val beforeScores = cards.map { baseScore(queryTokens, it) }
        val scored = cards.mapIndexed { index, card ->
            val searchable = "${card.title} ${card.why ?: ""}".lowercase()
            var score = baseScore(queryTokens, card)
            score += (profile.categoryWeights[card.type.uppercase()] ?: 0) * 1.8
            score += profile.tagWeights.entries.sumOf { (tag, weight) ->
                when {
                    searchable.contains(tag) && weight > 0 -> weight.toDouble() * 1.3
                    searchable.contains(tag) && weight < 0 -> weight.toDouble() * 1.6
                    else -> 0.0
                }
            }
            if (queryTokens.any { token -> token.length >= 3 && searchable.contains(token) }) {
                score += 0.5
            }
            Triple(card, score, index)
        }

        val sorted = scored
            .sortedWith(compareByDescending<Triple<ChatCard, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
        val afterScores = sorted.map { baseScore(queryTokens, it) }
        val beforeTop3 = beforeScores.take(3).averageOrZero()
        val afterTop3 = afterScores.take(3).averageOrZero()
        val improved = if (beforeTop3 <= 0.0) afterTop3 > 0.0 else afterTop3 >= (beforeTop3 * 1.05)

        meterRegistry.summary("chat_reranker_proxy_score_before").record(beforeTop3)
        meterRegistry.summary("chat_reranker_proxy_score_after").record(afterTop3)
        meterRegistry.counter("chat_reranker_improved_total", "improved", improved.toString()).increment()

        return sorted
    }

    private fun mergeProfile(
        current: PreferenceProfileSnapshot,
        likeTags: List<String>,
        dislikeTags: List<String>,
        likeCategories: List<String>,
        dislikeCategories: List<String>,
    ): PreferenceProfileSnapshot {
        val tags = current.tagWeights.toMutableMap()
        val categories = current.categoryWeights.toMutableMap()

        likeTags.map { normalizeTag(it) }.filter { it.isNotBlank() }.forEach { tag ->
            tags[tag] = (tags[tag] ?: 0) + 1
        }
        dislikeTags.map { normalizeTag(it) }.filter { it.isNotBlank() }.forEach { tag ->
            tags[tag] = (tags[tag] ?: 0) - 1
        }

        likeCategories.map { it.trim().uppercase() }.filter { it.isNotBlank() }.forEach { category ->
            categories[category] = (categories[category] ?: 0) + 1
        }
        dislikeCategories.map { it.trim().uppercase() }.filter { it.isNotBlank() }.forEach { category ->
            categories[category] = (categories[category] ?: 0) - 1
        }

        return PreferenceProfileSnapshot(
            tagWeights = tags.toMap(),
            categoryWeights = categories.toMap(),
        )
    }

    private fun store(profileKey: String, profile: PreferenceProfileSnapshot) {
        val key = prefKey(profileKey)
        val payload = StoredPreferenceProfile(
            tagWeights = profile.tagWeights,
            categoryWeights = profile.categoryWeights,
            updatedAt = OffsetDateTime.now().toString(),
        )
        fallbackStore[key] = payload

        runCatching {
            redisTemplate.opsForValue().set(
                key,
                objectMapper.writeValueAsString(payload),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(3600)),
            )
            meterRegistry.counter("chat_pref_feedback_total", "result", "store_success").increment()
        }.onFailure {
            meterRegistry.counter("chat_pref_feedback_total", "result", "store_error").increment()
        }
    }

    private fun normalizeTag(value: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), "_").take(40)
    }

    private fun prefKey(profileKey: String): String {
        return "chat:pref:$profileKey"
    }

    private data class StoredPreferenceProfile(
        val tagWeights: Map<String, Int> = emptyMap(),
        val categoryWeights: Map<String, Int> = emptyMap(),
        val updatedAt: String = OffsetDateTime.now().toString(),
    )

    private fun baseScore(queryTokens: Set<String>, card: ChatCard): Double {
        val searchable = "${card.title} ${card.why ?: ""}".lowercase()
        val overlap = queryTokens.count { searchable.contains(it) }
        val sourceQuality = if (card.source.any { it.snippet.length >= 24 }) 0.7 else 0.2
        return (overlap * 0.35) + (card.source.size * 0.45) + sourceQuality
    }

    private fun List<Double>.averageOrZero(): Double {
        if (isEmpty()) return 0.0
        return average()
    }

    private fun StoredPreferenceProfile.toSnapshot(): PreferenceProfileSnapshot {
        return PreferenceProfileSnapshot(
            tagWeights = tagWeights,
            categoryWeights = categoryWeights,
        )
    }
}

data class PreferenceProfileSnapshot(
    val tagWeights: Map<String, Int> = emptyMap(),
    val categoryWeights: Map<String, Int> = emptyMap(),
)
