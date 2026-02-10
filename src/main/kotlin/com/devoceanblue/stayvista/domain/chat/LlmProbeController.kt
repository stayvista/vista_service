package com.devoceanblue.stayvista.domain.chat

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/llm")
class LlmProbeController(
    private val llmProbeService: LlmProbeService,
) {
    @GetMapping("/healthz")
    fun healthz(): ResponseEntity<Map<String, Any>> {
        val result = llmProbeService.healthz()
        val status = if (result.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(
            mapOf(
                "service" to "llm",
                "status" to result.status,
                "ok" to result.ok,
                "detail" to result.detail,
            ),
        )
    }

    @GetMapping("/readyz")
    fun readyz(): ResponseEntity<Map<String, Any>> {
        val result = llmProbeService.readyz()
        val status = if (result.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(
            mapOf(
                "service" to "llm",
                "status" to result.status,
                "ok" to result.ok,
                "detail" to result.detail,
            ),
        )
    }
}
