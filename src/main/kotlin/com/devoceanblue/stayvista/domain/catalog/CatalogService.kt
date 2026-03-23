package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.time.DateRange
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.sql.Date
import java.time.LocalDate
import kotlin.math.round
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogService(
    private val mapper: CatalogMapper,
    private val domainSupportService: DomainSupportService,
) {
    @Transactional
    fun createProperty(request: CreatePropertyRequest): Long {
        domainSupportService.ensurePartnerExists(request.partner_id)
        val command = PropertyInsertCommand(
            partnerId = request.partner_id,
            name = request.name,
            country = request.country,
            city = request.city,
            address1 = request.address1,
            lat = request.lat?.toBigDecimal(),
            lng = request.lng?.toBigDecimal(),
            status = request.status,
        )
        mapper.insertProperty(command)
        val id = command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create property")
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
        val affected = mapper.patchProperty(
            propertyId = propertyId,
            name = request.name,
            country = request.country,
            city = request.city,
            address1 = request.address1,
            lat = request.lat?.toBigDecimal(),
            lng = request.lng?.toBigDecimal(),
            status = request.status,
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
        val propertyExists = mapper.countProperty(propertyId)
        if (propertyExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }
        val command = RoomTypeInsertCommand(
            propertyId = propertyId,
            name = request.name,
            capacityAdults = request.max_guests,
            status = request.status,
            basePrice = request.base_price.amount,
        )
        mapper.insertRoomType(command)
        val id = command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create room type")
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
        val affected = mapper.patchRoomType(
            roomTypeId = roomTypeId,
            name = request.name,
            maxGuests = request.max_guests,
            status = request.status,
            basePrice = request.base_price?.amount,
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

        val conflict = mapper.findInventoryConflictDate(
            roomTypeId = roomTypeId,
            startDate = Date.valueOf(request.start_date),
            endDate = Date.valueOf(request.end_date),
            total = request.total,
        )
        if (conflict != null) {
            throw DomainException(
                ErrorCode.INVENTORY_TOTAL_BELOW_COMMITTED,
                "Inventory total cannot be lower than hold + sold",
                mapOf("conflict_date" to conflict.toString()),
            )
        }

        nights.forEach { day ->
            mapper.upsertInventoryNight(
                roomTypeId = roomTypeId,
                stayDate = Date.valueOf(day),
                total = request.total,
            )
        }
        return nights.size
    }

    fun getProperty(propertyId: Long): PropertyDetail {
        val detail = mapper.findPropertyDetail(propertyId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "Property not found")

        val amenityRows = mapper.listAmenityRows(propertyId)

        val amenityGroups = amenityRows.groupBy { it.groupCode }
            .toSortedMap()
            .map { (group, rows) ->
                PropertyAmenityGroup(
                    group = group,
                    items = rows.map { row -> PropertyCodeLabel(code = row.code, label = row.label) },
                )
            }

        val paymentOptions = mapper.listPaymentOptions(propertyId)
        val themes = mapper.listThemes(propertyId)
        val brands = mapper.listBrands(propertyId)

        return PropertyDetail(
            property_id = detail.propertyId,
            name = detail.name,
            city = detail.city,
            country = detail.country,
            address1 = detail.address1,
            lat = detail.lat,
            lng = detail.lng,
            status = detail.status,
            rating = detail.rating,
            thumbnail_url = detail.thumbnailUrl,
            district_name = detail.districtName,
            property_type_code = detail.propertyTypeCode,
            property_type_label = detail.propertyTypeLabel,
            star_rating = detail.starRating,
            location_rating = detail.locationRating,
            review_count = detail.reviewCount,
            beach_distance_m = detail.beachDistanceM,
            is_beachfront = detail.isBeachfront,
            kid_free_stay = detail.kidFreeStay,
            popularity_score = detail.popularityScore,
            brand_names = brands,
            amenity_groups = amenityGroups,
            payment_options = paymentOptions,
            themes = themes,
        )
    }

    fun listProperties(limit: Int, cursor: Long?): PropertyListData {
        val fetchLimit = limit.coerceIn(1, 50)
        val rows = mapper.listProperties(cursor = cursor, limit = fetchLimit + 1)
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
        userId: Long? = null,
    ): RoomTypeListData {
        val availabilityWindow = resolveAvailabilityWindow(checkIn = checkIn, checkOut = checkOut)
        val rows = if (availabilityWindow == null) {
            mapper.listRoomTypesBasic(propertyId)
                .map { it.toSummary() }
        } else {
            mapper.listRoomTypesWithAvailability(
                nights = availabilityWindow.nights,
                rooms = rooms.coerceAtLeast(1),
                checkIn = Date.valueOf(availabilityWindow.checkIn),
                checkOut = Date.valueOf(availabilityWindow.checkOut),
                userId = userId,
                propertyId = propertyId,
            ).map { it.toSummary() }
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

    private fun toBookingId(id: Long): String = "bkg_$id"

    fun listPropertyReviews(
        propertyId: Long,
        tag: String?,
        page: Int,
        size: Int,
    ): PropertyReviewData {
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedSize = size.coerceIn(1, 50)
        val normalizedTag = tag?.trim()?.takeIf { it.isNotEmpty() }

        val exists = mapper.countProperty(propertyId)
        if (exists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }

        val summary = mapper.reviewSummary(propertyId)?.toSummary() ?: PropertyReviewSummary(
            total = 0L,
            avg_score = 0.0,
            service = 0.0,
            cleanliness = 0.0,
            facility = 0.0,
            value_for_money = 0.0,
            location = 0.0,
        )

        val tags = mapper.reviewTags(propertyId)

        val totalFiltered = mapper.countFilteredReviews(
            propertyId = propertyId,
            tag = normalizedTag,
        )

        val reviewRows = mapper.listReviews(
            propertyId = propertyId,
            tag = normalizedTag,
            limit = normalizedSize,
            offset = (normalizedPage - 1) * normalizedSize,
        ).map { it.toReviewRow() }

        val reviewTagsById = if (reviewRows.isEmpty()) {
            emptyMap()
        } else {
            mapper.listReviewTags(reviewRows.map { it.reviewId }).groupBy { it.reviewId }
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
    val active_hold_booking_id: String? = null,
    val active_hold_expires_at: String? = null,
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

data class AmenityRow(
    val groupCode: String,
    val code: String,
    val label: String,
)

data class PropertyReviewRow(
    val reviewId: Long,
    val reviewer: String,
    val travelerType: String,
    val stayMonth: String,
    val score: Double,
    val title: String,
    val body: String,
)

data class PropertyReviewTagRow(
    val reviewId: Long,
    val tag: String,
)

data class PropertyDetailRow(
    val propertyId: Long,
    val name: String,
    val city: String?,
    val country: String?,
    val address1: String?,
    val lat: Double?,
    val lng: Double?,
    val status: String,
    val rating: Double,
    val thumbnailUrl: String?,
    val districtName: String?,
    val propertyTypeCode: String?,
    val propertyTypeLabel: String?,
    val starRating: Int,
    val locationRating: Double,
    val reviewCount: Int,
    val beachDistanceM: Int?,
    val isBeachfront: Boolean,
    val kidFreeStay: Boolean,
    val popularityScore: Int,
)

data class RoomTypeRow(
    val id: Long,
    val name: String,
    val capacityAdults: Int,
    val status: String,
    val basePrice: Long,
    val bedType: String?,
    val viewType: String?,
    val bedrooms: Int?,
    val availableRooms: Int? = null,
    val isAvailable: Int? = null,
    val activeHoldBookingId: Long? = null,
    val activeHoldExpiresAt: java.sql.Timestamp? = null,
) {
    fun toSummary(): RoomTypeSummary = RoomTypeSummary(
        room_type_id = id,
        name = name,
        max_guests = capacityAdults,
        status = status,
        base_price = Money(currency = "KRW", amount = basePrice),
        bed_type = bedType,
        view_type = viewType,
        bedrooms = bedrooms,
        available_rooms = availableRooms,
        is_available = isAvailable?.let { it == 1 },
        active_hold_booking_id = activeHoldBookingId?.let { "bkg_$it" },
        active_hold_expires_at = activeHoldExpiresAt?.toInstant()?.toString(),
    )
}

data class PropertyReviewSummaryRow(
    val totalCount: Long,
    val avgScore: Double?,
    val serviceScore: Double?,
    val cleanlinessScore: Double?,
    val facilityScore: Double?,
    val valueScore: Double?,
    val locationScore: Double?,
) {
    fun toSummary(): PropertyReviewSummary = PropertyReviewSummary(
        total = totalCount,
        avg_score = roundToOne(avgScore ?: 0.0),
        service = roundToOne(serviceScore ?: 0.0),
        cleanliness = roundToOne(cleanlinessScore ?: 0.0),
        facility = roundToOne(facilityScore ?: 0.0),
        value_for_money = roundToOne(valueScore ?: 0.0),
        location = roundToOne(locationScore ?: 0.0),
    )
}

data class PropertyReviewQueryRow(
    val reviewId: Long,
    val reviewer: String,
    val travelerType: String,
    val stayDate: LocalDate,
    val scoreOverall: Double,
    val title: String,
    val body: String,
) {
    fun toReviewRow(): PropertyReviewRow = PropertyReviewRow(
        reviewId = reviewId,
        reviewer = reviewer,
        travelerType = travelerType,
        stayMonth = formatStayMonth(stayDate),
        score = roundToOne(scoreOverall),
        title = title,
        body = body,
    )
}

data class PropertyInsertCommand(
    val partnerId: Long,
    val name: String,
    val country: String?,
    val city: String?,
    val address1: String?,
    val lat: java.math.BigDecimal?,
    val lng: java.math.BigDecimal?,
    val status: String,
    var id: Long? = null,
)

data class RoomTypeInsertCommand(
    val propertyId: Long,
    val name: String,
    val capacityAdults: Int,
    val status: String,
    val basePrice: Long,
    var id: Long? = null,
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

@Mapper
interface CatalogMapper {
    @Insert(
        """
        INSERT INTO property(partner_id, name, country, city, address1, lat, lng, status)
        VALUES (#{partnerId}, #{name}, #{country}, #{city}, #{address1}, #{lat}, #{lng}, #{status})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertProperty(command: PropertyInsertCommand): Int

    @Update(
        """
        UPDATE property
        SET name = COALESCE(#{name}, name),
            country = COALESCE(#{country}, country),
            city = COALESCE(#{city}, city),
            address1 = COALESCE(#{address1}, address1),
            lat = COALESCE(#{lat}, lat),
            lng = COALESCE(#{lng}, lng),
            status = COALESCE(#{status}, status),
            updated_at = NOW(3)
        WHERE id = #{propertyId}
        """,
    )
    fun patchProperty(
        @Param("propertyId") propertyId: Long,
        @Param("name") name: String?,
        @Param("country") country: String?,
        @Param("city") city: String?,
        @Param("address1") address1: String?,
        @Param("lat") lat: java.math.BigDecimal?,
        @Param("lng") lng: java.math.BigDecimal?,
        @Param("status") status: String?,
    ): Int

    @Select("SELECT COUNT(*) FROM property WHERE id = #{propertyId}")
    fun countProperty(@Param("propertyId") propertyId: Long): Long

    @Insert(
        """
        INSERT INTO room_type(property_id, name, capacity_adults, capacity_children, status, base_price)
        VALUES (#{propertyId}, #{name}, #{capacityAdults}, 0, #{status}, #{basePrice})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertRoomType(command: RoomTypeInsertCommand): Int

    @Update(
        """
        UPDATE room_type
        SET name = COALESCE(#{name}, name),
            capacity_adults = COALESCE(#{maxGuests}, capacity_adults),
            status = COALESCE(#{status}, status),
            base_price = COALESCE(#{basePrice}, base_price),
            updated_at = NOW(3)
        WHERE id = #{roomTypeId}
        """,
    )
    fun patchRoomType(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("name") name: String?,
        @Param("maxGuests") maxGuests: Int?,
        @Param("status") status: String?,
        @Param("basePrice") basePrice: Long?,
    ): Int

    @Select(
        """
        SELECT stay_date
        FROM inventory_night
        WHERE room_type_id = #{roomTypeId}
          AND stay_date >= #{startDate}
          AND stay_date < #{endDate}
          AND #{total} < (hold + sold)
        LIMIT 1
        """,
    )
    fun findInventoryConflictDate(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("startDate") startDate: Date,
        @Param("endDate") endDate: Date,
        @Param("total") total: Int,
    ): LocalDate?

    @Insert(
        """
        INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
        VALUES (#{roomTypeId}, #{stayDate}, #{total}, 0, 0)
        ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = NOW(3)
        """,
    )
    fun upsertInventoryNight(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("total") total: Int,
    ): Int

    @Select(
        """
        SELECT p.id AS propertyId,
               p.name,
               p.city,
               p.country,
               p.address1,
               p.lat,
               p.lng,
               p.status,
               COALESCE(p.rating, 0) AS rating,
               p.thumbnail_url AS thumbnailUrl,
               p.district_name AS districtName,
               p.property_type_code AS propertyTypeCode,
               pt.label_ko AS propertyTypeLabel,
               p.star_rating AS starRating,
               COALESCE(p.location_rating, 0) AS locationRating,
               p.review_count AS reviewCount,
               p.beach_distance_m AS beachDistanceM,
               p.is_beachfront AS isBeachfront,
               p.kid_free_stay AS kidFreeStay,
               p.popularity_score AS popularityScore
        FROM property p
        LEFT JOIN property_type pt ON pt.code = p.property_type_code
        WHERE p.id = #{propertyId}
        LIMIT 1
        """,
    )
    fun findPropertyDetail(@Param("propertyId") propertyId: Long): PropertyDetailRow?

    @Select(
        """
        SELECT a.group_code AS groupCode, a.code, a.label_ko AS label
        FROM property_amenity pa
        JOIN amenity a ON a.code = pa.amenity_code
        WHERE pa.property_id = #{propertyId}
        ORDER BY a.group_code, a.label_ko
        """,
    )
    fun listAmenityRows(@Param("propertyId") propertyId: Long): List<AmenityRow>

    @Select(
        """
        SELECT po.code, po.label_ko AS label
        FROM property_payment_option ppo
        JOIN payment_option po ON po.code = ppo.payment_option_code
        WHERE ppo.property_id = #{propertyId}
        ORDER BY po.label_ko
        """,
    )
    fun listPaymentOptions(@Param("propertyId") propertyId: Long): List<PropertyCodeLabel>

    @Select(
        """
        SELECT t.code, t.label_ko AS label
        FROM property_theme pt
        JOIN theme t ON t.code = pt.theme_code
        WHERE pt.property_id = #{propertyId}
        ORDER BY t.label_ko
        """,
    )
    fun listThemes(@Param("propertyId") propertyId: Long): List<PropertyCodeLabel>

    @Select(
        """
        SELECT b.name
        FROM property_brand pb
        JOIN brand b ON b.id = pb.brand_id
        WHERE pb.property_id = #{propertyId}
        ORDER BY b.name
        """,
    )
    fun listBrands(@Param("propertyId") propertyId: Long): List<String>

    @Select(
        """
        SELECT id AS property_id,
               name,
               city,
               COALESCE(rating, 0) AS rating,
               thumbnail_url AS thumbnail_url
        FROM property
        WHERE status='ACTIVE'
          AND (#{cursor} IS NULL OR id > #{cursor})
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun listProperties(
        @Param("cursor") cursor: Long?,
        @Param("limit") limit: Int,
    ): List<PropertySummary>

    @Select(
        """
        SELECT id,
               name,
               capacity_adults AS capacityAdults,
               status,
               base_price AS basePrice,
               bed_type AS bedType,
               view_type AS viewType,
               bedrooms
        FROM room_type
        WHERE property_id = #{propertyId} AND status='ACTIVE'
        ORDER BY id
        """,
    )
    fun listRoomTypesBasic(@Param("propertyId") propertyId: Long): List<RoomTypeRow>

    @Select(
        """
        SELECT rt.id,
               rt.name,
               rt.capacity_adults AS capacityAdults,
               rt.status,
               rt.base_price AS basePrice,
               rt.bed_type AS bedType,
               rt.view_type AS viewType,
               rt.bedrooms,
               inv.available_rooms AS availableRooms,
               CASE
                 WHEN inv.covered_nights = #{nights} AND inv.available_rooms >= #{rooms} THEN 1
                 ELSE 0
               END AS isAvailable,
               hold.booking_id AS activeHoldBookingId,
               hold.expires_at AS activeHoldExpiresAt
        FROM room_type rt
        LEFT JOIN (
          SELECT room_type_id,
                 MIN(total - hold - sold) AS available_rooms,
                 COUNT(*) AS covered_nights
          FROM inventory_night
          WHERE stay_date >= #{checkIn}
            AND stay_date < #{checkOut}
          GROUP BY room_type_id
        ) inv ON inv.room_type_id = rt.id
        LEFT JOIN (
          SELECT room_type_id,
                 MAX(id) AS booking_id,
                 MAX(expires_at) AS expires_at
          FROM booking
          WHERE #{userId} IS NOT NULL
            AND user_id = #{userId}
            AND status = 'HOLD'
            AND check_in = #{checkIn}
            AND check_out = #{checkOut}
            AND rooms = #{rooms}
            AND expires_at > NOW(3)
          GROUP BY room_type_id
        ) hold ON hold.room_type_id = rt.id
        WHERE rt.property_id = #{propertyId}
          AND rt.status = 'ACTIVE'
        ORDER BY rt.id
        """,
    )
    fun listRoomTypesWithAvailability(
        @Param("nights") nights: Int,
        @Param("rooms") rooms: Int,
        @Param("checkIn") checkIn: Date,
        @Param("checkOut") checkOut: Date,
        @Param("userId") userId: Long?,
        @Param("propertyId") propertyId: Long,
    ): List<RoomTypeRow>

    @Select(
        """
        SELECT COUNT(*) AS totalCount,
               AVG(pr.score_overall) AS avgScore,
               AVG(pr.score_service) AS serviceScore,
               AVG(pr.score_cleanliness) AS cleanlinessScore,
               AVG(pr.score_facility) AS facilityScore,
               AVG(pr.score_value) AS valueScore,
               AVG(pr.score_location) AS locationScore
        FROM property_review pr
        WHERE pr.property_id = #{propertyId}
          AND pr.status = 'PUBLISHED'
        """,
    )
    fun reviewSummary(@Param("propertyId") propertyId: Long): PropertyReviewSummaryRow?

    @Select(
        """
        SELECT prt.tag AS tag, COUNT(*) AS count
        FROM property_review pr
        JOIN property_review_tag prt ON prt.review_id = pr.id
        WHERE pr.property_id = #{propertyId}
          AND pr.status = 'PUBLISHED'
        GROUP BY prt.tag
        ORDER BY count DESC, prt.tag ASC
        """,
    )
    fun reviewTags(@Param("propertyId") propertyId: Long): List<PropertyReviewTagCount>

    @Select(
        """
        <script>
        SELECT COUNT(*)
        FROM property_review pr
        WHERE pr.property_id = #{propertyId}
          AND pr.status = 'PUBLISHED'
          <if test="tag != null">
            AND EXISTS (
              SELECT 1 FROM property_review_tag prt
              WHERE prt.review_id = pr.id AND prt.tag = #{tag}
            )
          </if>
        </script>
        """,
    )
    fun countFilteredReviews(
        @Param("propertyId") propertyId: Long,
        @Param("tag") tag: String?,
    ): Long

    @Select(
        """
        <script>
        SELECT pr.id AS reviewId,
               pr.reviewer_name AS reviewer,
               pr.traveler_type AS travelerType,
               pr.stay_date AS stayDate,
               pr.score_overall AS scoreOverall,
               pr.title,
               pr.body
        FROM property_review pr
        WHERE pr.property_id = #{propertyId}
          AND pr.status = 'PUBLISHED'
          <if test="tag != null">
            AND EXISTS (
              SELECT 1 FROM property_review_tag prt
              WHERE prt.review_id = pr.id AND prt.tag = #{tag}
            )
          </if>
        ORDER BY pr.stay_date DESC, pr.id DESC
        LIMIT #{limit}
        OFFSET #{offset}
        </script>
        """,
    )
    fun listReviews(
        @Param("propertyId") propertyId: Long,
        @Param("tag") tag: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<PropertyReviewQueryRow>

    @Select(
        """
        <script>
        SELECT review_id AS reviewId, tag
        FROM property_review_tag
        WHERE review_id IN
        <foreach collection="reviewIds" item="reviewId" open="(" separator="," close=")">
          #{reviewId}
        </foreach>
        ORDER BY review_id, tag
        </script>
        """,
    )
    fun listReviewTags(@Param("reviewIds") reviewIds: List<Long>): List<PropertyReviewTagRow>
}
