package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.stereotype.Service

@Service
class DestinationRecommendationService(
    private val mapper: DestinationRecommendationMapper,
) {
    fun recommend(
        cityId: String?,
        placeId: String?,
        lang: String,
        limit: Int,
    ): DestinationRecommendationData {
        val city = resolveCity(cityId, placeId)
        val country = resolveCountry(city)
        val normalizedLimit = limit.coerceIn(4, 24)
        return DestinationRecommendationData(
            city = city,
            country = country,
            lang = lang,
            districts = safeSection(emptyList()) { loadDistricts(city, normalizedLimit) },
            pois = safeSection(emptyList()) { loadPois(city, normalizedLimit) },
            featured_properties = safeSection(emptyList()) { loadFeaturedProperties(city, normalizedLimit) },
            country_popular_cities = safeSection(defaultPopularCities(country)) {
                loadCountryPopularCities(country, city, normalizedLimit)
            },
        )
    }

    private fun resolveCity(cityId: String?, placeId: String?): String {
        val fromCity = cityId?.trim()?.takeIf { it.isNotBlank() }
        if (fromCity != null) {
            return CityCanonicalizer.canonicalize(fromCity) ?: fromCity
        }

        if (!placeId.isNullOrBlank() && !placeId.contains(':')) {
            return CityCanonicalizer.canonicalize(placeId) ?: placeId.trim()
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId)
        if (parsed != null && parsed.type == PlaceType.CITY) {
            return CityCanonicalizer.canonicalize(parsed.canonicalId) ?: parsed.canonicalId
        }
        return "Seoul"
    }

    private fun resolveCountry(city: String): String {
        val normalizedCity = CityCanonicalizer.canonicalize(city) ?: city
        val fromProperty = mapper.findCountryByCity(normalizedCity)

        if (!fromProperty.isNullOrBlank()) {
            return fromProperty
        }

        return when (normalizedCity) {
            "Seoul", "Busan", "Jeju" -> "KR"
            "Tokyo", "Osaka", "Kyoto" -> "JP"
            else -> "KR"
        }
    }

    private fun loadDistricts(city: String, limit: Int): List<DistrictRecommendation> {
        val fromTable = optionalQuery { mapper.listDistricts(city = city, limit = limit) }
        if (fromTable.isNotEmpty()) {
            return fromTable
        }

        return mapper.listFallbackDistricts(city = city, limit = limit)
            .mapIndexed { idx, row ->
                DistrictRecommendation(
                    id = (idx + 1).toLong(),
                    name = row.districtName,
                    blurb = "${row.districtName} 중심 숙소",
                    rank = row.count,
                )
            }
    }

    private fun loadPois(city: String, limit: Int): List<PoiRecommendation> {
        val ranked = optionalQuery { mapper.listRankedPois(city = city, limit = limit) }
        if (ranked.isNotEmpty()) {
            return ranked
        }

        return mapper.listFallbackPois(city = city, limit = limit)
    }

    private fun loadFeaturedProperties(city: String, limit: Int): List<FeaturedPropertyRecommendation> {
        val curated = optionalQuery { mapper.listCuratedProperties(city = city, limit = limit) }
        if (curated.isNotEmpty()) {
            return curated
        }

        return mapper.listFallbackProperties(city = city, limit = limit)
    }

    private fun loadCountryPopularCities(country: String, city: String, limit: Int): List<PopularCityRecommendation> {
        val candidates = mapper.listPopularCities(country = country, limit = limit + 2)
            .map { row ->
                PopularCityRecommendation(
                    city = row.city,
                    country = country,
                    property_count = row.count,
                    highlights = cityHighlights(row.city),
                )
            }.filter { it.city != city }
            .take(limit)

        if (candidates.isNotEmpty()) {
            return candidates
        }

        return defaultPopularCities(country)
    }

    private fun cityHighlights(city: String): String {
        val categories = mapper.listCityHighlightCategories(city)
        if (categories.isEmpty()) {
            return "인기 여행지"
        }
        return categories.joinToString(", ") { categoryLabel(it) }
    }

    private fun defaultPopularCities(country: String): List<PopularCityRecommendation> {
        return when (country) {
            "JP" -> listOf(
                PopularCityRecommendation("Tokyo", "JP", 0, "쇼핑, 미식"),
                PopularCityRecommendation("Osaka", "JP", 0, "먹거리, 관광"),
                PopularCityRecommendation("Kyoto", "JP", 0, "전통, 문화유산"),
            )

            else -> listOf(
                PopularCityRecommendation("Seoul", "KR", 0, "쇼핑, 레스토랑"),
                PopularCityRecommendation("Busan", "KR", 0, "해변, 레스토랑"),
                PopularCityRecommendation("Jeju", "KR", 0, "자연경관, 해변"),
            )
        }
    }

    private fun <T> optionalQuery(block: () -> List<T>): List<T> {
        return try {
            block()
        } catch (_: BadSqlGrammarException) {
            emptyList()
        }
    }

    private fun <T> safeSection(fallback: T, block: () -> T): T {
        return runCatching { block() }.getOrDefault(fallback)
    }

    private fun categoryLabel(code: String): String {
        return when (code.lowercase()) {
            "food" -> "레스토랑"
            "shopping" -> "쇼핑"
            "museum" -> "관광"
            "attraction" -> "관광"
            else -> code
        }
    }
}

data class DestinationRecommendationData(
    val city: String,
    val country: String,
    val lang: String,
    val districts: List<DistrictRecommendation>,
    val pois: List<PoiRecommendation>,
    val featured_properties: List<FeaturedPropertyRecommendation>,
    val country_popular_cities: List<PopularCityRecommendation>,
)

data class DistrictRecommendation(
    val id: Long,
    val name: String,
    val blurb: String?,
    val rank: Int,
)

data class PoiRecommendation(
    val poi_id: Long,
    val name: String,
    val category: String,
    val rank: Int,
)

data class FeaturedPropertyRecommendation(
    val property_id: Long,
    val name: String,
    val thumb: String?,
    val stars: Int,
    val rank: Int,
)

data class PopularCityRecommendation(
    val city: String,
    val country: String,
    val property_count: Int,
    val highlights: String,
)

data class DestinationDistrictFallbackRow(
    val districtName: String,
    val count: Int,
)

data class DestinationPopularCityRow(
    val city: String,
    val count: Int,
)

@Mapper
interface DestinationRecommendationMapper {
    @Select(
        """
        SELECT country
        FROM property
        WHERE city = #{city}
          AND status = 'ACTIVE'
          AND country IS NOT NULL
          AND country <> ''
        LIMIT 1
        """,
    )
    fun findCountryByCity(@Param("city") city: String): String?

    @Select(
        """
        SELECT id, name, blurb, rank_score AS rank
        FROM district
        WHERE city = #{city}
        ORDER BY rank_score DESC, id ASC
        LIMIT #{limit}
        """,
    )
    fun listDistricts(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<DistrictRecommendation>

    @Select(
        """
        SELECT district_name AS districtName, COUNT(*) AS count
        FROM property
        WHERE city = #{city}
          AND status = 'ACTIVE'
          AND district_name IS NOT NULL
          AND district_name <> ''
        GROUP BY district_name
        ORDER BY count DESC, district_name ASC
        LIMIT #{limit}
        """,
    )
    fun listFallbackDistricts(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<DestinationDistrictFallbackRow>

    @Select(
        """
        SELECT p.id AS poi_id, p.name, COALESCE(p.category, 'attraction') AS category, cpp.rank_score AS rank
        FROM city_poi_popular cpp
        JOIN poi p ON p.id = cpp.poi_id
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'POI'
         AND m.canonical_id = CONCAT('', p.id)
        WHERE cpp.city = #{city}
          AND p.active = 1
        ORDER BY cpp.rank_score DESC,
                 COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 p.id ASC
        LIMIT #{limit}
        """,
    )
    fun listRankedPois(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<PoiRecommendation>

    @Select(
        """
        SELECT id AS poi_id, name, COALESCE(category, 'attraction') AS category, popularity_score AS rank
        FROM poi
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'POI'
         AND m.canonical_id = CONCAT('', poi.id)
        WHERE city = #{city}
          AND active = 1
        ORDER BY COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 popularity_score DESC,
                 rating_score DESC,
                 id ASC
        LIMIT #{limit}
        """,
    )
    fun listFallbackPois(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<PoiRecommendation>

    @Select(
        """
        SELECT p.id AS property_id, p.name, p.thumbnail_url AS thumb, p.star_rating AS stars, cfp.rank_score AS rank
        FROM city_featured_property cfp
        JOIN property p ON p.id = cfp.property_id
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'PROPERTY'
         AND m.canonical_id = CONCAT('', p.id)
        WHERE cfp.city = #{city}
          AND p.status = 'ACTIVE'
        ORDER BY cfp.rank_score DESC,
                 COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 p.id ASC
        LIMIT #{limit}
        """,
    )
    fun listCuratedProperties(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<FeaturedPropertyRecommendation>

    @Select(
        """
        SELECT id AS property_id, name, thumbnail_url AS thumb, star_rating AS stars, popularity_score AS rank
        FROM property
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'PROPERTY'
         AND m.canonical_id = CONCAT('', property.id)
        WHERE city = #{city}
          AND status = 'ACTIVE'
        ORDER BY COALESCE(m.popularity_7d, 0) DESC,
                 COALESCE(m.ctr_7d, 0) DESC,
                 rating DESC,
                 popularity_score DESC,
                 id ASC
        LIMIT #{limit}
        """,
    )
    fun listFallbackProperties(
        @Param("city") city: String,
        @Param("limit") limit: Int,
    ): List<FeaturedPropertyRecommendation>

    @Select(
        """
        SELECT p.city, COUNT(*) AS count
        FROM property p
        LEFT JOIN ac_suggest_metric m
          ON m.type = 'CITY'
         AND m.canonical_id = p.city
        WHERE p.status = 'ACTIVE'
          AND p.country = #{country}
        GROUP BY p.city
        ORDER BY MAX(COALESCE(m.popularity_7d, 0)) DESC,
                 MAX(COALESCE(m.ctr_7d, 0)) DESC,
                 COUNT(*) DESC,
                 p.city ASC
        LIMIT #{limit}
        """,
    )
    fun listPopularCities(
        @Param("country") country: String,
        @Param("limit") limit: Int,
    ): List<DestinationPopularCityRow>

    @Select(
        """
        SELECT category
        FROM poi
        WHERE city = #{city}
          AND active = 1
          AND category IS NOT NULL
          AND category <> ''
        GROUP BY category
        ORDER BY COUNT(*) DESC, category ASC
        LIMIT 2
        """,
    )
    fun listCityHighlightCategories(@Param("city") city: String): List<String>
}
