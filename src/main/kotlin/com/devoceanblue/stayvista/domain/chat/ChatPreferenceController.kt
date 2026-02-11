package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/chat/preferences")
class ChatPreferenceController(
    private val preferenceProfileService: PreferenceProfileService,
) {
    @PostMapping("/feedback")
    fun feedback(@Valid @RequestBody request: ChatPreferenceFeedbackRequest) = ApiResponses.ok(
        preferenceProfileService.applyExplicitFeedback(request),
    )
}

data class ChatPreferenceFeedbackRequest(
    val user_id: String = "",
    val session_id: String = "",
    val conversation_id: String = "",
    val like_tags: List<String> = emptyList(),
    val dislike_tags: List<String> = emptyList(),
    val like_categories: List<String> = emptyList(),
    val dislike_categories: List<String> = emptyList(),
)
