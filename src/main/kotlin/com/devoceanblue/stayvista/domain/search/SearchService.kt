package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SearchService(
    private val jdbcTemplate: JdbcTemplate,
    private val cache: SimpleTtlCache,
    private val meterRegistry: MeterRegistry,
    private val openSearchClient: OpenSearchClient,
    @Value("\${stayvista.search.use-opensearch:true}") private val useOpenSearch: Boolean,
) {
    fun search(request: SearchRequest): SearchData {
        val cacheKey = "search:v1:${sha256(normalized(request))}"
        cache.get<SearchData>(cacheKey)?.let {
            meterRegistry.counter("search_requests_total", "cache", "hit").increment()
            return it
        }

        meterRegistry.counter("search_requests_total", "cache", "miss").increment()

        val data = if (useOpenSearch) {
            try {
                openSearchClient.search(request)
            } catch (_: Exception) {
                meterRegistry.counter("search_opensearch_errors_total").increment()
                searchFromDb(request)
            }
        } else {
            searchFromDb(request)
        }

        cache.put(cacheKey, ttlMillis = 10_000, value = data)
        return data
    }

    private fun searchFromDb(request: SearchRequest): SearchData {
        val limit = request.limit.coerceIn(1, 50)
        val cursor = request.cursor?.toLongOrNull()
        val params = mutableListOf<Any?>()
        val where = mutableListOf("p.status='ACTIVE'")

        if (!request.city.isNullOrBlank()) {
            where += "p.city = ?"
            params += request.city
        }
        if (!request.q.isNullOrBlank()) {
            where += "p.name LIKE ?"
            params += "%${request.q.trim()}%"
        }
        if (request.min_price != null) {
            where += "rt.base_price >= ?"
            params += request.min_price
        }
        if (request.max_price != null) {
            where += "rt.base_price <= ?"
            params += request.max_price
        }
        if (request.min_rating != null) {
            where += "COALESCE(p.rating, 0) >= ?"
            params += request.min_rating
        }
        if (cursor != null) {
            where += "p.id > ?"
            params += cursor
        }

        val order = when (request.sort) {
            "price_asc" -> "price_min ASC, p.id ASC"
            "price_desc" -> "price_min DESC, p.id DESC"
            "rating_desc" -> "rating DESC, p.id ASC"
            else -> "p.id ASC"
        }

        val sql = """
            SELECT p.id, p.name, p.city, COALESCE(MIN(rt.base_price), 0) AS price_min,
                   COALESCE(p.rating, 0) AS rating, p.thumbnail_url
            FROM property p
            LEFT JOIN room_type rt ON rt.property_id = p.id AND rt.status='ACTIVE'
            WHERE ${where.joinToString(" AND ")}
            GROUP BY p.id, p.name, p.city, p.rating, p.thumbnail_url
            ORDER BY $order
            LIMIT ?
        """.trimIndent()
        params += (limit + 1)

        val rows = jdbcTemplate.query(sql, { rs, _ ->
            SearchItem(
                property_id = rs.getLong("id"),
                name = rs.getString("name"),
                city = rs.getString("city"),
                price_min = rs.getLong("price_min"),
                rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                thumbnail_url = rs.getString("thumbnail_url"),
            )
        }, *params.toTypedArray())

        val hasNext = rows.size > limit
        val items = if (hasNext) rows.dropLast(1) else rows
        return SearchData(
            items = items,
            next_cursor = if (hasNext) items.last().property_id.toString() else null,
        )
    }

    private fun normalized(request: SearchRequest): String {
        return listOf(
            "q=${request.q ?: ""}",
            "city=${request.city ?: ""}",
            "check_in=${request.check_in ?: ""}",
            "check_out=${request.check_out ?: ""}",
            "adults=${request.adults ?: ""}",
            "children=${request.children ?: ""}",
            "min_price=${request.min_price ?: ""}",
            "max_price=${request.max_price ?: ""}",
            "min_rating=${request.min_rating ?: ""}",
            "sort=${request.sort ?: ""}",
            "cursor=${request.cursor ?: ""}",
            "limit=${request.limit}",
        ).joinToString("&")
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class SearchRequest(
    val q: String?,
    val city: String?,
    val check_in: String?,
    val check_out: String?,
    val adults: Int?,
    val children: Int?,
    val min_price: Long?,
    val max_price: Long?,
    val min_rating: Double?,
    val sort: String?,
    val cursor: String?,
    val limit: Int,
)

data class SearchItem(
    val property_id: Long,
    val name: String,
    val city: String?,
    val price_min: Long,
    val rating: Double,
    val thumbnail_url: String?,
)

data class SearchData(
    val items: List<SearchItem>,
    val next_cursor: String?,
)
