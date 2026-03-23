package com.devoceanblue.stayvista.domain.search

import io.micrometer.core.instrument.MeterRegistry
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SearchIndexSyncService(
    private val mapper: SearchIndexSyncMapper,
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
            eventType == "RoomTypeUpserted" && aggregateType == "ROOM_TYPE" -> aggregateId.toLongOrNull()?.let(mapper::findPropertyIdByRoomType)

            else -> null
        }

        if (propertyId == null) {
            return
        }

        val property = mapper.findProperty(propertyId) ?: return

        val roomTypes = mapper.listRoomTypes(propertyId)

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

    fun reindexAll(limit: Int?): SearchReindexData {
        val propertyIds = if (limit != null) {
            mapper.listPropertyIdsLimited(limit)
        } else {
            mapper.listPropertyIds()
        }

        var successCount = 0
        var failedCount = 0
        propertyIds.forEach { propertyId ->
            try {
                syncCatalogEvent(
                    aggregateType = "PROPERTY",
                    aggregateId = propertyId.toString(),
                    eventType = "PropertyUpserted",
                )
                successCount += 1
            } catch (_: Exception) {
                failedCount += 1
            }
        }

        return SearchReindexData(
            scanned = propertyIds.size,
            upserted = successCount,
            failed = failedCount,
        )
    }

}

data class SearchReindexData(
    val scanned: Int,
    val upserted: Int,
    val failed: Int,
)

data class PropertyDocument(
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

data class RoomTypeDocument(
    val roomTypeId: Long,
    val name: String,
    val maxGuests: Int,
    val basePrice: Long,
)

@Mapper
interface SearchIndexSyncMapper {
    @Select("SELECT property_id FROM room_type WHERE id = #{roomTypeId} LIMIT 1")
    fun findPropertyIdByRoomType(@Param("roomTypeId") roomTypeId: Long): Long?

    @Select(
        """
        SELECT id AS propertyId,
               name,
               city,
               country,
               status,
               lat,
               lng,
               COALESCE(rating, 0) AS rating,
               thumbnail_url AS thumbnailUrl
        FROM property
        WHERE id = #{propertyId}
        LIMIT 1
        """,
    )
    fun findProperty(@Param("propertyId") propertyId: Long): PropertyDocument?

    @Select(
        """
        SELECT id AS roomTypeId,
               name,
               capacity_adults AS maxGuests,
               base_price AS basePrice
        FROM room_type
        WHERE property_id = #{propertyId}
        """,
    )
    fun listRoomTypes(@Param("propertyId") propertyId: Long): List<RoomTypeDocument>

    @Select("SELECT id FROM property ORDER BY id")
    fun listPropertyIds(): List<Long>

    @Select("SELECT id FROM property ORDER BY id LIMIT #{limit}")
    fun listPropertyIdsLimited(@Param("limit") limit: Int): List<Long>
}
