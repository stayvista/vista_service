package com.devoceanblue.stayvista.domain.fx

import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class FxService(
    private val jdbcTemplate: JdbcTemplate,
    private val cache: SimpleTtlCache,
) {
    fun quote(base: String, quote: String): FxQuote {
        val normalizedBase = base.trim().uppercase()
        val normalizedQuote = quote.trim().uppercase()
        if (normalizedBase == normalizedQuote) {
            return FxQuote(normalizedBase, normalizedQuote, BigDecimal.ONE, nowIso())
        }

        val cacheKey = "fx:v1:$normalizedBase:$normalizedQuote"
        cache.get<FxQuote>(cacheKey)?.let { return it }

        val direct = loadRate(normalizedBase, normalizedQuote)
        val resolved = when {
            direct != null -> direct
            else -> {
                val inverse = loadRate(normalizedQuote, normalizedBase)
                if (inverse != null && inverse.rate.compareTo(BigDecimal.ZERO) > 0) {
                    FxQuote(
                        base = normalizedBase,
                        quote = normalizedQuote,
                        rate = BigDecimal.ONE.divide(inverse.rate, 8, RoundingMode.HALF_UP),
                        as_of = inverse.as_of,
                    )
                } else {
                    resolveViaKrwOrFallback(normalizedBase, normalizedQuote)
                }
            }
        }

        cache.put(cacheKey, ttlMillis = 300_000, value = resolved)
        return resolved
    }

    fun convert(amount: Long, from: String, to: String): Long {
        if (amount == 0L) {
            return 0L
        }
        val quote = quote(from, to)
        val converted = BigDecimal.valueOf(amount).multiply(quote.rate)
        return converted.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun resolveViaKrwOrFallback(base: String, quote: String): FxQuote {
        val viaKrw = runCatching {
            val toKrw = quote(base, "KRW")
            val fromKrw = quote("KRW", quote)
            FxQuote(
                base = base,
                quote = quote,
                rate = toKrw.rate.multiply(fromKrw.rate).setScale(8, RoundingMode.HALF_UP),
                as_of = listOf(toKrw.as_of, fromKrw.as_of).maxOrNull() ?: nowIso(),
            )
        }.getOrNull()

        if (viaKrw != null && viaKrw.rate.compareTo(BigDecimal.ZERO) > 0) {
            return viaKrw
        }

        val fallbackRate = STATIC_FALLBACK["$base:$quote"] ?: BigDecimal.ONE
        return FxQuote(base = base, quote = quote, rate = fallbackRate, as_of = nowIso())
    }

    private fun loadRate(base: String, quote: String): FxQuote? {
        return runCatching {
            jdbcTemplate.query(
                """
                SELECT base, quote, rate, as_of
                FROM fx_rate
                WHERE base = ?
                  AND quote = ?
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    FxQuote(
                        base = rs.getString("base"),
                        quote = rs.getString("quote"),
                        rate = rs.getBigDecimal("rate"),
                        as_of = rs.getTimestamp("as_of")?.toInstant()?.toString() ?: nowIso(),
                    )
                },
                base,
                quote,
            ).firstOrNull()
        }.getOrNull()
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))

    companion object {
        private val STATIC_FALLBACK: Map<String, BigDecimal> = mapOf(
            "KRW:KRW" to BigDecimal.ONE,
            "USD:USD" to BigDecimal.ONE,
            "JPY:JPY" to BigDecimal.ONE,
            "EUR:EUR" to BigDecimal.ONE,
            "USD:KRW" to BigDecimal("1320.00000000"),
            "KRW:USD" to BigDecimal("0.00075758"),
            "JPY:KRW" to BigDecimal("8.80000000"),
            "KRW:JPY" to BigDecimal("0.11363636"),
            "EUR:KRW" to BigDecimal("1430.00000000"),
            "KRW:EUR" to BigDecimal("0.00069930"),
            "USD:JPY" to BigDecimal("149.50000000"),
            "JPY:USD" to BigDecimal("0.00668896"),
            "USD:EUR" to BigDecimal("0.92000000"),
            "EUR:USD" to BigDecimal("1.08695652"),
        )
    }
}

data class FxQuote(
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val as_of: String,
)
