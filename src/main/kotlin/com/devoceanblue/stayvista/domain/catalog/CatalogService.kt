package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.time.DateRange
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import kotlin.math.round
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
        val detail = jdbcTemplate.query(
            """
            SELECT p.id,
                   p.name,
                   p.city,
                   p.country,
                   p.address1,
                   p.lat,
                   p.lng,
                   p.status,
                   p.rating,
                   p.thumbnail_url,
                   p.district_name,
                   p.property_type_code,
                   pt.label_ko AS property_type_label,
                   p.star_rating,
                   p.location_rating,
                   p.review_count,
                   p.beach_distance_m,
                   p.is_beachfront,
                   p.kid_free_stay,
                   p.popularity_score
            FROM property p
            LEFT JOIN property_type pt ON pt.code = p.property_type_code
            WHERE p.id = ?
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
                    district_name = rs.getString("district_name"),
                    property_type_code = rs.getString("property_type_code"),
                    property_type_label = rs.getString("property_type_label"),
                    star_rating = rs.getInt("star_rating"),
                    location_rating = rs.getBigDecimal("location_rating")?.toDouble() ?: 0.0,
                    review_count = rs.getInt("review_count"),
                    beach_distance_m = rs.getInt("beach_distance_m").let { if (rs.wasNull()) null else it },
                    is_beachfront = rs.getBoolean("is_beachfront"),
                    kid_free_stay = rs.getBoolean("kid_free_stay"),
                    popularity_score = rs.getInt("popularity_score"),
                    brand_names = emptyList(),
                    amenity_groups = emptyList(),
                    payment_options = emptyList(),
                    themes = emptyList(),
                )
            },
            propertyId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Property not found")

        val amenityRows = jdbcTemplate.query(
            """
            SELECT a.group_code, a.code, a.label_ko
            FROM property_amenity pa
            JOIN amenity a ON a.code = pa.amenity_code
            WHERE pa.property_id = ?
            ORDER BY a.group_code, a.label_ko
            """.trimIndent(),
            { rs, _ ->
                AmenityRow(
                    groupCode = rs.getString("group_code"),
                    code = rs.getString("code"),
                    label = rs.getString("label_ko"),
                )
            },
            propertyId,
        )

        val amenityGroups = amenityRows.groupBy { it.groupCode }
            .toSortedMap()
            .map { (group, rows) ->
                PropertyAmenityGroup(
                    group = group,
                    items = rows.map { row -> PropertyCodeLabel(code = row.code, label = row.label) },
                )
            }

        val paymentOptions = jdbcTemplate.query(
            """
            SELECT po.code, po.label_ko
            FROM property_payment_option ppo
            JOIN payment_option po ON po.code = ppo.payment_option_code
            WHERE ppo.property_id = ?
            ORDER BY po.label_ko
            """.trimIndent(),
            { rs, _ ->
                PropertyCodeLabel(
                    code = rs.getString("code"),
                    label = rs.getString("label_ko"),
                )
            },
            propertyId,
        )

        val themes = jdbcTemplate.query(
            """
            SELECT t.code, t.label_ko
            FROM property_theme pt
            JOIN theme t ON t.code = pt.theme_code
            WHERE pt.property_id = ?
            ORDER BY t.label_ko
            """.trimIndent(),
            { rs, _ ->
                PropertyCodeLabel(
                    code = rs.getString("code"),
                    label = rs.getString("label_ko"),
                )
            },
            propertyId,
        )

        val brands = jdbcTemplate.query(
            """
            SELECT b.name
            FROM property_brand pb
            JOIN brand b ON b.id = pb.brand_id
            WHERE pb.property_id = ?
            ORDER BY b.name
            """.trimIndent(),
            { rs, _ -> rs.getString("name") },
            propertyId,
        )

        return detail.copy(
            brand_names = brands,
            amenity_groups = amenityGroups,
            payment_options = paymentOptions,
            themes = themes,
        )
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

    fun listRoomTypes(
        propertyId: Long,
        checkIn: LocalDate? = null,
        checkOut: LocalDate? = null,
        rooms: Int = 1,
    ): RoomTypeListData {
        val availabilityWindow = resolveAvailabilityWindow(checkIn = checkIn, checkOut = checkOut)
        val rows = if (availabilityWindow == null) {
            jdbcTemplate.query(
                """
                SELECT id, name, capacity_adults, status, base_price, bed_type, view_type, bedrooms
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
                        bed_type = rs.getString("bed_type"),
                        view_type = rs.getString("view_type"),
                        bedrooms = rs.getInt("bedrooms").takeIf { !rs.wasNull() },
                    )
                },
                propertyId,
            )
        } else {
            jdbcTemplate.query(
                """
                SELECT rt.id,
                       rt.name,
                       rt.capacity_adults,
                       rt.status,
                       rt.base_price,
                       rt.bed_type,
                       rt.view_type,
                       rt.bedrooms,
                       inv.available_rooms,
                       inv.covered_nights,
                       CASE
                         WHEN inv.covered_nights = ? AND inv.available_rooms >= ? THEN 1
                         ELSE 0
                       END AS is_available
                FROM room_type rt
                LEFT JOIN (
                  SELECT room_type_id,
                         MIN(total - hold - sold) AS available_rooms,
                         COUNT(*) AS covered_nights
                  FROM inventory_night
                  WHERE stay_date >= ?
                    AND stay_date < ?
                  GROUP BY room_type_id
                ) inv ON inv.room_type_id = rt.id
                WHERE rt.property_id = ?
                  AND rt.status = 'ACTIVE'
                ORDER BY rt.id
                """.trimIndent(),
                { rs, _ ->
                    val availableRooms = rs.getInt("available_rooms").takeIf { !rs.wasNull() }
                    val isAvailable = rs.getInt("is_available") == 1
                    RoomTypeSummary(
                        room_type_id = rs.getLong("id"),
                        name = rs.getString("name"),
                        max_guests = rs.getInt("capacity_adults"),
                        status = rs.getString("status"),
                        base_price = Money(
                            currency = "KRW",
                            amount = rs.getLong("base_price"),
                        ),
                        bed_type = rs.getString("bed_type"),
                        view_type = rs.getString("view_type"),
                        bedrooms = rs.getInt("bedrooms").takeIf { !rs.wasNull() },
                        available_rooms = availableRooms,
                        is_available = isAvailable,
                    )
                },
                availabilityWindow.nights,
                rooms.coerceAtLeast(1),
                Date.valueOf(availabilityWindow.checkIn),
                Date.valueOf(availabilityWindow.checkOut),
                propertyId,
            )
        }
        return RoomTypeListData(items = rows)
    }

    private fun resolveAvailabilityWindow(checkIn: LocalDate?, checkOut: LocalDate?): AvailabilityWindow? {
        if (checkIn == null && checkOut == null) {
            return null
        }
        if (checkIn == null || checkOut == null) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "check_in and check_out are required together")
        }
        val nights = DateRange.nights(checkIn, checkOut)
        if (nights.isEmpty()) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "check_out must be after check_in")
        }
        if (nights.size > 30) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "max stay nights exceeded")
        }
        return AvailabilityWindow(
            checkIn = checkIn,
            checkOut = checkOut,
            nights = nights.size,
        )
    }

    fun listPropertyReviews(
        propertyId: Long,
        tag: String?,
        page: Int,
        size: Int,
    ): PropertyReviewData {
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedSize = size.coerceIn(1, 50)
        val normalizedTag = tag?.trim()?.takeIf { it.isNotEmpty() }

        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM property WHERE id = ?",
            Long::class.java,
            propertyId,
        ) ?: 0L
        if (exists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }

        val summary = jdbcTemplate.query(
            """
            SELECT COUNT(*) AS total_count,
                   AVG(pr.score_overall) AS avg_score,
                   AVG(pr.score_service) AS service_score,
                   AVG(pr.score_cleanliness) AS cleanliness_score,
                   AVG(pr.score_facility) AS facility_score,
                   AVG(pr.score_value) AS value_score,
                   AVG(pr.score_location) AS location_score
            FROM property_review pr
            WHERE pr.property_id = ?
              AND pr.status = 'PUBLISHED'
            """.trimIndent(),
            { rs, _ ->
                PropertyReviewSummary(
                    total = rs.getLong("total_count"),
                    avg_score = roundToOne(rs.getDouble("avg_score")),
                    service = roundToOne(rs.getDouble("service_score")),
                    cleanliness = roundToOne(rs.getDouble("cleanliness_score")),
                    facility = roundToOne(rs.getDouble("facility_score")),
                    value_for_money = roundToOne(rs.getDouble("value_score")),
                    location = roundToOne(rs.getDouble("location_score")),
                )
            },
            propertyId,
        ).firstOrNull() ?: PropertyReviewSummary(
            total = 0L,
            avg_score = 0.0,
            service = 0.0,
            cleanliness = 0.0,
            facility = 0.0,
            value_for_money = 0.0,
            location = 0.0,
        )

        val tags = jdbcTemplate.query(
            """
            SELECT prt.tag, COUNT(*) AS cnt
            FROM property_review pr
            JOIN property_review_tag prt ON prt.review_id = pr.id
            WHERE pr.property_id = ?
              AND pr.status = 'PUBLISHED'
            GROUP BY prt.tag
            ORDER BY cnt DESC, prt.tag ASC
            """.trimIndent(),
            { rs, _ ->
                PropertyReviewTagCount(
                    tag = rs.getString("tag"),
                    count = rs.getLong("cnt"),
                )
            },
            propertyId,
        )

        val where = mutableListOf(
            "pr.property_id = ?",
            "pr.status = 'PUBLISHED'",
        )
        val whereParams = mutableListOf<Any?>(propertyId)
        if (normalizedTag != null) {
            where += "EXISTS (SELECT 1 FROM property_review_tag prt WHERE prt.review_id = pr.id AND prt.tag = ?)"
            whereParams += normalizedTag
        }
        val whereClause = where.joinToString(" AND ")

        val totalFiltered = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM property_review pr WHERE $whereClause",
            Long::class.java,
            *whereParams.toTypedArray(),
        ) ?: 0L

        val reviewRows = jdbcTemplate.query(
            """
            SELECT pr.id,
                   pr.reviewer_name,
                   pr.traveler_type,
                   pr.stay_date,
                   pr.score_overall,
                   pr.title,
                   pr.body
            FROM property_review pr
            WHERE $whereClause
            ORDER BY pr.stay_date DESC, pr.id DESC
            LIMIT ?
            OFFSET ?
            """.trimIndent(),
            { rs, _ ->
                PropertyReviewRow(
                    reviewId = rs.getLong("id"),
                    reviewer = rs.getString("reviewer_name"),
                    travelerType = rs.getString("traveler_type"),
                    stayMonth = formatStayMonth(rs.getDate("stay_date").toLocalDate()),
                    score = roundToOne(rs.getDouble("score_overall")),
                    title = rs.getString("title"),
                    body = rs.getString("body"),
                )
            },
            *(whereParams + listOf(normalizedSize, (normalizedPage - 1) * normalizedSize)).toTypedArray(),
        )

        val reviewTagsById = if (reviewRows.isEmpty()) {
            emptyMap()
        } else {
            val placeholders = reviewRows.joinToString(",") { "?" }
            jdbcTemplate.query(
                """
                SELECT review_id, tag
                FROM property_review_tag
                WHERE review_id IN ($placeholders)
                ORDER BY review_id, tag
                """.trimIndent(),
                { rs, _ ->
                    PropertyReviewTagRow(
                        reviewId = rs.getLong("review_id"),
                        tag = rs.getString("tag"),
                    )
                },
                *reviewRows.map { it.reviewId }.toTypedArray(),
            ).groupBy { it.reviewId }
                .mapValues { (_, rows) -> rows.map { it.tag } }
        }

        val items = reviewRows.map { row ->
            PropertyReviewItem(
                review_id = row.reviewId,
                reviewer = row.reviewer,
                score = row.score,
                title = row.title,
                body = row.body,
                stay_month = row.stayMonth,
                traveler_type = row.travelerType,
                tags = reviewTagsById[row.reviewId] ?: emptyList(),
            )
        }

        return PropertyReviewData(
            summary = summary,
            tags = tags,
            items = items,
            meta = PropertyReviewMeta(
                page = normalizedPage,
                size = normalizedSize,
                has_more = totalFiltered > (normalizedPage.toLong() * normalizedSize),
                total = totalFiltered,
            ),
        )
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
    val district_name: String?,
    val property_type_code: String?,
    val property_type_label: String?,
    val star_rating: Int,
    val location_rating: Double,
    val review_count: Int,
    val beach_distance_m: Int?,
    val is_beachfront: Boolean,
    val kid_free_stay: Boolean,
    val popularity_score: Int,
    val brand_names: List<String>,
    val amenity_groups: List<PropertyAmenityGroup>,
    val payment_options: List<PropertyCodeLabel>,
    val themes: List<PropertyCodeLabel>,
)

data class PropertyCodeLabel(
    val code: String,
    val label: String,
)

data class PropertyAmenityGroup(
    val group: String,
    val items: List<PropertyCodeLabel>,
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
    val bed_type: String?,
    val view_type: String?,
    val bedrooms: Int?,
    val available_rooms: Int? = null,
    val is_available: Boolean? = null,
)

data class RoomTypeListData(
    val items: List<RoomTypeSummary>,
)

data class PropertyReviewData(
    val summary: PropertyReviewSummary,
    val tags: List<PropertyReviewTagCount>,
    val items: List<PropertyReviewItem>,
    val meta: PropertyReviewMeta,
)

data class PropertyReviewSummary(
    val total: Long,
    val avg_score: Double,
    val service: Double,
    val cleanliness: Double,
    val facility: Double,
    val value_for_money: Double,
    val location: Double,
)

data class PropertyReviewTagCount(
    val tag: String,
    val count: Long,
)

data class PropertyReviewItem(
    val review_id: Long,
    val reviewer: String,
    val score: Double,
    val title: String,
    val body: String,
    val stay_month: String,
    val traveler_type: String,
    val tags: List<String>,
)

data class PropertyReviewMeta(
    val page: Int,
    val size: Int,
    val has_more: Boolean,
    val total: Long,
)

data class Money(
    val currency: String,
    val amount: Long,
)

private data class AmenityRow(
    val groupCode: String,
    val code: String,
    val label: String,
)

private data class PropertyReviewRow(
    val reviewId: Long,
    val reviewer: String,
    val travelerType: String,
    val stayMonth: String,
    val score: Double,
    val title: String,
    val body: String,
)

private data class PropertyReviewTagRow(
    val reviewId: Long,
    val tag: String,
)

private data class AvailabilityWindow(
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val nights: Int,
)

private fun roundToOne(value: Double): Double {
    if (!value.isFinite()) return 0.0
    return round(value * 10.0) / 10.0
}

private fun formatStayMonth(date: LocalDate): String = "${date.year}년 ${date.monthValue}월"
