package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.domain.queue.QueueService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "stayvista.queue.enabled=true",
        "stayvista.rate-limit.enabled=true",
        "stayvista.rate-limit.booking-hold-per-minute=10",
        "stayvista.rate-limit.search-per-minute=60",
    ],
)
@AutoConfigureMockMvc
class TrafficGuardFilterTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var queueService: QueueService

    @MockitoBean
    lateinit var redisRateLimiter: RedisRateLimiter

    @BeforeEach
    fun setup() {
        given(redisRateLimiter.allow("booking_hold", "1001", 10)).willReturn(
            RateLimitDecision(
                allowed = true,
                retryAfterSeconds = 1,
            ),
        )
        given(redisRateLimiter.allow("search", "127.0.0.1", 60)).willReturn(
            RateLimitDecision(
                allowed = true,
                retryAfterSeconds = 1,
            ),
        )
        given(redisRateLimiter.allow("search", "1001", 60)).willReturn(
            RateLimitDecision(
                allowed = true,
                retryAfterSeconds = 1,
            ),
        )
    }

    @Test
    fun `booking hold should return QUEUE_REQUIRED when queue token is missing`() {
        mockMvc.perform(
            post("/v1/bookings/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-queue-missing")
                .header("X-User-Id", "1001")
                .content(bookingHoldBody()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("QUEUE_REQUIRED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `booking hold should return QUEUE_TOKEN_INVALID when queue token is invalid`() {
        given(queueService.validateAdmitToken("bad-token")).willReturn(false)

        mockMvc.perform(
            post("/v1/bookings/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-queue-invalid")
                .header("X-User-Id", "1001")
                .header("Queue-Token", "bad-token")
                .content(bookingHoldBody()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("QUEUE_TOKEN_INVALID"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `booking hold should return RATE_LIMITED when policy rejects request`() {
        given(queueService.validateAdmitToken("qat_ok")).willReturn(true)
        given(redisRateLimiter.allow("booking_hold", "1001", 10)).willReturn(
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = 17,
            ),
        )

        mockMvc.perform(
            post("/v1/bookings/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-rate-limited")
                .header("X-User-Id", "1001")
                .header("Queue-Token", "qat_ok")
                .content(bookingHoldBody()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "17"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `search should return RATE_LIMITED using remote ip principal`() {
        given(redisRateLimiter.allow("search", "127.0.0.1", 60)).willReturn(
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = 12,
            ),
        )

        mockMvc.perform(
            get("/v1/search/properties")
                .param("city", "Seoul")
                .param("limit", "10"),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "12"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    @Test
    fun `search should return RATE_LIMITED using X-User-Id principal`() {
        given(redisRateLimiter.allow("search", "1001", 60)).willReturn(
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = 9,
            ),
        )

        mockMvc.perform(
            get("/v1/search/properties")
                .header("X-User-Id", "1001")
                .param("city", "Seoul")
                .param("limit", "10"),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "9"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.request_id").isNotEmpty)
    }

    private fun bookingHoldBody(): String {
        return """
            {
              "room_type_id": 2001,
              "check_in": "2026-02-10",
              "check_out": "2026-02-12",
              "rooms": 1,
              "guests": {"adults": 2, "children": 0},
              "price": {"currency": "KRW", "amount_total": 120000}
            }
        """.trimIndent()
    }
}
