package com.devoceanblue.stayvista.domain.locale

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import java.security.MessageDigest
import java.util.Locale
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LocaleService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun getLocale(
        userId: Long?,
        sessionId: String?,
        acceptLanguage: String?,
        countryHint: String?,
    ): LocaleData {
        val fromUser = userId?.let { loadUserLocale(it) }
        if (fromUser != null) {
            return fromUser.copy(source = "stored_user")
        }

        val fromSession = sessionId?.let { loadSessionLocale(it) }
        if (fromSession != null) {
            return fromSession.copy(source = "stored_session")
        }

        val inferredCountry = inferCountry(countryHint, acceptLanguage)
        val inferredLanguage = inferLanguage(acceptLanguage, inferredCountry)
        val inferredCurrency = defaultCurrency(inferredCountry)
        return LocaleData(
            country = inferredCountry,
            currency = inferredCurrency,
            language = inferredLanguage,
            source = "inferred",
        )
    }

    fun overrideLocale(
        userId: Long?,
        sessionId: String?,
        request: LocaleOverrideRequest,
        acceptLanguage: String?,
    ): LocaleData {
        val country = request.country.trim().uppercase(Locale.ROOT)
        val currency = request.currency.trim().uppercase(Locale.ROOT)
        val language = request.language?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: inferLanguage(acceptLanguage, country)

        if (country.length !in 2..3) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "country must be ISO code",
            )
        }
        if (currency.length !in 3..5) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "currency must be ISO code",
            )
        }

        if (userId != null) {
            runCatching {
                jdbcTemplate.update(
                    """
                    INSERT INTO user_locale(user_id, country, currency, language, source)
                    VALUES (?, ?, ?, ?, 'manual')
                    ON DUPLICATE KEY UPDATE
                      country = VALUES(country),
                      currency = VALUES(currency),
                      language = VALUES(language),
                      source = 'manual',
                      updated_at = NOW(3)
                    """.trimIndent(),
                    userId,
                    country,
                    currency,
                    language,
                )
            }
        }

        val resolvedSessionId = sessionId?.takeIf { it.isNotBlank() } ?: return LocaleData(
            country = country,
            currency = currency,
            language = language,
            source = "manual",
        )

        runCatching {
            jdbcTemplate.update(
                """
                INSERT INTO session_locale(session_id, country, currency, language, source)
                VALUES (?, ?, ?, ?, 'manual')
                ON DUPLICATE KEY UPDATE
                  country = VALUES(country),
                  currency = VALUES(currency),
                  language = VALUES(language),
                  source = 'manual',
                  updated_at = NOW(3)
                """.trimIndent(),
                resolvedSessionId,
                country,
                currency,
                language,
            )
        }

        return LocaleData(
            country = country,
            currency = currency,
            language = language,
            source = "manual",
        )
    }

    fun resolveSessionId(anonId: String?, remoteAddr: String?, userAgent: String?): String {
        val normalizedAnon = anonId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedAnon != null) {
            return normalizedAnon
        }
        val payload = "${remoteAddr ?: "0.0.0.0"}|${userAgent ?: "ua"}"
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return "anon_${hash.take(10).joinToString("") { "%02x".format(it) }}"
    }

    private fun loadUserLocale(userId: Long): LocaleData? {
        return runCatching {
            jdbcTemplate.query(
                """
                SELECT country, currency, language
                FROM user_locale
                WHERE user_id = ?
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    LocaleData(
                        country = rs.getString("country"),
                        currency = rs.getString("currency"),
                        language = rs.getString("language"),
                        source = "stored_user",
                    )
                },
                userId,
            ).firstOrNull()
        }.getOrNull()
    }

    private fun loadSessionLocale(sessionId: String): LocaleData? {
        return runCatching {
            jdbcTemplate.query(
                """
                SELECT country, currency, language
                FROM session_locale
                WHERE session_id = ?
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    LocaleData(
                        country = rs.getString("country"),
                        currency = rs.getString("currency"),
                        language = rs.getString("language"),
                        source = "stored_session",
                    )
                },
                sessionId,
            ).firstOrNull()
        }.getOrNull()
    }

    private fun inferCountry(countryHint: String?, acceptLanguage: String?): String {
        val fromHint = countryHint?.trim()?.uppercase(Locale.ROOT)
        if (!fromHint.isNullOrBlank()) {
            return fromHint
        }

        val languageHeader = acceptLanguage?.trim().orEmpty()
        val language = languageHeader.split(",").firstOrNull().orEmpty()
        return when {
            language.contains("-KR", ignoreCase = true) -> "KR"
            language.contains("-JP", ignoreCase = true) -> "JP"
            language.contains("-US", ignoreCase = true) -> "US"
            language.contains("-GB", ignoreCase = true) -> "GB"
            language.contains("-FR", ignoreCase = true) -> "FR"
            language.contains("-DE", ignoreCase = true) -> "DE"
            else -> "KR"
        }
    }

    private fun inferLanguage(acceptLanguage: String?, country: String): String {
        val topLanguage = acceptLanguage?.split(",")?.firstOrNull()
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

        if (topLanguage != null) {
            return topLanguage
        }
        return when (country.uppercase(Locale.ROOT)) {
            "KR" -> "ko"
            "JP" -> "ja"
            "US", "GB" -> "en"
            "FR" -> "fr"
            "DE" -> "de"
            else -> "en"
        }
    }

    private fun defaultCurrency(country: String): String {
        return when (country.uppercase(Locale.ROOT)) {
            "KR" -> "KRW"
            "JP" -> "JPY"
            "US" -> "USD"
            "GB", "FR", "DE", "IT", "ES", "NL" -> "EUR"
            else -> "USD"
        }
    }
}

data class LocaleData(
    val country: String,
    val currency: String,
    val language: String,
    val source: String,
)

data class LocaleOverrideRequest(
    val country: String,
    val currency: String,
    val language: String? = null,
)
