package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.common.api.ApiErrorBody
import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class AuthGuardFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val method = request.method
        val path = request.requestURI

        if (isBypassPath(path) || isPublicEndpoint(method, path)) {
            filterChain.doFilter(request, response)
            return
        }

        if (path.startsWith("/v1/admin/")) {
            val adminId = request.getHeader("X-Admin-Id")
            if (adminId.isNullOrBlank()) {
                writeError(response, ErrorCode.FORBIDDEN, "X-Admin-Id header is required")
                return
            }
            if (adminId.toLongOrNull() == null) {
                writeError(response, ErrorCode.VALIDATION_ERROR, "X-Admin-Id must be numeric")
                return
            }
            filterChain.doFilter(request, response)
            return
        }

        if (requiresUserHeader(method, path)) {
            val userId = request.getHeader("X-User-Id")
            if (userId.isNullOrBlank()) {
                writeError(response, ErrorCode.UNAUTHORIZED, "X-User-Id header is required")
                return
            }
            if (userId.toLongOrNull() == null) {
                writeError(response, ErrorCode.VALIDATION_ERROR, "X-User-Id must be numeric")
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun isBypassPath(path: String): Boolean {
        return path.startsWith("/actuator") || path.startsWith("/internal")
    }

    private fun isPublicEndpoint(method: String, path: String): Boolean {
        if (method == "GET" && path.startsWith("/v1/search/")) return true
        if (method == "GET" && (path == "/v1/properties" || path.startsWith("/v1/properties/"))) return true
        if (method == "GET" && (path == "/v1/tickets/products" || path.startsWith("/v1/tickets/products/") || path == "/v1/tickets/events")) return true
        if (method == "GET" && (path == "/v1/packages" || path.startsWith("/v1/packages/"))) return true
        if (method == "GET" && path == "/v1/geo/pois/nearby") return true
        if (method == "POST" && path == "/v1/chat/recommend") return true
        if (path == "/v1/queue/join" || path == "/v1/queue/status") return true
        return false
    }

    private fun requiresUserHeader(method: String, path: String): Boolean {
        if (path.startsWith("/v1/admin/")) return false
        if (method == "GET") return false
        return path.startsWith("/v1/bookings/") ||
            path.startsWith("/v1/tickets/orders/") ||
            path.matches(Regex("/v1/packages/.+/(holds|confirm)$"))
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
}
