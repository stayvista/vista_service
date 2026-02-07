package com.devoceanblue.stayvista.domain.search

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SearchIndexSyncService(
    private val jdbcTemplate: JdbcTemplate,
    private val openSearchClient: OpenSearchClient,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    fun ensureIndex() {
        try {
            openSearchClient.ensureIndexAndAlias()
        } catch (_: Exception) {
            meterRegistry.counter("search_index_ensure_fail_total").increment()
        }
    }

    fun syncCatalogEvent(aggregateType: String, aggregateId: String, eventType: String) {
        val propertyId = when {
            eventType == "PropertyUpserted" && aggregateType == "PROPERTY" -> aggregateId.toLongOrNull()
            eventType == "RoomTypeUpserted" && aggregateType == "ROOM_TYPE" -> {
                val roomTypeId = aggregateId.toLongOrNull()
                if (roomTypeId == null) {
                    null
                } else {
                    jdbcTemplate.query(
                        "SELECT property_id FROM room_type WHERE id = ?",
                        { rs, _ -> rs.getLong("property_id") },
                        roomTypeId,
                    ).firstOrNull()
                }
            }

            else -> null
        }

        if (propertyId == null) {
            return
        }

        val property = jdbcTemplate.query(
            """
            SELECT id, name, city, country, status, lat, lng, rating, thumbnail_url
            FROM property
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                PropertyDocument(
                    propertyId = rs.getLong("id"),
                    name = rs.getString("name"),
                    city = rs.getString("city"),
                    country = rs.getString("country"),
                    status = rs.getString("status"),
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lng = rs.getBigDecimal("lng")?.toDouble(),
                    rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                    thumbnailUrl = rs.getString("thumbnail_url"),
                )
            },
            propertyId,
        ).firstOrNull() ?: return

        val roomTypes = jdbcTemplate.query(
            """
            SELECT id, name, capacity_adults, base_price
            FROM room_type
            WHERE property_id = ?
            """.trimIndent(),
            { rs, _ ->
                RoomTypeDocument(
                    roomTypeId = rs.getLong("id"),
                    name = rs.getString("name"),
                    maxGuests = rs.getInt("capacity_adults"),
                    basePrice = rs.getLong("base_price"),
                )
            },
            propertyId,
        )

        val document = mutableMapOf<String, Any?>(
            "property_id" to property.propertyId,
            "name" to property.name,
            "city" to property.city,
            "country" to property.country,
            "status" to property.status,
            "price_min" to (roomTypes.minOfOrNull { it.basePrice } ?: 0L),
            "rating" to property.rating,
            "thumbnail_url" to property.thumbnailUrl,
            "room_types" to roomTypes.map {
                mapOf(
                    "room_type_id" to it.roomTypeId,
                    "name" to it.name,
                    "max_guests" to it.maxGuests,
                    "base_price" to it.basePrice,
                )
            },
        )

        if (property.lat != null && property.lng != null) {
            document["location"] = mapOf("lat" to property.lat, "lon" to property.lng)
        }

        openSearchClient.upsertProperty(property.propertyId, document)
        meterRegistry.counter("search_index_upsert_total", "result", "success").increment()
    }

    private data class PropertyDocument(
        val propertyId: Long,
        val name: String,
        val city: String?,
        val country: String?,
        val status: String,
        val lat: Double?,
        val lng: Double?,
        val rating: Double,
        val thumbnailUrl: String?,
    )

    private data class RoomTypeDocument(
        val roomTypeId: Long,
        val name: String,
        val maxGuests: Int,
        val basePrice: Long,
    )
}
