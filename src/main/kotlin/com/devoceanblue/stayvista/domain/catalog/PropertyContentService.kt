package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class PropertyContentService(
    private val mapper: PropertyContentMapper,
) {
    fun getContent(propertyId: Long): PropertyContentData {
        val exists = mapper.countProperty(propertyId)
        if (exists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Property not found")
        }

        val editorial = mapper.findEditorial(propertyId)

        val highlights = mapper.listHighlights(propertyId)

        val galleryImages = mapper.listGalleryImages(propertyId)

        val cards = mapper.listStaycationCards(propertyId)

        val cardItemsById = if (cards.isEmpty()) {
            emptyMap()
        } else {
            mapper.listStaycationItems(cards.map { it.id }).groupBy { it.cardId }
                .mapValues { (_, rows) -> rows.map { it.itemText } }
        }

        val roomTypeIds = mapper.listRoomTypeIds(propertyId)

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
        return mapper.listRoomMedia(roomTypeIds).groupBy { it.roomTypeId }
            .mapValues { (_, rows) -> rows.map { it.value } }
    }

    private fun loadRoomFeatures(roomTypeIds: List<Long>): Map<Long, List<String>> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        return mapper.listRoomFeatures(roomTypeIds).groupBy { it.roomTypeId }
            .mapValues { (_, rows) -> rows.map { it.value } }
    }

    private fun loadRoomPlans(roomTypeIds: List<Long>): List<RatePlanRow> {
        if (roomTypeIds.isEmpty()) return emptyList()
        return mapper.listRoomPlans(roomTypeIds)
    }

    private fun loadPlanBenefits(planIds: List<Long>): Map<Long, List<String>> {
        if (planIds.isEmpty()) return emptyMap()
        return mapper.listPlanBenefits(planIds).groupBy { it.planId }
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

data class StaycationCardRow(
    val id: Long,
    val cardCode: String,
    val title: String,
    val subtitle: String?,
)

data class StaycationItemRow(
    val cardId: Long,
    val itemText: String,
)

data class RoomTextRow(
    val roomTypeId: Long,
    val value: String,
)

data class RatePlanRow(
    val id: Long,
    val roomTypeId: Long,
    val planCode: String,
    val occupancyText: String?,
    val paySummary: String?,
    val urgencyText: String?,
    val listPriceKrw: Long,
    val salePriceKrw: Long,
)

data class PlanBenefitRow(
    val planId: Long,
    val benefitText: String,
)

@Mapper
interface PropertyContentMapper {
    @Select("SELECT COUNT(*) FROM property WHERE id = #{propertyId}")
    fun countProperty(@Param("propertyId") propertyId: Long): Long

    @Select(
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
        WHERE property_id = #{propertyId}
        LIMIT 1
        """,
    )
    fun findEditorial(@Param("propertyId") propertyId: Long): PropertyEditorialData?

    @Select(
        """
        SELECT content
        FROM property_highlight
        WHERE property_id = #{propertyId}
          AND active = 1
        ORDER BY display_order ASC, id ASC
        """,
    )
    fun listHighlights(@Param("propertyId") propertyId: Long): List<String>

    @Select(
        """
        SELECT image_url
        FROM property_gallery_image
        WHERE property_id = #{propertyId}
          AND active = 1
        ORDER BY is_cover DESC, display_order ASC, id ASC
        """,
    )
    fun listGalleryImages(@Param("propertyId") propertyId: Long): List<String>

    @Select(
        """
        SELECT id,
               card_code AS cardCode,
               title,
               subtitle
        FROM property_staycation_card
        WHERE property_id = #{propertyId}
          AND active = 1
        ORDER BY display_order ASC, id ASC
        """,
    )
    fun listStaycationCards(@Param("propertyId") propertyId: Long): List<StaycationCardRow>

    @Select(
        """
        <script>
        SELECT card_id AS cardId, item_text AS itemText
        FROM property_staycation_item
        WHERE card_id IN
        <foreach collection="cardIds" item="cardId" open="(" separator="," close=")">
          #{cardId}
        </foreach>
          AND active = 1
        ORDER BY card_id ASC, display_order ASC, id ASC
        </script>
        """,
    )
    fun listStaycationItems(@Param("cardIds") cardIds: List<Long>): List<StaycationItemRow>

    @Select(
        """
        SELECT id
        FROM room_type
        WHERE property_id = #{propertyId}
          AND status = 'ACTIVE'
        ORDER BY id ASC
        """,
    )
    fun listRoomTypeIds(@Param("propertyId") propertyId: Long): List<Long>

    @Select(
        """
        <script>
        SELECT room_type_id AS roomTypeId, image_url AS value
        FROM room_type_media
        WHERE room_type_id IN
        <foreach collection="roomTypeIds" item="roomTypeId" open="(" separator="," close=")">
          #{roomTypeId}
        </foreach>
          AND active = 1
        ORDER BY room_type_id ASC, display_order ASC, id ASC
        </script>
        """,
    )
    fun listRoomMedia(@Param("roomTypeIds") roomTypeIds: List<Long>): List<RoomTextRow>

    @Select(
        """
        <script>
        SELECT room_type_id AS roomTypeId, feature_text AS value
        FROM room_type_feature
        WHERE room_type_id IN
        <foreach collection="roomTypeIds" item="roomTypeId" open="(" separator="," close=")">
          #{roomTypeId}
        </foreach>
          AND active = 1
        ORDER BY room_type_id ASC, display_order ASC, id ASC
        </script>
        """,
    )
    fun listRoomFeatures(@Param("roomTypeIds") roomTypeIds: List<Long>): List<RoomTextRow>

    @Select(
        """
        <script>
        SELECT id,
               room_type_id AS roomTypeId,
               plan_code AS planCode,
               occupancy_text AS occupancyText,
               pay_summary AS paySummary,
               urgency_text AS urgencyText,
               list_price_krw AS listPriceKrw,
               sale_price_krw AS salePriceKrw
        FROM room_rate_plan
        WHERE room_type_id IN
        <foreach collection="roomTypeIds" item="roomTypeId" open="(" separator="," close=")">
          #{roomTypeId}
        </foreach>
          AND active = 1
        ORDER BY room_type_id ASC, display_order ASC, id ASC
        </script>
        """,
    )
    fun listRoomPlans(@Param("roomTypeIds") roomTypeIds: List<Long>): List<RatePlanRow>

    @Select(
        """
        <script>
        SELECT plan_id AS planId, benefit_text AS benefitText
        FROM room_rate_plan_benefit
        WHERE plan_id IN
        <foreach collection="planIds" item="planId" open="(" separator="," close=")">
          #{planId}
        </foreach>
          AND active = 1
        ORDER BY plan_id ASC, display_order ASC, id ASC
        </script>
        """,
    )
    fun listPlanBenefits(@Param("planIds") planIds: List<Long>): List<PlanBenefitRow>
}
