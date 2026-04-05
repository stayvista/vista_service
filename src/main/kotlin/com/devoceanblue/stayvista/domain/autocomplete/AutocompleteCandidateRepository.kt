package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Repository

@Repository
class AutocompleteCandidateRepository(
    private val mapper: AutocompleteCandidateMapper,
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
        return mapper.findPoiCity(poiId)
            ?.takeIf { it.isNotBlank() }
    }

    private fun searchCities(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return mapper.searchCities(sqlLike = sqlLike, limit = size.coerceAtLeast(1) * 2)
            .mapNotNull { it.toCandidate() }
    }

    private fun searchProperties(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return mapper.searchProperties(sqlLike = sqlLike, limit = size.coerceAtLeast(1) * 2)
            .mapNotNull { it.toCandidate() }
    }

    private fun searchPois(sqlLike: String, size: Int): List<AutocompleteCandidate> {
        return mapper.searchPois(sqlLike = sqlLike, limit = size.coerceAtLeast(1) * 2)
            .mapNotNull { it.toCandidate() }
    }

    private fun popularCities(size: Int): List<AutocompleteCandidate> {
        return mapper.popularCities(size).mapNotNull { it.toCandidate() }
    }

    private fun popularProperties(size: Int): List<AutocompleteCandidate> {
        return mapper.popularProperties(size).mapNotNull { it.toCandidate() }
    }

    private fun popularPois(size: Int): List<AutocompleteCandidate> {
        return mapper.popularPois(size).mapNotNull { it.toCandidate() }
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

data class AutocompleteCandidateRow(
    val type: String,
    val canonicalId: String,
    val display: String,
    val subtitle: String?,
    val lat: Double?,
    val lng: Double?,
    val score: Double?,
    val source: String,
    val bucket: String?,
) {
    fun toCandidate(): AutocompleteCandidate? {
        val placeType = runCatching { PlaceType.valueOf(type.uppercase()) }.getOrNull() ?: return null
        return AutocompleteCandidate(
            type = placeType,
            canonicalId = canonicalId,
            display = display,
            subtitle = subtitle,
            lat = lat,
            lng = lng,
            score = score ?: 0.0,
            source = source,
            bucket = bucket,
        )
    }
}

@Mapper
interface AutocompleteCandidateMapper {
    @Select("SELECT city FROM poi WHERE id = #{poiId}")
    fun findPoiCity(@Param("poiId") poiId: Long): String?

    @Select(
        """
        SELECT 'CITY' AS type,
               city AS canonicalId,
               city AS display,
               'City' AS subtitle,
               NULL AS lat,
               NULL AS lng,
               COUNT(*) * 1.0 AS score,
               'db' AS source,
               NULL AS bucket
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
        WHERE LOWER(c.city) LIKE #{sqlLike}
        GROUP BY city
        ORDER BY score DESC, city ASC
        LIMIT #{limit}
        """,
    )
    fun searchCities(
        @Param("sqlLike") sqlLike: String,
        @Param("limit") limit: Int,
    ): List<AutocompleteCandidateRow>

    @Select(
        """
        SELECT 'PROPERTY' AS type,
               CONCAT('', p.id) AS canonicalId,
               p.name AS display,
               p.city AS subtitle,
               NULL AS lat,
               NULL AS lng,
               COALESCE(p.rating, 0) AS score,
               'db' AS source,
               NULL AS bucket
        FROM property p
        WHERE p.status = 'ACTIVE'
          AND LOWER(p.name) LIKE #{sqlLike}
        ORDER BY score DESC, p.id ASC
        LIMIT #{limit}
        """,
    )
    fun searchProperties(
        @Param("sqlLike") sqlLike: String,
        @Param("limit") limit: Int,
    ): List<AutocompleteCandidateRow>

    @Select(
        """
        SELECT 'POI' AS type,
               CONCAT('', id) AS canonicalId,
               name AS display,
               TRIM(CONCAT(COALESCE(NULLIF(category, ''), ''), CASE WHEN city IS NOT NULL AND city <> '' THEN CONCAT(' · ', city) ELSE '' END)) AS subtitle,
               lat,
               lng,
               1.0 AS score,
               'db' AS source,
               NULL AS bucket
        FROM poi
        WHERE LOWER(name) LIKE #{sqlLike}
        ORDER BY id ASC
        LIMIT #{limit}
        """,
    )
    fun searchPois(
        @Param("sqlLike") sqlLike: String,
        @Param("limit") limit: Int,
    ): List<AutocompleteCandidateRow>

    @Select(
        """
        SELECT 'CITY' AS type,
               city AS canonicalId,
               city AS display,
               'Popular city' AS subtitle,
               NULL AS lat,
               NULL AS lng,
               COALESCE(m.popularity_7d * 1.0, COUNT(*) * 1.0) AS score,
               'popular' AS source,
               'popular' AS bucket
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
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'CITY'
         AND m.canonical_id = c.city
        GROUP BY city, m.popularity_7d, m.ctr_7d
        ORDER BY COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 COUNT(*) DESC,
                 city ASC
        LIMIT #{limit}
        """,
    )
    fun popularCities(@Param("limit") limit: Int): List<AutocompleteCandidateRow>

    @Select(
        """
        SELECT 'PROPERTY' AS type,
               CONCAT('', p.id) AS canonicalId,
               p.name AS display,
               p.city AS subtitle,
               NULL AS lat,
               NULL AS lng,
               COALESCE(m.popularity_7d * 1.0, COALESCE(p.rating, 0)) AS score,
               'popular' AS source,
               'popular' AS bucket
        FROM property p
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'PROPERTY'
         AND m.canonical_id = CONCAT('', p.id)
        WHERE p.status = 'ACTIVE'
        ORDER BY COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 COALESCE(p.rating, 0) DESC,
                 p.id ASC
        LIMIT #{limit}
        """,
    )
    fun popularProperties(@Param("limit") limit: Int): List<AutocompleteCandidateRow>

    @Select(
        """
        SELECT 'POI' AS type,
               CONCAT('', id) AS canonicalId,
               name AS display,
               TRIM(CONCAT(COALESCE(NULLIF(category, ''), ''), CASE WHEN city IS NOT NULL AND city <> '' THEN CONCAT(' · ', city) ELSE '' END)) AS subtitle,
               lat,
               lng,
               COALESCE(m.popularity_7d * 1.0, 1.0) AS score,
               'popular' AS source,
               'popular' AS bucket
        FROM poi
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'POI'
         AND m.canonical_id = CONCAT('', id)
        ORDER BY COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 id ASC
        LIMIT #{limit}
        """,
    )
    fun popularPois(@Param("limit") limit: Int): List<AutocompleteCandidateRow>
}
