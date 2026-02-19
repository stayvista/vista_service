package com.devoceanblue.stayvista.common.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsConfig(
    @Value("\${stayvista.web.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:5180,http://127.0.0.1:5180}")
    private val allowedOriginsCsv: String,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val allowedOrigins = allowedOriginsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toTypedArray()

        registry.addMapping("/**")
            .allowedOrigins(*allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("request_id", "trace_id", "server_time")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
