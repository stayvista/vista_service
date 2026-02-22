package com.devoceanblue.stayvista.domain.promotion

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromotionService(
    private val jdbcTemplate: JdbcTemplate,
    private val domainSupportService: DomainSupportService,
    private val meterRegistry: MeterRegistry,
) {
    fun listCampaigns(section: String, city: String?, limit: Int): PromotionCampaignListData {
        val normalizedSection = normalizeSection(section)
        val normalizedCity = city?.trim().orEmpty()
        val safeLimit = limit.coerceIn(1, 60)
        val now = Instant.now()

        val items = jdbcTemplate.query(
            """
            SELECT
              id,
              code,
              section,
              title,
              subtitle,
              description,
              city,
              image_url,
              badge_text,
              discount_text,
              currency,
              coupon_value_type,
              coupon_value,
              min_order_amount,
              issue_limit,
              issued_count,
              starts_at,
              ends_at,
              priority,
              status,
              GREATEST(issue_limit - issued_count, 0) AS remaining_count
            FROM promotion_campaign
            WHERE section = ?
              AND status IN ('ACTIVE', 'PAUSED')
              AND ends_at >= DATE_SUB(NOW(3), INTERVAL 1 DAY)
              AND (? = '' OR city IS NULL OR LOWER(city) = LOWER(?))
            ORDER BY priority DESC, starts_at ASC, id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val startsAt = rs.getTimestamp("starts_at").toInstant()
                val endsAt = rs.getTimestamp("ends_at").toInstant()
                val remainingCount = rs.getInt("remaining_count")
                PromotionCampaignSummary(
                    campaign_id = rs.getLong("id"),
                    code = rs.getString("code"),
                    section = rs.getString("section"),
                    title = rs.getString("title"),
                    subtitle = rs.getString("subtitle"),
                    description = rs.getString("description"),
                    city = rs.getString("city"),
                    image_url = rs.getString("image_url"),
                    badge_text = rs.getString("badge_text"),
                    discount_text = rs.getString("discount_text"),
                    currency = rs.getString("currency"),
                    coupon_value_type = rs.getString("coupon_value_type"),
                    coupon_value = rs.getBigDecimal("coupon_value").toDouble(),
                    min_order_amount = rs.getLong("min_order_amount"),
                    issue_limit = rs.getInt("issue_limit"),
                    issued_count = rs.getInt("issued_count"),
                    remaining_count = remainingCount,
                    starts_at = startsAt,
                    ends_at = endsAt,
                    priority = rs.getInt("priority"),
                    status = rs.getString("status"),
                    claimable = rs.getString("status") == "ACTIVE" &&
                        now >= startsAt &&
                        now <= endsAt &&
                        remainingCount > 0,
                )
            },
            normalizedSection,
            normalizedCity,
            normalizedCity,
            safeLimit,
        )

        meterRegistry.counter(
            "promotion_campaign_list_total",
            "section",
            normalizedSection,
            "has_city_filter",
            (normalizedCity.isNotBlank()).toString(),
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

        val updatedRows = jdbcTemplate.update(
            """
            UPDATE promotion_campaign
            SET issued_count = issued_count + 1
            WHERE id = ?
              AND status = 'ACTIVE'
              AND starts_at <= NOW(3)
              AND ends_at >= NOW(3)
              AND issued_count < issue_limit
            """.trimIndent(),
            campaignId,
        )
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
        return jdbcTemplate.query(
            """
            SELECT id, code, status, starts_at, ends_at
            FROM promotion_campaign
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                CampaignRow(
                    id = rs.getLong("id"),
                    code = rs.getString("code"),
                    status = rs.getString("status"),
                    startsAt = rs.getTimestamp("starts_at").toInstant(),
                    endsAt = rs.getTimestamp("ends_at").toInstant(),
                )
            },
            campaignId,
        ).firstOrNull()
    }

    private fun findClaim(campaignId: Long, userId: Long): ClaimRow? {
        return jdbcTemplate.query(
            """
            SELECT id, coupon_code, expires_at
            FROM promotion_coupon_claim
            WHERE campaign_id = ?
              AND user_id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                ClaimRow(
                    id = rs.getLong("id"),
                    couponCode = rs.getString("coupon_code"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                )
            },
            campaignId,
            userId,
        ).firstOrNull()
    }

    private fun insertClaim(
        campaignId: Long,
        userId: Long,
        couponCode: String,
        expiresAt: Instant,
    ): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO promotion_coupon_claim(
                  campaign_id,
                  user_id,
                  coupon_code,
                  status,
                  claimed_at,
                  expires_at
                )
                VALUES (?, ?, ?, 'ISSUED', NOW(3), ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, campaignId)
            ps.setLong(2, userId)
            ps.setString(3, couponCode)
            ps.setTimestamp(4, Timestamp.from(expiresAt))
            ps
        }, keyHolder)
        return keyHolder.key?.toLong()
            ?: throw DomainException(ErrorCode.INTERNAL, "쿠폰 발급 식별자를 생성하지 못했습니다.")
    }

    private fun remainingCount(campaignId: Long): Int {
        return jdbcTemplate.queryForObject(
            """
            SELECT GREATEST(issue_limit - issued_count, 0)
            FROM promotion_campaign
            WHERE id = ?
            """.trimIndent(),
            Int::class.java,
            campaignId,
        ) ?: 0
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

private data class CampaignRow(
    val id: Long,
    val code: String,
    val status: String,
    val startsAt: Instant,
    val endsAt: Instant,
)

private data class ClaimRow(
    val id: Long,
    val couponCode: String,
    val expiresAt: Instant?,
)
