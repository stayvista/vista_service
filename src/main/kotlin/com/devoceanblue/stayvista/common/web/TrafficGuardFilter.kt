package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.common.api.ApiErrorBody
import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.poi.NearbyTokenBucketRateLimiter
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
    private val nearbyRateLimiter: NearbyTokenBucketRateLimiter,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.rate-limit.enabled:true}") private val rateLimitEnabled: Boolean,
    @Value("\${stayvista.rate-limit.search-per-minute:60}") private val searchPerMinute: Int,
    @Value("\${stayvista.rate-limit.autocomplete-per-minute:120}") private val autocompletePerMinute: Int,
    @Value("\${stayvista.rate-limit.booking-hold-per-minute:10}") private val bookingHoldPerMinute: Int,
    @Value("\${stayvista.rate-limit.booking-confirm-per-minute:5}") private val bookingConfirmPerMinute: Int,
    @Value("\${stayvista.rate-limit.package-hold-per-minute:10}") private val packageHoldPerMinute: Int,
    @Value("\${stayvista.rate-limit.package-confirm-per-minute:5}") private val packageConfirmPerMinute: Int,
    @Value("\${stayvista.rate-limit.chat-per-minute:40}") private val chatPerMinute: Int,
    @Value("\${stayvista.rate-limit.bot-strict-per-minute:8}") private val botStrictPerMinute: Int,
    @Value("\${stayvista.queue.enabled:false}") private val queueEnabled: Boolean,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        val method = request.method

        if (method == "OPTIONS") {
            filterChain.doFilter(request, response)
            return
        }

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

        if (isNearbyEndpoint(method, path)) {
            val basePrincipal = request.getHeader("X-User-Id")
                ?.takeIf { it.isNotBlank() }
                ?: request.remoteAddr.orEmpty()
            val botRequest = isLikelyBot(request.getHeader("User-Agent"))
            val principal = if (botRequest) "bot:$basePrincipal" else basePrincipal
            val decision = nearbyRateLimiter.allow(principal)
            if (!decision.allowed) {
                meterRegistry.counter(
                    "rate_limited_count",
                    "endpoint_group",
                    "nearby",
                    "reason",
                    if (botRequest) "bot_or_abuse" else "quota",
                ).increment()
                val retryAfterSeconds = ((decision.retryAfterMs + 999L) / 1000L).coerceAtLeast(1L)
                response.setHeader("Retry-After", retryAfterSeconds.toString())
                writeError(
                    response = response,
                    errorCode = ErrorCode.RATE_LIMITED,
                    message = "Too many nearby requests",
                    details = mapOf("retry_after_ms" to decision.retryAfterMs),
                )
                return
            }
        }

        if (rateLimitEnabled) {
            val policy = ratePolicy(method, path)
            if (policy != null) {
                val basePrincipal = request.getHeader("X-User-Id")
                    ?.takeIf { it.isNotBlank() }
                    ?: request.remoteAddr.orEmpty()
                val botRequest = isLikelyBot(request.getHeader("User-Agent"))
                val principal = if (botRequest) "bot:$basePrincipal" else basePrincipal
                val effectiveLimit = if (botRequest) {
                    minOf(policy.limitPerMinute, botStrictPerMinute.coerceAtLeast(1))
                } else {
                    policy.limitPerMinute
                }

                var finalDecision = RateLimitDecision(true, 1)
                val cost = requestCost(policy.name, request, botRequest)
                repeat(cost) {
                    finalDecision = redisRateLimiter.allow(policy.name, principal, effectiveLimit)
                    if (!finalDecision.allowed) {
                        return@repeat
                    }
                }

                if (!finalDecision.allowed) {
                    meterRegistry.counter(
                        "rate_limited_total",
                        "endpoint_group",
                        policy.name,
                        "reason",
                        if (botRequest) "bot_or_abuse" else "quota",
                    ).increment()
                    meterRegistry.counter("abuse_block_total", "policy", policy.name).increment()
                    response.setHeader("Retry-After", finalDecision.retryAfterSeconds.toString())
                    writeError(response, ErrorCode.RATE_LIMITED, "Too many requests")
                    return
                }

                if (botRequest && isSensitivePath(path)) {
                    val burstDecision = redisRateLimiter.allow("abuse_burst", principal, botStrictPerMinute.coerceAtLeast(1))
                    if (!burstDecision.allowed) {
                        meterRegistry.counter("abuse_block_total", "policy", policy.name).increment()
                        response.setHeader("Retry-After", burstDecision.retryAfterSeconds.toString())
                        writeError(response, ErrorCode.RATE_LIMITED, "Suspicious traffic throttled")
                        return
                    }
                }

            }
        }

        filterChain.doFilter(request, response)
    }

    private fun ratePolicy(method: String, path: String): RatePolicy? {
        if (method == "GET" && path.startsWith("/v1/search/")) {
            return RatePolicy("search", searchPerMinute)
        }
        if (method == "GET" && path == "/v1/prices/calendar") {
            return RatePolicy("search", searchPerMinute)
        }
        if (method == "GET" && path == "/v1/autocomplete") {
            return RatePolicy("autocomplete", autocompletePerMinute)
        }
        if (method == "POST" && path == "/v1/bookings/holds") {
            return RatePolicy("booking_hold", bookingHoldPerMinute)
        }
        if (method == "POST" && path.matches(Regex("/v1/bookings/.+/confirm"))) {
            return RatePolicy("booking_confirm", bookingConfirmPerMinute)
        }
        if (method == "POST" && path.matches(Regex("/v1/packages/.+/holds"))) {
            return RatePolicy("package_hold", packageHoldPerMinute)
        }
        if (method == "POST" && path.matches(Regex("/v1/packages/.+/confirm"))) {
            return RatePolicy("package_confirm", packageConfirmPerMinute)
        }
        if (method == "POST" && (path == "/v1/chat/recommend" || path == "/v1/chat/recommend:stream")) {
            return RatePolicy("chat", chatPerMinute)
        }
        if (method == "POST" && path == "/v1/ai/search/copilot") {
            return RatePolicy("chat", chatPerMinute)
        }
        return null
    }

    private fun isQueueProtectedEndpoint(method: String, path: String): Boolean {
        if (method != "POST") return false
        return path == "/v1/bookings/holds" ||
            path.matches(Regex("/v1/bookings/.+/confirm")) ||
            path == "/v1/tickets/orders/holds" ||
            path.matches(Regex("/v1/tickets/orders/.+/confirm")) ||
            path.matches(Regex("/v1/packages/.+/holds")) ||
            path.matches(Regex("/v1/packages/.+/confirm"))
    }

    private fun isNearbyEndpoint(method: String, path: String): Boolean {
        return method == "GET" && path == "/v1/poi/nearby"
    }

    private fun isLikelyBot(userAgent: String?): Boolean {
        val ua = userAgent?.lowercase()?.trim().orEmpty()
        if (ua.isBlank()) return false
        val signatures = listOf("bot", "crawler", "spider", "scrapy", "python-requests", "curl/")
        return signatures.any { ua.contains(it) }
    }

    private fun requestCost(policyName: String, request: HttpServletRequest, botRequest: Boolean): Int {
        var cost = 1
        if (botRequest) {
            cost += 2
        }
        if (policyName == "autocomplete") {
            val q = request.getParameter("q")?.trim().orEmpty()
            if (q.length <= 1) {
                cost += 1
            }
        }
        if (policyName == "chat") {
            val contentLength = request.contentLengthLong
            if (contentLength > 2_048) {
                cost += 1
            }
        }
        return cost.coerceAtMost(4)
    }

    private fun isSensitivePath(path: String): Boolean {
        return path.startsWith("/v1/chat/") ||
            path.startsWith("/v1/search/") ||
            path.startsWith("/v1/prices/") ||
            path.startsWith("/v1/autocomplete")
    }

    private fun writeError(
        response: HttpServletResponse,
        errorCode: ErrorCode,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        response.status = errorCode.httpStatus.value()
        response.contentType = "application/json"
        val body = ApiResponses.error(
            ApiErrorBody(
                code = errorCode.code,
                message = message,
                details = details,
            ),
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    private data class RatePolicy(
        val name: String,
        val limitPerMinute: Int,
    )
}
