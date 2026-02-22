package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class PropertyContentService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun getContent(propertyId: Long): PropertyContentData {
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM property WHERE id = ?",
            Long::class.java,
            propertyId,
        ) ?: 0L
        if (exists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }

        val editorial = jdbcTemplate.query(
            """
            SELECT short_description,
                   long_description,
                   check_in_time,
                   check_out_time,
                   airport_transfer_fee_krw,
                   breakfast_fee_krw,
                   remodeled_year,
                   children_policy
            FROM property_editorial
            WHERE property_id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                PropertyEditorialData(
                    short_description = rs.getString("short_description"),
                    long_description = rs.getString("long_description"),
                    check_in_time = rs.getString("check_in_time"),
                    check_out_time = rs.getString("check_out_time"),
                    airport_transfer_fee_krw = rs.getLong("airport_transfer_fee_krw").takeIf { !rs.wasNull() },
                    breakfast_fee_krw = rs.getLong("breakfast_fee_krw").takeIf { !rs.wasNull() },
                    remodeled_year = rs.getInt("remodeled_year").takeIf { !rs.wasNull() },
                    children_policy = rs.getString("children_policy"),
                )
            },
            propertyId,
        ).firstOrNull()

        val highlights = jdbcTemplate.query(
            """
            SELECT content
            FROM property_highlight
            WHERE property_id = ?
              AND active = 1
            ORDER BY display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ -> rs.getString("content") },
            propertyId,
        )

        val galleryImages = jdbcTemplate.query(
            """
            SELECT image_url
            FROM property_gallery_image
            WHERE property_id = ?
              AND active = 1
            ORDER BY is_cover DESC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ -> rs.getString("image_url") },
            propertyId,
        )

        val cards = jdbcTemplate.query(
            """
            SELECT id, card_code, title, subtitle
            FROM property_staycation_card
            WHERE property_id = ?
              AND active = 1
            ORDER BY display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                StaycationCardRow(
                    id = rs.getLong("id"),
                    cardCode = rs.getString("card_code"),
                    title = rs.getString("title"),
                    subtitle = rs.getString("subtitle"),
                )
            },
            propertyId,
        )

        val cardItemsById = if (cards.isEmpty()) {
            emptyMap()
        } else {
            val placeholders = cards.joinToString(",") { "?" }
            jdbcTemplate.query(
                """
                SELECT card_id, item_text
                FROM property_staycation_item
                WHERE card_id IN ($placeholders)
                  AND active = 1
                ORDER BY card_id ASC, display_order ASC, id ASC
                """.trimIndent(),
                { rs, _ ->
                    StaycationItemRow(
                        cardId = rs.getLong("card_id"),
                        itemText = rs.getString("item_text"),
                    )
                },
                *cards.map { it.id }.toTypedArray(),
            ).groupBy { it.cardId }
                .mapValues { (_, rows) -> rows.map { it.itemText } }
        }

        val roomTypeIds = jdbcTemplate.query(
            """
            SELECT id
            FROM room_type
            WHERE property_id = ?
              AND status = 'ACTIVE'
            ORDER BY id ASC
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") },
            propertyId,
        )

        val roomMediaByType = loadRoomMedia(roomTypeIds)
        val roomFeatureByType = loadRoomFeatures(roomTypeIds)
        val plans = loadRoomPlans(roomTypeIds)
        val benefitsByPlan = loadPlanBenefits(plans.map { it.id })
        val planByRoom = plans.groupBy { it.roomTypeId }

        val roomContent = roomTypeIds.map { roomTypeId ->
            PropertyRoomContentData(
                room_type_id = roomTypeId,
                media = roomMediaByType[roomTypeId] ?: emptyList(),
                features = roomFeatureByType[roomTypeId] ?: emptyList(),
                plans = (planByRoom[roomTypeId] ?: emptyList()).map { plan ->
                    PropertyRatePlanData(
                        plan_code = plan.planCode,
                        occupancy_text = plan.occupancyText,
                        pay_summary = plan.paySummary,
                        urgency_text = plan.urgencyText,
                        list_price_krw = plan.listPriceKrw,
                        sale_price_krw = plan.salePriceKrw,
                        benefits = benefitsByPlan[plan.id] ?: emptyList(),
                    )
                },
            )
        }

        return PropertyContentData(
            editorial = editorial,
            highlights = highlights,
            gallery_images = galleryImages,
            staycation_cards = cards.map { card ->
                PropertyStaycationCardData(
                    card_code = card.cardCode,
                    title = card.title,
                    subtitle = card.subtitle,
                    items = cardItemsById[card.id] ?: emptyList(),
                )
            },
            room_content = roomContent,
        )
    }

    private fun loadRoomMedia(roomTypeIds: List<Long>): Map<Long, List<String>> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        val placeholders = roomTypeIds.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT room_type_id, image_url
            FROM room_type_media
            WHERE room_type_id IN ($placeholders)
              AND active = 1
            ORDER BY room_type_id ASC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                RoomTextRow(
                    roomTypeId = rs.getLong("room_type_id"),
                    value = rs.getString("image_url"),
                )
            },
            *roomTypeIds.toTypedArray(),
        ).groupBy { it.roomTypeId }
            .mapValues { (_, rows) -> rows.map { it.value } }
    }

    private fun loadRoomFeatures(roomTypeIds: List<Long>): Map<Long, List<String>> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        val placeholders = roomTypeIds.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT room_type_id, feature_text
            FROM room_type_feature
            WHERE room_type_id IN ($placeholders)
              AND active = 1
            ORDER BY room_type_id ASC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                RoomTextRow(
                    roomTypeId = rs.getLong("room_type_id"),
                    value = rs.getString("feature_text"),
                )
            },
            *roomTypeIds.toTypedArray(),
        ).groupBy { it.roomTypeId }
            .mapValues { (_, rows) -> rows.map { it.value } }
    }

    private fun loadRoomPlans(roomTypeIds: List<Long>): List<RatePlanRow> {
        if (roomTypeIds.isEmpty()) return emptyList()
        val placeholders = roomTypeIds.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT id, room_type_id, plan_code, occupancy_text, pay_summary, urgency_text, list_price_krw, sale_price_krw
            FROM room_rate_plan
            WHERE room_type_id IN ($placeholders)
              AND active = 1
            ORDER BY room_type_id ASC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                RatePlanRow(
                    id = rs.getLong("id"),
                    roomTypeId = rs.getLong("room_type_id"),
                    planCode = rs.getString("plan_code"),
                    occupancyText = rs.getString("occupancy_text"),
                    paySummary = rs.getString("pay_summary"),
                    urgencyText = rs.getString("urgency_text"),
                    listPriceKrw = rs.getLong("list_price_krw"),
                    salePriceKrw = rs.getLong("sale_price_krw"),
                )
            },
            *roomTypeIds.toTypedArray(),
        )
    }

    private fun loadPlanBenefits(planIds: List<Long>): Map<Long, List<String>> {
        if (planIds.isEmpty()) return emptyMap()
        val placeholders = planIds.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT plan_id, benefit_text
            FROM room_rate_plan_benefit
            WHERE plan_id IN ($placeholders)
              AND active = 1
            ORDER BY plan_id ASC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                PlanBenefitRow(
                    planId = rs.getLong("plan_id"),
                    benefitText = rs.getString("benefit_text"),
                )
            },
            *planIds.toTypedArray(),
        ).groupBy { it.planId }
            .mapValues { (_, rows) -> rows.map { it.benefitText } }
    }
}

data class PropertyContentData(
    val editorial: PropertyEditorialData?,
    val highlights: List<String>,
    val gallery_images: List<String>,
    val staycation_cards: List<PropertyStaycationCardData>,
    val room_content: List<PropertyRoomContentData>,
)

data class PropertyEditorialData(
    val short_description: String?,
    val long_description: String?,
    val check_in_time: String?,
    val check_out_time: String?,
    val airport_transfer_fee_krw: Long?,
    val breakfast_fee_krw: Long?,
    val remodeled_year: Int?,
    val children_policy: String?,
)

data class PropertyStaycationCardData(
    val card_code: String,
    val title: String,
    val subtitle: String?,
    val items: List<String>,
)

data class PropertyRoomContentData(
    val room_type_id: Long,
    val media: List<String>,
    val features: List<String>,
    val plans: List<PropertyRatePlanData>,
)

data class PropertyRatePlanData(
    val plan_code: String,
    val occupancy_text: String?,
    val pay_summary: String?,
    val urgency_text: String?,
    val list_price_krw: Long,
    val sale_price_krw: Long,
    val benefits: List<String>,
)

private data class StaycationCardRow(
    val id: Long,
    val cardCode: String,
    val title: String,
    val subtitle: String?,
)

private data class StaycationItemRow(
    val cardId: Long,
    val itemText: String,
)

private data class RoomTextRow(
    val roomTypeId: Long,
    val value: String,
)

private data class RatePlanRow(
    val id: Long,
    val roomTypeId: Long,
    val planCode: String,
    val occupancyText: String?,
    val paySummary: String?,
    val urgencyText: String?,
    val listPriceKrw: Long,
    val salePriceKrw: Long,
)

private data class PlanBenefitRow(
    val planId: Long,
    val benefitText: String,
)
