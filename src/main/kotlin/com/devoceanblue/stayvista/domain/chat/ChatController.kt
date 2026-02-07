package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/chat")
class ChatController(
    private val chatService: ChatService,
) {
    @PostMapping("/recommend")
    fun recommend(@Valid @RequestBody request: ChatRecommendRequest) = ApiResponses.ok(
        chatService.recommend(request),
    )
}
