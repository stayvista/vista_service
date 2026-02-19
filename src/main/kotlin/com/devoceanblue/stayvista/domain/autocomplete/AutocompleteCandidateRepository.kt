package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AutocompleteCandidateRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun searchDatabaseCandidates(
        normalizedQ: String,
        types: Set<PlaceType>,
        size: Int,
    ): List<AutocompleteCandidate> {
        if (normalizedQ.isBlank()) {
            return emptyList()
        }

        val sqlLike = "%${normalizedQ.lowercase()}%"
        val candidates = mutableListOf<AutocompleteCandidate>()

        if (types.contains(PlaceType.CITY)) {
            candidates += searchCities(sqlLike, size)
        }
        if (types.contains(PlaceType.PROPERTY)) {
            candidates += searchProperties(sqlLike, size)
        }
        if (types.contains(PlaceType.POI)) {
            candidates += searchPois(sqlLike, size)
        }
        if (types.contains(PlaceType.STATION)) {
            candidates += filterStatic(sqlLike, PlaceType.STATION)
        }
        if (types.contains(PlaceType.AIRPORT)) {
            candidates += filterStatic(sqlLike, PlaceType.AIRPORT)
        }

        return candidates
    }

    fun loadPopularCandidates(
        types: Set<PlaceType>,
        size: Int,
    ): List<AutocompleteCandidate> {
        val candidates = mutableListOf<AutocompleteCandidate>()

        if (types.contains(PlaceType.CITY)) {
            candidates += popularCities(size.coerceAtLeast(3))
        }
        if (types.contains(PlaceType.PROPERTY)) {
            candidates += popularProperties(size.coerceAtLeast(3))
        }
        if (types.contains(PlaceType.POI)) {
            candidates += popularPois(size.coerceAtLeast(3))
        }
        if (types.contains(PlaceType.STATION)) {
            candidates += staticSuggestions
                .filter { it.type == PlaceType.STATION }
                .take(4)
                .map { it.copy(source = "popular", bucket = "popular") }
        }
        if (types.contains(PlaceType.AIRPORT)) {
            candidates += staticSuggestions
                .filter { it.type == PlaceType.AIRPORT }
                .take(4)
                .map { it.copy(source = "popular", bucket = "popular") }
        }

        return candidates.take(size.coerceAtLeast(1) * 2)
    }

    fun resolvePoiCity(canonicalId: String): String? {
        val poiId = canonicalId.toLongOrNull() ?: return null
        return jdbcTemplate.query(
            """
            SELECT city
            FROM poi
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("city") },
            poiId,
        ).firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun searchCities(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT city, COUNT(*) AS popularity
            FROM (
                SELECT city
                FROM property
                WHERE status = 'ACTIVE'
                  AND city IS NOT NULL
                  AND city <> ''
                UNION ALL
                SELECT city
                FROM poi
                WHERE city IS NOT NULL
                  AND city <> ''
            ) c
            WHERE LOWER(c.city) LIKE ?
            GROUP BY city
            ORDER BY popularity DESC, city ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                AutocompleteCandidate(
                    type = PlaceType.CITY,
                    canonicalId = rs.getString("city"),
                    display = rs.getString("city"),
                    subtitle = "City",
                    score = rs.getLong("popularity").toDouble(),
                    source = "db",
                )
            },
            sqlLike,
            size.coerceAtLeast(1) * 2,
        )
    }

    private fun searchProperties(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT p.id, p.name, p.city, COALESCE(p.rating, 0) AS rating
            FROM property p
            WHERE p.status = 'ACTIVE'
              AND LOWER(p.name) LIKE ?
            ORDER BY rating DESC, p.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                AutocompleteCandidate(
                    type = PlaceType.PROPERTY,
                    canonicalId = rs.getLong("id").toString(),
                    display = rs.getString("name"),
                    subtitle = rs.getString("city"),
                    score = rs.getDouble("rating"),
                    source = "db",
                )
            },
            sqlLike,
            size.coerceAtLeast(1) * 2,
        )
    }

    private fun searchPois(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT id, name, category, city, lat, lng
            FROM poi
            WHERE LOWER(name) LIKE ?
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val category = rs.getString("category")
                val city = rs.getString("city")
                AutocompleteCandidate(
                    type = PlaceType.POI,
                    canonicalId = rs.getLong("id").toString(),
                    display = rs.getString("name"),
                    subtitle = listOfNotNull(category, city)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() },
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lng = rs.getBigDecimal("lng")?.toDouble(),
                    score = 1.0,
                    source = "db",
                )
            },
            sqlLike,
            size.coerceAtLeast(1) * 2,
        )
    }

    private fun popularCities(size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT city, COUNT(*) AS popularity
            FROM (
                SELECT city
                FROM property
                WHERE status = 'ACTIVE'
                  AND city IS NOT NULL
                  AND city <> ''
                UNION ALL
                SELECT city
                FROM poi
                WHERE city IS NOT NULL
                  AND city <> ''
            ) c
            GROUP BY city
            ORDER BY popularity DESC, city ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                AutocompleteCandidate(
                    type = PlaceType.CITY,
                    canonicalId = rs.getString("city"),
                    display = rs.getString("city"),
                    subtitle = "Popular city",
                    score = rs.getLong("popularity").toDouble(),
                    source = "popular",
                    bucket = "popular",
                )
            },
            size,
        )
    }

    private fun popularProperties(size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT p.id, p.name, p.city, COALESCE(p.rating, 0) AS rating
            FROM property p
            WHERE p.status = 'ACTIVE'
            ORDER BY rating DESC, p.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                AutocompleteCandidate(
                    type = PlaceType.PROPERTY,
                    canonicalId = rs.getLong("id").toString(),
                    display = rs.getString("name"),
                    subtitle = rs.getString("city"),
                    score = rs.getDouble("rating"),
                    source = "popular",
                    bucket = "popular",
                )
            },
            size,
        )
    }

    private fun popularPois(size: Int): List<AutocompleteCandidate> {
        return jdbcTemplate.query(
            """
            SELECT id, name, category, city, lat, lng
            FROM poi
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val category = rs.getString("category")
                val city = rs.getString("city")
                AutocompleteCandidate(
                    type = PlaceType.POI,
                    canonicalId = rs.getLong("id").toString(),
                    display = rs.getString("name"),
                    subtitle = listOfNotNull(category, city)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() },
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lng = rs.getBigDecimal("lng")?.toDouble(),
                    score = 1.0,
                    source = "popular",
                    bucket = "popular",
                )
            },
            size,
        )
    }

    private fun filterStatic(sqlLike: String, type: PlaceType): List<AutocompleteCandidate> {
        val keyword = sqlLike.removePrefix("%").removeSuffix("%")
        return staticSuggestions
            .asSequence()
            .filter { it.type == type }
            .filter {
                it.display.lowercase().contains(keyword) ||
                    (it.subtitle?.lowercase()?.contains(keyword) ?: false)
            }
            .take(6)
            .map { it.copy(source = "db") }
            .toList()
    }

    companion object {
        private val staticSuggestions: List<AutocompleteCandidate> = listOf(
            AutocompleteCandidate(
                type = PlaceType.STATION,
                canonicalId = "SEOUL_STATION",
                display = "Seoul Station",
                subtitle = "KTX · Seoul",
                lat = 37.5547,
                lng = 126.9706,
                source = "db",
            ),
            AutocompleteCandidate(
                type = PlaceType.STATION,
                canonicalId = "BUSAN_STATION",
                display = "Busan Station",
                subtitle = "KTX · Busan",
                lat = 35.1151,
                lng = 129.0414,
                source = "db",
            ),
            AutocompleteCandidate(
                type = PlaceType.STATION,
                canonicalId = "DAEJEON_STATION",
                display = "Daejeon Station",
                subtitle = "KTX · Daejeon",
                lat = 36.3326,
                lng = 127.4340,
                source = "db",
            ),
            AutocompleteCandidate(
                type = PlaceType.AIRPORT,
                canonicalId = "ICN",
                display = "Incheon International Airport",
                subtitle = "Seoul metropolitan",
                lat = 37.4602,
                lng = 126.4407,
                source = "db",
            ),
            AutocompleteCandidate(
                type = PlaceType.AIRPORT,
                canonicalId = "GMP",
                display = "Gimpo International Airport",
                subtitle = "Seoul",
                lat = 37.5583,
                lng = 126.7906,
                source = "db",
            ),
            AutocompleteCandidate(
                type = PlaceType.AIRPORT,
                canonicalId = "PUS",
                display = "Gimhae International Airport",
                subtitle = "Busan",
                lat = 35.1796,
                lng = 128.9382,
                source = "db",
            ),
        )
    }
}
