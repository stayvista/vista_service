package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.PlaceType
import io.micrometer.core.instrument.MeterRegistry
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import org.springframework.stereotype.Service

@Service
class AutocompleteService(
    private val cacheService: AutocompleteCacheService,
    private val openSearchGateway: AutocompleteOpenSearchGateway,
    private val candidateRepository: AutocompleteCandidateRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val osConsecutiveFailures = AtomicInteger(0)
    private val osDegradeUntilEpochMs = AtomicLong(0)

    fun autocomplete(query: AutocompleteQuery): AutocompleteData {
        val startedAtNs = System.nanoTime()
        val normalizedQ = normalizeQuery(query.q)
        if (normalizedQ.length > MAX_QUERY_LENGTH) {
            meterRegistry.counter("ac_reject_total", "reason", "q_too_long").increment()
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "q is too long",
                details = mapOf("max_length" to MAX_QUERY_LENGTH),
            )
        }

        val requestedSize = query.size.coerceIn(1, 20)
        val effectiveSize = if (normalizedQ.length == 1) {
            meterRegistry.counter("ac_reject_total", "reason", "len1_limited").increment()
            min(requestedSize, 8)
        } else {
            requestedSize
        }

        val normalizedTypes = query.types.ifEmpty {
            linkedSetOf(
                PlaceType.CITY,
                PlaceType.PROPERTY,
                PlaceType.POI,
                PlaceType.STATION,
                PlaceType.AIRPORT,
            )
        }

        val normalizedLang = query.lang.lowercase(Locale.ROOT)

        val result = if (normalizedQ.isBlank()) {
            meterRegistry.counter("ac_empty_query_total").increment()
            loadEmptyQueryResult(
                principalKey = query.principalKey,
                types = normalizedTypes,
                size = effectiveSize,
                lang = normalizedLang,
                startedAtNs = startedAtNs,
            )
        } else {
            loadTypedQueryResult(
                normalizedQ = normalizedQ,
                types = normalizedTypes,
                size = effectiveSize,
                lang = normalizedLang,
                principalKey = query.principalKey,
                startedAtNs = startedAtNs,
            )
        }

        meterRegistry.counter(
            "ac_req_total",
            "cache_hit",
            result.meta.cache_hit.toString(),
            "source",
            result.items.firstOrNull()?.source ?: "none",
        ).increment()
        meterRegistry.timer(
            "ac_latency_ms",
            "source",
            result.items.firstOrNull()?.source ?: "none",
        ).record(Duration.ofNanos(System.nanoTime() - startedAtNs))

        return result
    }

    private fun loadEmptyQueryResult(
        principalKey: String,
        types: Set<PlaceType>,
        size: Int,
        lang: String,
        startedAtNs: Long,
    ): AutocompleteData {
        val recent = cacheService.getRecent(principalKey)
            .filter { types.contains(it.type) }
            .map { it.copy(source = "recent", bucket = "recent") }

        val popularKey = cacheService.popularKey(lang = lang, types = types, size = size)
        val popularFromCache = cacheService.getSuggestions(popularKey)
        val popular = if (popularFromCache != null) {
            popularFromCache.map { it.copy(source = "popular", bucket = "popular") }
        } else {
            val loaded = candidateRepository.loadPopularCandidates(types = types, size = size)
                .map { it.copy(source = "popular", bucket = "popular") }
            cacheService.putPopularSuggestions(popularKey, loaded)
            loaded
        }

        val merged = dedupeAndLimit(
            candidates = recent + popular,
            limit = size,
        )

        return buildResponse(
            q = "",
            items = merged,
            size = size,
            types = types,
            lang = lang,
            cacheHit = popularFromCache != null,
            tookMs = elapsedMs(startedAtNs),
            highlight = null,
        )
    }

    private fun loadTypedQueryResult(
        normalizedQ: String,
        types: Set<PlaceType>,
        size: Int,
        lang: String,
        principalKey: String,
        startedAtNs: Long,
    ): AutocompleteData {
        val cacheKey = cacheService.queryKey(
            lang = lang,
            types = types,
            normalizedQ = cacheNormalizedQ(normalizedQ),
            size = size,
        )
        cacheService.getSuggestions(cacheKey)?.let { cached ->
            return buildResponse(
                q = normalizedQ,
                items = dedupeAndLimit(cached, size),
                size = size,
                types = types,
                lang = lang,
                cacheHit = true,
                tookMs = elapsedMs(startedAtNs),
                highlight = normalizedQ,
            )
        }

        val freshCandidates = fetchFreshCandidates(normalizedQ, types, size, lang)
        val finalCandidates = if (freshCandidates.isEmpty()) {
            val fallback = loadEmptyQueryResult(
                principalKey = principalKey,
                types = types,
                size = size,
                lang = lang,
                startedAtNs = startedAtNs,
            )
            return fallback.copy(q = normalizedQ)
        } else {
            dedupeAndLimit(freshCandidates, size)
        }

        cacheService.putQuerySuggestions(cacheKey, finalCandidates)

        return buildResponse(
            q = normalizedQ,
            items = finalCandidates,
            size = size,
            types = types,
            lang = lang,
            cacheHit = false,
            tookMs = elapsedMs(startedAtNs),
            highlight = normalizedQ,
        )
    }

    private fun fetchFreshCandidates(
        normalizedQ: String,
        types: Set<PlaceType>,
        size: Int,
        lang: String,
    ): List<AutocompleteCandidate> {
        if (isOpenSearchDegraded()) {
            return candidateRepository.searchDatabaseCandidates(
                normalizedQ = normalizedQ,
                types = types,
                size = size,
            )
        }

        return try {
            val fromOpenSearch = openSearchGateway.search(
                q = normalizedQ,
                types = types,
                size = size,
                lang = lang,
            )
            if (fromOpenSearch.isNotEmpty()) {
                osConsecutiveFailures.set(0)
                dedupeAndLimit(fromOpenSearch, size)
            } else {
                candidateRepository.searchDatabaseCandidates(
                    normalizedQ = normalizedQ,
                    types = types,
                    size = size,
                )
            }
        } catch (_: Exception) {
            recordOpenSearchFailure()
            candidateRepository.searchDatabaseCandidates(
                normalizedQ = normalizedQ,
                types = types,
                size = size,
            )
        }
    }

    private fun isOpenSearchDegraded(): Boolean {
        return System.currentTimeMillis() < osDegradeUntilEpochMs.get()
    }

    private fun recordOpenSearchFailure() {
        val consecutive = osConsecutiveFailures.incrementAndGet()
        if (consecutive >= 3) {
            osDegradeUntilEpochMs.set(System.currentTimeMillis() + OPEN_SEARCH_DEGRADE_WINDOW_MS)
            osConsecutiveFailures.set(0)
        }
    }

    private fun buildResponse(
        q: String,
        items: List<AutocompleteCandidate>,
        size: Int,
        types: Set<PlaceType>,
        lang: String,
        cacheHit: Boolean,
        tookMs: Long,
        highlight: String?,
    ): AutocompleteData {
        return AutocompleteData(
            q = q,
            items = items.map { it.toItem(highlight = highlight) },
            meta = AutocompleteMeta(
                types = types.map { it.name },
                size = size,
                lang = lang,
                took_ms = tookMs,
                cache_hit = cacheHit,
            ),
        )
    }

    private fun dedupeAndLimit(
        candidates: List<AutocompleteCandidate>,
        limit: Int,
    ): List<AutocompleteCandidate> {
        val seen = linkedSetOf<String>()
        return candidates
            .filter { seen.add(it.dedupeKey()) }
            .take(limit.coerceAtLeast(1))
    }

    private fun cacheNormalizedQ(q: String): String {
        return q.lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), " ")
    }

    private fun normalizeQuery(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return Normalizer.normalize(raw, Normalizer.Form.NFC)
            .trim()
            .replace("\\s+".toRegex(), " ")
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 80
        private const val OPEN_SEARCH_DEGRADE_WINDOW_MS = 30_000L
    }
}
