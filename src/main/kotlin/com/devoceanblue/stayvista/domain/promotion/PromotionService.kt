package com.devoceanblue.stayvista.domain.promotion

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.UUID
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromotionService(
    private val mapper: PromotionMapper,
    private val domainSupportService: DomainSupportService,
    private val meterRegistry: MeterRegistry,
) {
    fun listCampaigns(section: String, city: String?, excludeCountry: String?, limit: Int): PromotionCampaignListData {
        val normalizedSection = normalizeSection(section)
        val normalizedCity = city?.trim().orEmpty()
        val normalizedExcludeCountry = excludeCountry?.trim().orEmpty().uppercase()
        val safeLimit = limit.coerceIn(1, 60)
        val now = Instant.now()

        val items = mapper.listCampaigns(
            section = normalizedSection,
            city = normalizedCity,
            excludeCountry = normalizedExcludeCountry,
            limit = safeLimit,
        ).map { row ->
            val startsAt = row.startsAt?.toInstant() ?: Instant.EPOCH
            val endsAt = row.endsAt?.toInstant() ?: Instant.EPOCH
            val remainingCount = row.remainingCount
            PromotionCampaignSummary(
                campaign_id = row.id,
                code = row.code,
                section = row.section,
                title = row.title,
                subtitle = row.subtitle,
                description = row.description,
                city = row.city,
                image_url = row.imageUrl,
                badge_text = row.badgeText,
                discount_text = row.discountText,
                currency = row.currency,
                coupon_value_type = row.couponValueType,
                coupon_value = row.couponValue,
                min_order_amount = row.minOrderAmount,
                issue_limit = row.issueLimit,
                issued_count = row.issuedCount,
                remaining_count = remainingCount,
                starts_at = startsAt,
                ends_at = endsAt,
                priority = row.priority,
                status = row.status,
                claimable = row.status == "ACTIVE" &&
                    now >= startsAt &&
                    now <= endsAt &&
                    remainingCount > 0,
            )
        }

        meterRegistry.counter(
            "promotion_campaign_list_total",
            "section",
            normalizedSection,
            "has_city_filter",
            (normalizedCity.isNotBlank()).toString(),
            "exclude_country",
            normalizedExcludeCountry.ifBlank { "NONE" },
        ).increment()

        return PromotionCampaignListData(
            section = normalizedSection,
            city = normalizedCity.ifBlank { null },
            now = now,
            items = items,
        )
    }

    @Transactional
    fun claimCampaign(userId: Long, campaignId: Long): PromotionClaimData {
        domainSupportService.ensureUserExists(userId)
        val campaign = loadCampaign(campaignId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "프로모션을 찾을 수 없습니다.")

        val now = Instant.now()
        if (campaign.status != "ACTIVE") {
            meterRegistry.counter("promotion_claim_total", "result", "inactive").increment()
            throw DomainException(ErrorCode.CONFLICT, "현재 발급할 수 없는 프로모션입니다.")
        }
        if (now < campaign.startsAt || now > campaign.endsAt) {
            meterRegistry.counter("promotion_claim_total", "result", "out_of_window").increment()
            throw DomainException(ErrorCode.EXPIRED, "쿠폰 발급 기간이 아닙니다.")
        }

        val existing = findClaim(campaignId, userId)
        if (existing != null) {
            meterRegistry.counter("promotion_claim_total", "result", "already_claimed").increment()
            return PromotionClaimData(
                campaign_id = campaignId,
                claim_id = existing.id,
                coupon_code = existing.couponCode,
                already_claimed = true,
                remaining_count = remainingCount(campaignId),
                expires_at = existing.expiresAt ?: campaign.endsAt,
                message = "이미 발급받은 쿠폰입니다.",
            )
        }

        val couponCode = buildCouponCode(campaign.code)
        val claimId = try {
            insertClaim(
                campaignId = campaignId,
                userId = userId,
                couponCode = couponCode,
                expiresAt = campaign.endsAt,
            )
        } catch (e: DuplicateKeyException) {
            val fallback = findClaim(campaignId, userId)
            if (fallback != null) {
                meterRegistry.counter("promotion_claim_total", "result", "already_claimed").increment()
                return PromotionClaimData(
                    campaign_id = campaignId,
                    claim_id = fallback.id,
                    coupon_code = fallback.couponCode,
                    already_claimed = true,
                    remaining_count = remainingCount(campaignId),
                    expires_at = fallback.expiresAt ?: campaign.endsAt,
                    message = "이미 발급받은 쿠폰입니다.",
                )
            }
            throw e
        }

        val updatedRows = mapper.incrementIssuedCount(campaignId)
        if (updatedRows != 1) {
            meterRegistry.counter("promotion_claim_total", "result", "sold_out").increment()
            throw DomainException(ErrorCode.CONFLICT, "쿠폰이 모두 소진되었습니다.")
        }

        val remaining = remainingCount(campaignId)
        domainSupportService.appendOutbox(
            aggregateType = "promotion_campaign",
            aggregateId = campaignId.toString(),
            eventType = "coupon_claimed",
            payload = mapOf(
                "campaign_id" to campaignId,
                "user_id" to userId,
                "claim_id" to claimId,
                "coupon_code" to couponCode,
            ),
        )
        meterRegistry.counter("promotion_claim_total", "result", "success").increment()

        return PromotionClaimData(
            campaign_id = campaignId,
            claim_id = claimId,
            coupon_code = couponCode,
            already_claimed = false,
            remaining_count = remaining,
            expires_at = campaign.endsAt,
            message = "쿠폰이 발급되었습니다.",
        )
    }

    private fun normalizeSection(raw: String): String {
        val normalized = raw.trim().uppercase()
        if (normalized.isBlank()) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "section 파라미터가 필요합니다.")
        }
        return normalized
    }

    private fun loadCampaign(campaignId: Long): CampaignRow? {
        return mapper.findCampaign(campaignId)
            ?.toData()
    }

    private fun findClaim(campaignId: Long, userId: Long): ClaimRow? {
        return mapper.findClaim(campaignId = campaignId, userId = userId)
            ?.toData()
    }

    private fun insertClaim(
        campaignId: Long,
        userId: Long,
        couponCode: String,
        expiresAt: Instant,
    ): Long {
        val command = PromotionClaimInsertCommand(
            campaignId = campaignId,
            userId = userId,
            couponCode = couponCode,
            expiresAt = java.sql.Timestamp.from(expiresAt),
        )
        mapper.insertClaim(command)
        return command.id
            ?: throw DomainException(ErrorCode.INTERNAL, "쿠폰 발급 식별자를 생성하지 못했습니다.")
    }

    private fun remainingCount(campaignId: Long): Int {
        return mapper.remainingCount(campaignId) ?: 0
    }

    private fun buildCouponCode(campaignCode: String): String {
        val prefix = campaignCode
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeLast(6)
            .ifBlank { "PROMO" }
        val suffix = UUID.randomUUID().toString()
            .replace("-", "")
            .take(8)
            .uppercase()
        return "$prefix-$suffix"
    }
}

data class PromotionCampaignListData(
    val section: String,
    val city: String?,
    val now: Instant,
    val items: List<PromotionCampaignSummary>,
)

data class PromotionCampaignSummary(
    val campaign_id: Long,
    val code: String,
    val section: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val city: String?,
    val image_url: String?,
    val badge_text: String?,
    val discount_text: String?,
    val currency: String,
    val coupon_value_type: String,
    val coupon_value: Double,
    val min_order_amount: Long,
    val issue_limit: Int,
    val issued_count: Int,
    val remaining_count: Int,
    val starts_at: Instant,
    val ends_at: Instant,
    val priority: Int,
    val status: String,
    val claimable: Boolean,
)

data class PromotionClaimData(
    val campaign_id: Long,
    val claim_id: Long,
    val coupon_code: String,
    val already_claimed: Boolean,
    val remaining_count: Int,
    val expires_at: Instant,
    val message: String,
)

data class CampaignRow(
    val id: Long,
    val code: String,
    val status: String,
    val startsAt: Instant,
    val endsAt: Instant,
)

data class ClaimRow(
    val id: Long,
    val couponCode: String,
    val expiresAt: Instant?,
)

data class PromotionCampaignQueryRow(
    val id: Long,
    val code: String,
    val section: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val city: String?,
    val imageUrl: String?,
    val badgeText: String?,
    val discountText: String?,
    val currency: String,
    val couponValueType: String,
    val couponValue: Double,
    val minOrderAmount: Long,
    val issueLimit: Int,
    val issuedCount: Int,
    val startsAt: java.sql.Timestamp?,
    val endsAt: java.sql.Timestamp?,
    val priority: Int,
    val status: String,
    val remainingCount: Int,
)

data class PromotionCampaignRow(
    val id: Long,
    val code: String,
    val status: String,
    val startsAt: java.sql.Timestamp?,
    val endsAt: java.sql.Timestamp?,
) {
    fun toData(): CampaignRow = CampaignRow(
        id = id,
        code = code,
        status = status,
        startsAt = startsAt?.toInstant() ?: Instant.EPOCH,
        endsAt = endsAt?.toInstant() ?: Instant.EPOCH,
    )
}

data class PromotionClaimRow(
    val id: Long,
    val couponCode: String,
    val expiresAt: java.sql.Timestamp?,
) {
    fun toData(): ClaimRow = ClaimRow(
        id = id,
        couponCode = couponCode,
        expiresAt = expiresAt?.toInstant(),
    )
}

data class PromotionClaimInsertCommand(
    val campaignId: Long,
    val userId: Long,
    val couponCode: String,
    val expiresAt: java.sql.Timestamp,
    var id: Long? = null,
)

@Mapper
interface PromotionMapper {
    @Select(
        """
        SELECT
          id,
          code,
          section,
          title,
          subtitle,
          description,
          city,
          image_url AS imageUrl,
          badge_text AS badgeText,
          discount_text AS discountText,
          currency,
          coupon_value_type AS couponValueType,
          coupon_value AS couponValue,
          min_order_amount AS minOrderAmount,
          issue_limit AS issueLimit,
          issued_count AS issuedCount,
          starts_at AS startsAt,
          ends_at AS endsAt,
          priority,
          status,
          GREATEST(issue_limit - issued_count, 0) AS remainingCount
        FROM promotion_campaign
        WHERE section = #{section}
          AND status IN ('ACTIVE', 'PAUSED')
          AND ends_at >= DATE_SUB(NOW(3), INTERVAL 1 DAY)
          AND (#{city} = '' OR city IS NULL OR LOWER(city) = LOWER(#{city}))
          AND (
                #{excludeCountry} = ''
                OR city IS NULL
                OR NOT EXISTS (
                    SELECT 1
                    FROM property p
                    WHERE p.city IS NOT NULL
                      AND LOWER(p.city) = LOWER(promotion_campaign.city)
                      AND UPPER(p.country) = #{excludeCountry}
                )
              )
        ORDER BY priority DESC, starts_at ASC, id ASC
        LIMIT #{limit}
        """,
    )
    fun listCampaigns(
        @Param("section") section: String,
        @Param("city") city: String,
        @Param("excludeCountry") excludeCountry: String,
        @Param("limit") limit: Int,
    ): List<PromotionCampaignQueryRow>

    @Update(
        """
        UPDATE promotion_campaign
        SET issued_count = issued_count + 1
        WHERE id = #{campaignId}
          AND status = 'ACTIVE'
          AND starts_at <= NOW(3)
          AND ends_at >= NOW(3)
          AND issued_count < issue_limit
        """,
    )
    fun incrementIssuedCount(@Param("campaignId") campaignId: Long): Int

    @Select(
        """
        SELECT id, code, status, starts_at AS startsAt, ends_at AS endsAt
        FROM promotion_campaign
        WHERE id = #{campaignId}
        LIMIT 1
        """,
    )
    fun findCampaign(@Param("campaignId") campaignId: Long): PromotionCampaignRow?

    @Select(
        """
        SELECT id, coupon_code AS couponCode, expires_at AS expiresAt
        FROM promotion_coupon_claim
        WHERE campaign_id = #{campaignId}
          AND user_id = #{userId}
        LIMIT 1
        """,
    )
    fun findClaim(
        @Param("campaignId") campaignId: Long,
        @Param("userId") userId: Long,
    ): PromotionClaimRow?

    @Insert(
        """
        INSERT INTO promotion_coupon_claim(
          campaign_id,
          user_id,
          coupon_code,
          status,
          claimed_at,
          expires_at
        )
        VALUES (#{campaignId}, #{userId}, #{couponCode}, 'ISSUED', NOW(3), #{expiresAt})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertClaim(command: PromotionClaimInsertCommand): Int

    @Select(
        """
        SELECT GREATEST(issue_limit - issued_count, 0)
        FROM promotion_campaign
        WHERE id = #{campaignId}
        """,
    )
    fun remainingCount(@Param("campaignId") campaignId: Long): Int?
}
