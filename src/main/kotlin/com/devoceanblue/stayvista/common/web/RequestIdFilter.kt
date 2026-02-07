package com.devoceanblue.stayvista.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = RequestIdContext.resolveOrCreate(request)
        request.setAttribute(RequestIdContext.ATTRIBUTE_KEY, requestId)
        MDC.put(RequestIdContext.MDC_KEY, requestId)
        response.setHeader(RequestIdContext.HEADER_NAME, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(RequestIdContext.MDC_KEY)
        }
    }
}
