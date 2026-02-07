package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.common.api.ApiErrorBody
import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.queue.QueueService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TrafficGuardFilter(
    private val objectMapper: ObjectMapper,
    private val queueService: QueueService,
    private val redisRateLimiter: RedisRateLimiter,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.rate-limit.enabled:true}") private val rateLimitEnabled: Boolean,
    @Value("\${stayvista.rate-limit.search-per-minute:60}") private val searchPerMinute: Int,
    @Value("\${stayvista.rate-limit.booking-hold-per-minute:10}") private val bookingHoldPerMinute: Int,
    @Value("\${stayvista.rate-limit.booking-confirm-per-minute:5}") private val bookingConfirmPerMinute: Int,
    @Value("\${stayvista.queue.enabled:false}") private val queueEnabled: Boolean,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        val method = request.method

        if (queueEnabled && isQueueProtectedEndpoint(method, path)) {
            val token = request.getHeader("Queue-Token")
            if (token.isNullOrBlank()) {
                writeError(response, ErrorCode.QUEUE_REQUIRED, "Queue admit token is required")
                return
            }
            if (!queueService.validateAdmitToken(token)) {
                writeError(response, ErrorCode.QUEUE_TOKEN_INVALID, "Queue token is invalid or expired")
                return
            }
        }

        if (rateLimitEnabled) {
            val policy = ratePolicy(method, path)
            if (policy != null) {
                val principal = request.getHeader("X-User-Id")
                    ?.takeIf { it.isNotBlank() }
                    ?: request.remoteAddr.orEmpty()
                val decision = redisRateLimiter.allow(policy.name, principal, policy.limitPerMinute)
                if (!decision.allowed) {
                    meterRegistry.counter("rate_limited_total", "endpoint_group", policy.name).increment()
                    response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
                    writeError(response, ErrorCode.RATE_LIMITED, "Too many requests")
                    return
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun ratePolicy(method: String, path: String): RatePolicy? {
        if (method == "GET" && path.startsWith("/v1/search/")) {
            return RatePolicy("search", searchPerMinute)
        }
        if (method == "POST" && path == "/v1/bookings/holds") {
            return RatePolicy("booking_hold", bookingHoldPerMinute)
        }
        if (method == "POST" && path.matches(Regex("/v1/bookings/.+/confirm"))) {
            return RatePolicy("booking_confirm", bookingConfirmPerMinute)
        }
        return null
    }

    private fun isQueueProtectedEndpoint(method: String, path: String): Boolean {
        if (method != "POST") return false
        return path == "/v1/bookings/holds" ||
            path.matches(Regex("/v1/bookings/.+/confirm")) ||
            path == "/v1/tickets/orders/holds" ||
            path.matches(Regex("/v1/tickets/orders/.+/confirm"))
    }

    private fun writeError(response: HttpServletResponse, errorCode: ErrorCode, message: String) {
        response.status = errorCode.httpStatus.value()
        response.contentType = "application/json"
        val body = ApiResponses.error(
            ApiErrorBody(
                code = errorCode.code,
                message = message,
            ),
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    private data class RatePolicy(
        val name: String,
        val limitPerMinute: Int,
    )
}
