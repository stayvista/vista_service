package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.time.DateRange
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogService(
    private val jdbcTemplate: JdbcTemplate,
    private val domainSupportService: DomainSupportService,
) {
    @Transactional
    fun createProperty(request: CreatePropertyRequest): Long {
        domainSupportService.ensurePartnerExists(request.partner_id)
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO property(partner_id, name, country, city, address1, lat, lng, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, request.partner_id)
            ps.setString(2, request.name)
            ps.setString(3, request.country)
            ps.setString(4, request.city)
            ps.setString(5, request.address1)
            ps.setBigDecimal(6, request.lat?.toBigDecimal())
            ps.setBigDecimal(7, request.lng?.toBigDecimal())
            ps.setString(8, request.status)
            ps
        }, keyHolder)
        val id = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create property")
        domainSupportService.appendOutbox(
            aggregateType = "PROPERTY",
            aggregateId = id.toString(),
            eventType = "PropertyUpserted",
            payload = request,
        )
        return id
    }

    @Transactional
    fun patchProperty(propertyId: Long, request: PatchPropertyRequest) {
        val affected = jdbcTemplate.update(
            """
            UPDATE property
            SET name = COALESCE(?, name),
                country = COALESCE(?, country),
                city = COALESCE(?, city),
                address1 = COALESCE(?, address1),
                lat = COALESCE(?, lat),
                lng = COALESCE(?, lng),
                status = COALESCE(?, status),
                updated_at = NOW(3)
            WHERE id = ?
            """.trimIndent(),
            request.name,
            request.country,
            request.city,
            request.address1,
            request.lat?.toBigDecimal(),
            request.lng?.toBigDecimal(),
            request.status,
            propertyId,
        )
        if (affected == 0) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }
        domainSupportService.appendOutbox(
            aggregateType = "PROPERTY",
            aggregateId = propertyId.toString(),
            eventType = "PropertyUpserted",
            payload = request,
        )
    }

    @Transactional
    fun createRoomType(propertyId: Long, request: CreateRoomTypeRequest): Long {
        val propertyExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM property WHERE id = ?",
            Long::class.java,
            propertyId,
        ) ?: 0L
        if (propertyExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO room_type(property_id, name, capacity_adults, capacity_children, status, base_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, propertyId)
            ps.setString(2, request.name)
            ps.setInt(3, request.max_guests)
            ps.setInt(4, 0)
            ps.setString(5, request.status)
            ps.setLong(6, request.base_price.amount)
            ps
        }, keyHolder)
        val id = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create room type")
        domainSupportService.appendOutbox(
            aggregateType = "ROOM_TYPE",
            aggregateId = id.toString(),
            eventType = "RoomTypeUpserted",
            payload = request,
        )
        return id
    }

    @Transactional
    fun patchRoomType(roomTypeId: Long, request: PatchRoomTypeRequest) {
        val affected = jdbcTemplate.update(
            """
            UPDATE room_type
            SET name = COALESCE(?, name),
                capacity_adults = COALESCE(?, capacity_adults),
                status = COALESCE(?, status),
                base_price = COALESCE(?, base_price),
                updated_at = NOW(3)
            WHERE id = ?
            """.trimIndent(),
            request.name,
            request.max_guests,
            request.status,
            request.base_price?.amount,
            roomTypeId,
        )
        if (affected == 0) {
            throw DomainException(ErrorCode.NOT_FOUND, "Room type not found")
        }
        domainSupportService.appendOutbox(
            aggregateType = "ROOM_TYPE",
            aggregateId = roomTypeId.toString(),
            eventType = "RoomTypeUpserted",
            payload = request,
        )
    }

    @Transactional
    fun putInventory(roomTypeId: Long, request: PutInventoryRequest): Int {
        val nights = DateRange.nights(request.start_date, request.end_date)
        if (nights.isEmpty()) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "start_date must be before end_date")
        }

        val conflict = jdbcTemplate.query(
            """
            SELECT stay_date, hold, sold
            FROM inventory_night
            WHERE room_type_id = ?
              AND stay_date >= ?
              AND stay_date < ?
              AND ? < (hold + sold)
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getDate("stay_date").toLocalDate() },
            roomTypeId,
            Date.valueOf(request.start_date),
            Date.valueOf(request.end_date),
            request.total,
        ).firstOrNull()
        if (conflict != null) {
            throw DomainException(
                ErrorCode.INVENTORY_TOTAL_BELOW_COMMITTED,
                "Inventory total cannot be lower than hold + sold",
                mapOf("conflict_date" to conflict.toString()),
            )
        }

        nights.forEach { day ->
            jdbcTemplate.update(
                """
                INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
                VALUES (?, ?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = NOW(3)
                """.trimIndent(),
                roomTypeId,
                Date.valueOf(day),
                request.total,
            )
        }
        return nights.size
    }

    fun getProperty(propertyId: Long): PropertyDetail {
        return jdbcTemplate.query(
            """
            SELECT id, name, city, country, address1, lat, lng, status, rating, thumbnail_url
            FROM property
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                PropertyDetail(
                    property_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    city = rs.getString("city"),
                    country = rs.getString("country"),
                    address1 = rs.getString("address1"),
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lng = rs.getBigDecimal("lng")?.toDouble(),
                    status = rs.getString("status"),
                    rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                    thumbnail_url = rs.getString("thumbnail_url"),
                )
            },
            propertyId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
    }

    fun listProperties(limit: Int, cursor: Long?): PropertyListData {
        val fetchLimit = limit.coerceIn(1, 50)
        val rows = jdbcTemplate.query(
            """
            SELECT id, name, city, rating, thumbnail_url
            FROM property
            WHERE status='ACTIVE'
              AND (? IS NULL OR id > ?)
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                PropertySummary(
                    property_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    city = rs.getString("city"),
                    rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                    thumbnail_url = rs.getString("thumbnail_url"),
                )
            },
            cursor,
            cursor,
            fetchLimit + 1,
        )
        val hasNext = rows.size > fetchLimit
        val items = if (hasNext) rows.dropLast(1) else rows
        val nextCursor = if (hasNext) items.last().property_id.toString() else null
        return PropertyListData(items = items, next_cursor = nextCursor)
    }

    fun listRoomTypes(propertyId: Long): RoomTypeListData {
        val rows = jdbcTemplate.query(
            """
            SELECT id, name, capacity_adults, status, base_price
            FROM room_type
            WHERE property_id = ? AND status='ACTIVE'
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                RoomTypeSummary(
                    room_type_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    max_guests = rs.getInt("capacity_adults"),
                    status = rs.getString("status"),
                    base_price = Money(
                        currency = "KRW",
                        amount = rs.getLong("base_price"),
                    ),
                )
            },
            propertyId,
        )
        return RoomTypeListData(items = rows)
    }
}

data class CreatePropertyRequest(
    val partner_id: Long,
    val name: String,
    val country: String?,
    val city: String?,
    val address1: String?,
    val lat: Double?,
    val lng: Double?,
    val status: String = "ACTIVE",
)

data class PatchPropertyRequest(
    val name: String? = null,
    val country: String? = null,
    val city: String? = null,
    val address1: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val status: String? = null,
)

data class CreateRoomTypeRequest(
    val name: String,
    val max_guests: Int,
    val base_price: Money,
    val status: String = "ACTIVE",
)

data class PatchRoomTypeRequest(
    val name: String? = null,
    val max_guests: Int? = null,
    val base_price: Money? = null,
    val status: String? = null,
)

data class PutInventoryRequest(
    val start_date: LocalDate,
    val end_date: LocalDate,
    val total: Int,
)

data class PropertyDetail(
    val property_id: Long,
    val name: String,
    val city: String?,
    val country: String?,
    val address1: String?,
    val lat: Double?,
    val lng: Double?,
    val status: String,
    val rating: Double,
    val thumbnail_url: String?,
)

data class PropertySummary(
    val property_id: Long,
    val name: String,
    val city: String?,
    val rating: Double,
    val thumbnail_url: String?,
)

data class PropertyListData(
    val items: List<PropertySummary>,
    val next_cursor: String?,
)

data class RoomTypeSummary(
    val room_type_id: Long,
    val name: String,
    val max_guests: Int,
    val status: String,
    val base_price: Money,
)

data class RoomTypeListData(
    val items: List<RoomTypeSummary>,
)

data class Money(
    val currency: String,
    val amount: Long,
)
