package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class DestinationRecommendationService(
    private val jdbcTemplate: JdbcTemplate,
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
            districts = loadDistricts(city, normalizedLimit),
            pois = loadPois(city, normalizedLimit),
            featured_properties = loadFeaturedProperties(city, normalizedLimit),
            country_popular_cities = loadCountryPopularCities(country, city, normalizedLimit),
        )
    }

    private fun resolveCity(cityId: String?, placeId: String?): String {
        val fromCity = cityId?.trim()?.takeIf { it.isNotBlank() }
        if (fromCity != null) {
            return fromCity
        }

        if (!placeId.isNullOrBlank() && !placeId.contains(':')) {
            return placeId.trim()
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId)
        if (parsed != null && parsed.type == PlaceType.CITY) {
            return parsed.canonicalId
        }
        return "Seoul"
    }

    private fun resolveCountry(city: String): String {
        val fromProperty = jdbcTemplate.query(
            """
            SELECT country
            FROM property
            WHERE city = ?
              AND status = 'ACTIVE'
              AND country IS NOT NULL
              AND country <> ''
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString("country") },
            city,
        ).firstOrNull()

        if (!fromProperty.isNullOrBlank()) {
            return fromProperty
        }

        return when (city) {
            "Seoul", "Busan", "Jeju" -> "KR"
            "Tokyo", "Osaka", "Kyoto" -> "JP"
            else -> "KR"
        }
    }

    private fun loadDistricts(city: String, limit: Int): List<DistrictRecommendation> {
        val fromTable = jdbcTemplate.query(
            """
            SELECT id, name, blurb, rank_score
            FROM district
            WHERE city = ?
            ORDER BY rank_score DESC, id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                DistrictRecommendation(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    blurb = rs.getString("blurb"),
                    rank = rs.getInt("rank_score"),
                )
            },
            city,
            limit,
        )
        if (fromTable.isNotEmpty()) {
            return fromTable
        }

        return jdbcTemplate.query(
            """
            SELECT district_name, COUNT(*) AS cnt
            FROM property
            WHERE city = ?
              AND status = 'ACTIVE'
              AND district_name IS NOT NULL
              AND district_name <> ''
            GROUP BY district_name
            ORDER BY cnt DESC, district_name ASC
            LIMIT ?
            """.trimIndent(),
            { rs, idx ->
                DistrictRecommendation(
                    id = (idx + 1).toLong(),
                    name = rs.getString("district_name"),
                    blurb = "${rs.getString("district_name")} 중심 숙소",
                    rank = rs.getInt("cnt"),
                )
            },
            city,
            limit,
        )
    }

    private fun loadPois(city: String, limit: Int): List<PoiRecommendation> {
        val ranked = jdbcTemplate.query(
            """
            SELECT p.id, p.name, p.category, cpp.rank_score
            FROM city_poi_popular cpp
            JOIN poi p ON p.id = cpp.poi_id
            WHERE cpp.city = ?
              AND p.active = 1
            ORDER BY cpp.rank_score DESC, p.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                PoiRecommendation(
                    poi_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("category") ?: "attraction",
                    rank = rs.getInt("rank_score"),
                )
            },
            city,
            limit,
        )
        if (ranked.isNotEmpty()) {
            return ranked
        }

        return jdbcTemplate.query(
            """
            SELECT id, name, category, popularity_score
            FROM poi
            WHERE city = ?
              AND active = 1
            ORDER BY popularity_score DESC, rating_score DESC, id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                PoiRecommendation(
                    poi_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("category") ?: "attraction",
                    rank = rs.getInt("popularity_score"),
                )
            },
            city,
            limit,
        )
    }

    private fun loadFeaturedProperties(city: String, limit: Int): List<FeaturedPropertyRecommendation> {
        val curated = jdbcTemplate.query(
            """
            SELECT p.id, p.name, p.thumbnail_url, p.star_rating, cfp.rank_score
            FROM city_featured_property cfp
            JOIN property p ON p.id = cfp.property_id
            WHERE cfp.city = ?
              AND p.status = 'ACTIVE'
            ORDER BY cfp.rank_score DESC, p.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                FeaturedPropertyRecommendation(
                    property_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    thumb = rs.getString("thumbnail_url"),
                    stars = rs.getInt("star_rating"),
                    rank = rs.getInt("rank_score"),
                )
            },
            city,
            limit,
        )
        if (curated.isNotEmpty()) {
            return curated
        }

        return jdbcTemplate.query(
            """
            SELECT id, name, thumbnail_url, star_rating, rating, popularity_score
            FROM property
            WHERE city = ?
              AND status = 'ACTIVE'
            ORDER BY rating DESC, popularity_score DESC, id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                FeaturedPropertyRecommendation(
                    property_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    thumb = rs.getString("thumbnail_url"),
                    stars = rs.getInt("star_rating"),
                    rank = rs.getInt("popularity_score"),
                )
            },
            city,
            limit,
        )
    }

    private fun loadCountryPopularCities(country: String, city: String, limit: Int): List<PopularCityRecommendation> {
        val candidates = jdbcTemplate.query(
            """
            SELECT p.city, COUNT(*) AS cnt
            FROM property p
            WHERE p.status = 'ACTIVE'
              AND p.country = ?
            GROUP BY p.city
            ORDER BY cnt DESC, p.city ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val cityName = rs.getString("city")
                PopularCityRecommendation(
                    city = cityName,
                    country = country,
                    property_count = rs.getInt("cnt"),
                    highlights = cityHighlights(cityName),
                )
            },
            country,
            limit + 2,
        ).filter { it.city != city }
            .take(limit)

        if (candidates.isNotEmpty()) {
            return candidates
        }

        return listOf(
            PopularCityRecommendation("Seoul", "KR", 0, "쇼핑, 레스토랑"),
            PopularCityRecommendation("Busan", "KR", 0, "해변, 레스토랑"),
            PopularCityRecommendation("Jeju", "KR", 0, "자연경관, 해변"),
        )
    }

    private fun cityHighlights(city: String): String {
        val categories = jdbcTemplate.query(
            """
            SELECT category, COUNT(*) AS cnt
            FROM poi
            WHERE city = ?
              AND active = 1
              AND category IS NOT NULL
              AND category <> ''
            GROUP BY category
            ORDER BY cnt DESC, category ASC
            LIMIT 2
            """.trimIndent(),
            { rs, _ -> rs.getString("category") },
            city,
        )
        if (categories.isEmpty()) {
            return "인기 여행지"
        }
        return categories.joinToString(", ") { categoryLabel(it) }
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
