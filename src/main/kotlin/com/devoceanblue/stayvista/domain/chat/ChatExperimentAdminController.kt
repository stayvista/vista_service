package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/chat/experiments")
class ChatExperimentAdminController(
    private val chatExperimentService: ChatExperimentService,
) {
    @GetMapping("/chat-core")
    fun current() = ApiResponses.ok(chatExperimentService.currentConfig())

    @PostMapping("/chat-core")
    fun update(@Valid @RequestBody request: ChatExperimentUpdateRequest) = ApiResponses.ok(
        chatExperimentService.update(request),
    )
}
