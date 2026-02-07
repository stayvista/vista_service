package com.devoceanblue.stayvista.common.web

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.slf4j.MDC

object RequestIdContext {
    const val HEADER_NAME: String = "X-Request-Id"
    const val MDC_KEY: String = "request_id"
    const val ATTRIBUTE_KEY: String = "request_id"

    fun current(): String = MDC.get(MDC_KEY) ?: UUID.randomUUID().toString()

    fun resolveOrCreate(request: HttpServletRequest): String {
        val incoming = request.getHeader(HEADER_NAME)?.trim()
        return if (incoming.isNullOrEmpty()) UUID.randomUUID().toString() else incoming
    }
}
