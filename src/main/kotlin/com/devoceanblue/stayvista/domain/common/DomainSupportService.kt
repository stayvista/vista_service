package com.devoceanblue.stayvista.domain.common

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import java.util.UUID
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class DomainSupportService(
    private val mapper: DomainSupportMapper,
    private val objectMapper: ObjectMapper,
) {
    fun ensureUserExists(userId: Long) {
        val found = mapper.countActiveUser(userId)
        if (found <= 0L) {
            throw DomainException(ErrorCode.UNAUTHORIZED, "User not found or inactive")
        }
    }

    fun getActiveUser(userId: Long): UserAccount {
        return mapper.findActiveUser(userId)
            ?: throw DomainException(ErrorCode.UNAUTHORIZED, "User not found or inactive")
    }

    data class UserAccount(
        val id: Long,
        val email: String,
        val name: String,
        val status: String,
    )

    fun ensurePartnerExists(partnerId: Long, type: String = "HOTEL") {
        mapper.upsertPartner(
            partnerId = partnerId,
            name = "Partner$partnerId",
            type = type,
        )
    }

    fun appendOutbox(
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        payload: Any,
    ) {
        mapper.insertOutbox(
            eventId = UUID.randomUUID().toString(),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payloadJson = objectMapper.writeValueAsString(payload),
        )
    }
}

@Mapper
interface DomainSupportMapper {
    @Select(
        """
        SELECT COUNT(*)
        FROM user_account
        WHERE id = #{userId}
          AND status = 'ACTIVE'
        """,
    )
    fun countActiveUser(@Param("userId") userId: Long): Long

    @Select(
        """
        SELECT id, email, name, status
        FROM user_account
        WHERE id = #{userId}
          AND status = 'ACTIVE'
        LIMIT 1
        """,
    )
    fun findActiveUser(@Param("userId") userId: Long): DomainSupportService.UserAccount?

    @Insert(
        """
        INSERT INTO partner_account(id, name, type, status)
        VALUES (#{partnerId}, #{name}, #{type}, 'ACTIVE')
        ON DUPLICATE KEY UPDATE name=name
        """,
    )
    fun upsertPartner(
        @Param("partnerId") partnerId: Long,
        @Param("name") name: String,
        @Param("type") type: String,
    ): Int

    @Insert(
        """
        INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload_json, status)
        VALUES (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType}, CAST(#{payloadJson} AS JSON), 'NEW')
        """,
    )
    fun insertOutbox(
        @Param("eventId") eventId: String,
        @Param("aggregateType") aggregateType: String,
        @Param("aggregateId") aggregateId: String,
        @Param("eventType") eventType: String,
        @Param("payloadJson") payloadJson: String,
    ): Int
}
