package com.devoceanblue.stayvista.domain.queue

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/queue")
class QueueController(
    private val queueService: QueueService,
) {
    @PostMapping("/join")
    fun join(
        @RequestHeader(name = "X-User-Id", required = false) xUserId: String?,
        request: HttpServletRequest,
        @Valid @RequestBody body: QueueJoinRequest,
    ): ResponseEntity<Any> {
        val subject = xUserId ?: request.remoteAddr ?: "anonymous"
        val data = queueService.join(body.queue_key, subject)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(data))
    }

    @GetMapping("/status")
    fun status(@RequestParam ticket: String) = ApiResponses.ok(
        queueService.status(ticket),
    )
}

data class QueueJoinRequest(
    val queue_key: String,
)
