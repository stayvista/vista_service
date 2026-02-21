package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SearchFacetService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun facets(placeId: String?, city: String?): SearchFacetData {
        val resolvedCity = resolveCity(placeId, city)
        return SearchFacetData(
            popular_filters = safeList { popularFilters(resolvedCity) },
            districts = safeList { districtFacets(resolvedCity) },
            nearby_attractions = safeList { nearbyAttractions(resolvedCity) },
            brands = safeList { brandFacets(resolvedCity) },
            amenity_groups = safeList { amenityGroups(resolvedCity) },
            stars = safeList { starFacets(resolvedCity) },
            property_types = safeList { propertyTypeFacets(resolvedCity) },
            payment_options = safeList { paymentOptionFacets(resolvedCity) },
            themes = safeList { themeFacets(resolvedCity) },
            amenities = safeList { amenityFacets(resolvedCity) },
        )
    }

    private fun <T> safeList(block: () -> List<T>): List<T> {
        return runCatching { block() }.getOrDefault(emptyList())
    }

    private fun resolveCity(placeId: String?, city: String?): String? {
        val plainCity = city?.trim()?.takeIf { it.isNotBlank() }
        if (plainCity != null) {
            return plainCity
        }

        if (!placeId.isNullOrBlank() && !placeId.contains(':')) {
            return placeId.trim()
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId) ?: return null
        return when (parsed.type) {
            PlaceType.CITY -> parsed.canonicalId
            PlaceType.POI -> {
                val poiId = parsed.canonicalId.toLongOrNull() ?: return null
                jdbcTemplate.query(
                    "SELECT city FROM poi WHERE id = ? LIMIT 1",
                    { rs, _ -> rs.getString("city") },
                    poiId,
                ).firstOrNull()
            }

            else -> null
        }
    }

    private fun popularFilters(city: String?): List<PopularFilterFacet> {
        val filters = mutableListOf<PopularFilterFacet>()
        val topPropertyType = propertyTypeFacets(city).take(2)
        topPropertyType.forEach { type ->
            filters += PopularFilterFacet(
                key = "property_type",
                value = type.key,
                label = type.label,
                count = type.count,
            )
        }
        val topAmenity = amenityFacets(city).take(3)
        topAmenity.forEach { amenity ->
            filters += PopularFilterFacet(
                key = "amenities",
                value = amenity.key,
                label = amenity.label,
                count = amenity.count,
            )
        }
        return filters
    }

    private fun districtFacets(city: String?): List<FacetDistrict> {
        val whereCity = if (city != null) "WHERE d.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT d.id, d.name, d.blurb, d.rank_score, COALESCE(p.cnt, 0) AS cnt
            FROM district d
            LEFT JOIN (
              SELECT district_name, city, COUNT(*) AS cnt
              FROM property
              WHERE status = 'ACTIVE'
              GROUP BY district_name, city
            ) p ON p.district_name = d.name AND p.city = d.city
            $whereCity
            ORDER BY d.rank_score DESC, cnt DESC, d.id ASC
            LIMIT 12
            """.trimIndent(),
            { rs, _ ->
                FacetDistrict(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    blurb = rs.getString("blurb"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun nearbyAttractions(city: String?): List<FacetAttraction> {
        if (city == null) {
            return emptyList()
        }
        return jdbcTemplate.query(
            """
            SELECT p.id, p.name, cpp.rank_score
            FROM city_poi_popular cpp
            JOIN poi p ON p.id = cpp.poi_id
            WHERE cpp.city = ?
            ORDER BY cpp.rank_score DESC, p.id ASC
            LIMIT 12
            """.trimIndent(),
            { rs, _ ->
                FacetAttraction(
                    poi_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    count = rs.getInt("rank_score"),
                )
            },
            city,
        )
    }

    private fun brandFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT b.id, b.name, COUNT(*) AS cnt
            FROM property_brand pb
            JOIN brand b ON b.id = pb.brand_id
            JOIN property p ON p.id = pb.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY b.id, b.name
            ORDER BY cnt DESC, b.name ASC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getLong("id").toString(),
                    label = rs.getString("name"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun amenityGroups(city: String?): List<AmenityGroupFacet> {
        val byAmenity = amenityFacets(city)
            .groupBy { it.group ?: "other" }
        return byAmenity.entries.map { (group, items) ->
            AmenityGroupFacet(
                group = group,
                items = items.sortedByDescending { it.count }.take(8),
            )
        }.sortedBy { it.group }
    }

    private fun starFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT star_rating, COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
            $whereCity
            GROUP BY star_rating
            ORDER BY star_rating DESC
            """.trimIndent(),
            { rs, _ ->
                val star = rs.getInt("star_rating")
                FacetCountItem(
                    key = star.toString(),
                    label = "${star}성급",
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun propertyTypeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT pt.code, pt.label_ko, COUNT(*) AS cnt
            FROM property p
            JOIN property_type pt ON pt.code = p.property_type_code
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY pt.code, pt.label_ko
            ORDER BY cnt DESC, pt.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun paymentOptionFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT po.code, po.label_ko, COUNT(*) AS cnt
            FROM property_payment_option ppo
            JOIN payment_option po ON po.code = ppo.payment_option_code
            JOIN property p ON p.id = ppo.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY po.code, po.label_ko
            ORDER BY cnt DESC, po.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun themeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT t.code, t.label_ko, COUNT(*) AS cnt
            FROM property_theme pt
            JOIN theme t ON t.code = pt.theme_code
            JOIN property p ON p.id = pt.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY t.code, t.label_ko
            ORDER BY cnt DESC, t.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun amenityFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT a.code, a.label_ko, a.group_code, COUNT(*) AS cnt
            FROM property_amenity pa
            JOIN amenity a ON a.code = pa.amenity_code
            JOIN property p ON p.id = pa.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY a.code, a.label_ko, a.group_code
            ORDER BY cnt DESC, a.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                    group = rs.getString("group_code"),
                )
            },
            *params,
        )
    }
}

data class SearchFacetData(
    val popular_filters: List<PopularFilterFacet>,
    val districts: List<FacetDistrict>,
    val nearby_attractions: List<FacetAttraction>,
    val brands: List<FacetCountItem>,
    val amenity_groups: List<AmenityGroupFacet>,
    val stars: List<FacetCountItem>,
    val property_types: List<FacetCountItem>,
    val payment_options: List<FacetCountItem>,
    val themes: List<FacetCountItem>,
    val amenities: List<FacetCountItem>,
)

data class PopularFilterFacet(
    val key: String,
    val value: String,
    val label: String,
    val count: Int,
)

data class FacetDistrict(
    val id: Long,
    val name: String,
    val blurb: String?,
    val count: Int,
)

data class FacetAttraction(
    val poi_id: Long,
    val name: String,
    val count: Int,
)

data class AmenityGroupFacet(
    val group: String,
    val items: List<FacetCountItem>,
)

data class FacetCountItem(
    val key: String,
    val label: String,
    val count: Int,
    val group: String? = null,
)
