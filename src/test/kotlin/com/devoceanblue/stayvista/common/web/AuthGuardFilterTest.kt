package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.domain.auth.RedisSessionService
import io.micrometer.core.instrument.MeterRegistry
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AuthGuardFilterTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var redisSessionService: RedisSessionService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Test
    fun `booking hold should fail when bearer token is missing`() {
        val before = meterRegistry
            .find("auth_guard_reject_total")
            .tags("reason", "missing_or_invalid_token", "path_group", "bookings")
            .counter()
            ?.count()
            ?: 0.0
        mockMvc.perform(
            post("/v1/bookings/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-test")
                .content(
                    """
                    {
                      "room_type_id": 2001,
                      "check_in": "2026-02-10",
                      "check_out": "2026-02-12",
                      "rooms": 1,
                      "guests": {"adults": 2, "children": 0},
                      "price": {"currency": "KRW", "amount_total": 120000}
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
        val after = meterRegistry
            .find("auth_guard_reject_total")
            .tags("reason", "missing_or_invalid_token", "path_group", "bookings")
            .counter()
            ?.count()
            ?: 0.0
        assertEquals(before + 1.0, after, 0.000001)
    }

    @Test
    fun `booking hold should fail when bearer token is invalid`() {
        mockMvc.perform(
            post("/v1/bookings/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-test")
                .header("Authorization", "Bearer invalid-token")
                .content("{}"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `ticket vouchers should fail when bearer token is missing`() {
        mockMvc.perform(
            get("/v1/tickets/orders/tord_2/vouchers"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `me session should fail when bearer token is missing`() {
        mockMvc.perform(
            get("/v1/me/session"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `me session should return principal when bearer token is valid`() {
        val token = redisSessionService
            .createSession(
                userId = 1001L,
                email = "demo-user-1001@stayvista.com",
                name = "Demo User #1001",
            )
            .accessToken
        mockMvc.perform(
            get("/v1/me/session")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.user_id").value(1001))
            .andExpect(jsonPath("$.data.email").value("demo-user-1001@stayvista.com"))
            .andExpect(jsonPath("$.data.name").value("Demo User #1001"))
    }

    @Test
    fun `chat widget snapshot should fail when bearer token is missing`() {
        mockMvc.perform(
            get("/v1/chat/widget/session/snapshot"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `admin endpoint should fail when X-Admin-Id is missing`() {
        mockMvc.perform(
            post("/v1/admin/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
    }

    @Test
    fun `admin endpoint should fail when X-Admin-Id is not numeric`() {
        mockMvc.perform(
            post("/v1/admin/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Admin-Id", "nope")
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `poi nearby should be public without bearer token`() {
        mockMvc.perform(
            get("/v1/poi/nearby")
                .param("bbox", "invalid"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `public endpoint should keep working with valid bearer token`() {
        val token = redisSessionService
            .createSession(
                userId = 1001L,
                email = "demo-user-1001@stayvista.com",
                name = "Demo User #1001",
            )
            .accessToken

        mockMvc.perform(
            get("/v1/locale")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.country").isString)
            .andExpect(jsonPath("$.data.currency").isString)
    }
}
