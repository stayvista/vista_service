package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.common.api.ApiErrorBody
import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.auth.AuthPrincipal
import com.devoceanblue.stayvista.domain.auth.RedisSessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.Collections
import java.util.Enumeration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class AuthGuardFilter(
    private val objectMapper: ObjectMapper,
    private val redisSessionService: RedisSessionService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val method = request.method
        val path = request.requestURI

        if (method == "OPTIONS") {
            filterChain.doFilter(request, response)
            return
        }

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

        if (requiresUserAuth(method, path)) {
            val principal = resolvePrincipal(request) ?: run {
                writeError(response, ErrorCode.UNAUTHORIZED, "Session access token is required")
                return
            }
            val wrapped = AuthenticatedUserRequest(
                delegate = request,
                userId = principal.userId,
            )
            wrapped.setAttribute("auth.user_id", principal.userId)
            wrapped.setAttribute("auth.user_email", principal.email)
            wrapped.setAttribute("auth.user_name", principal.name)
            filterChain.doFilter(wrapped, response)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isBypassPath(path: String): Boolean {
        return path.startsWith("/actuator") || path.startsWith("/internal")
    }

    private fun isPublicEndpoint(method: String, path: String): Boolean {
        if (method == "POST" && (path == "/v1/auth/login" || path == "/v1/auth/register" || path == "/v1/auth/logout")) return true
        if (method == "GET" && path.startsWith("/v1/search/")) return true
        if (method == "GET" && path == "/v1/destinations/recommendations") return true
        if (method == "GET" && path == "/v1/prices/calendar") return true
        if (method == "GET" && path == "/v1/fx") return true
        if (method == "GET" && path == "/v1/locale") return true
        if (method == "POST" && path == "/v1/locale") return true
        if (method == "POST" && path == "/v1/ai/search/copilot") return true
        if (method == "GET" && path == "/v1/autocomplete") return true
        if (method == "POST" && (path == "/v1/autocomplete/feedback/impression" || path == "/v1/autocomplete/feedback/select")) return true
        if (method == "GET" && (path == "/v1/properties" || path.startsWith("/v1/properties/"))) return true
        if (method == "GET" && (path == "/v1/tickets/products" || path.startsWith("/v1/tickets/products/") || path == "/v1/tickets/events")) return true
        if (method == "GET" && (path == "/v1/packages" || path.startsWith("/v1/packages/"))) return true
        if (method == "GET" && path == "/v1/geo/pois/nearby") return true
        if (method == "GET" && path == "/v1/poi/nearby") return true
        if (method == "GET" && path.startsWith("/v1/poi/")) return true
        if (method == "GET" && path == "/v1/promotions/campaigns") return true
        if (method == "POST" && path == "/v1/chat/recommend") return true
        if (path == "/v1/queue/join" || path == "/v1/queue/status") return true
        return false
    }

    private fun requiresUserAuth(method: String, path: String): Boolean {
        if (path.startsWith("/v1/admin/")) return false
        if (path.startsWith("/v1/me/")) return true
        if (path.startsWith("/v1/tickets/orders/")) return true
        if (method == "POST" && path.matches(Regex("/v1/promotions/campaigns/\\d+/claim"))) return true
        if (method == "GET") return false
        return path.startsWith("/v1/bookings/") ||
            path.matches(Regex("/v1/packages/.+/(holds|confirm)$"))
    }

    private fun resolvePrincipal(request: HttpServletRequest): AuthPrincipal? {
        val token = redisSessionService.extractBearerToken(request.getHeader("Authorization")) ?: return null
        return redisSessionService.resolvePrincipal(token)
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

    private class AuthenticatedUserRequest(
        delegate: HttpServletRequest,
        private val userId: Long,
    ) : HttpServletRequestWrapper(delegate) {
        override fun getHeader(name: String): String? {
            if (name.equals("X-User-Id", ignoreCase = true)) {
                return userId.toString()
            }
            return super.getHeader(name)
        }

        override fun getHeaders(name: String): Enumeration<String> {
            if (name.equals("X-User-Id", ignoreCase = true)) {
                return Collections.enumeration(listOf(userId.toString()))
            }
            return super.getHeaders(name)
        }

        override fun getHeaderNames(): Enumeration<String> {
            val names = Collections.list(super.getHeaderNames())
                .toMutableSet()
            names.add("X-User-Id")
            return Collections.enumeration(names)
        }
    }
}
