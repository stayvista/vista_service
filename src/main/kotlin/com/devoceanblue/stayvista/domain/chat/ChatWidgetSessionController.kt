package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/chat/widget/session")
class ChatWidgetSessionController(
    private val chatWidgetSessionService: ChatWidgetSessionService,
) {
    @PostMapping("/snapshot")
    fun saveSnapshot(
        @RequestHeader(name = "X-User-Id") userId: Long,
        @Valid @RequestBody request: ChatWidgetSessionSaveRequest,
    ) = ApiResponses.ok(
        chatWidgetSessionService.save(userId, request),
    )

    @GetMapping("/snapshot")
    fun loadSnapshot(
        @RequestHeader(name = "X-User-Id") userId: Long,
    ) = ApiResponses.ok(
        chatWidgetSessionService.load(userId),
    )
}
