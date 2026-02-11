package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/chat/curation/rules")
class ChatCurationAdminController(
    private val chatCurationService: ChatCurationService,
) {
    @GetMapping
    fun list() = ApiResponses.ok(mapOf("items" to chatCurationService.list()))

    @PostMapping
    fun upsert(@Valid @RequestBody request: ChatCurationUpsertRequest) = ApiResponses.ok(
        chatCurationService.upsert(request),
    )

    @PatchMapping("/{ruleId}")
    fun update(
        @PathVariable ruleId: Long,
        @Valid @RequestBody request: ChatCurationUpdateRequest,
    ) = ApiResponses.ok(chatCurationService.update(ruleId, request))

    @DeleteMapping("/{ruleId}")
    fun delete(@PathVariable ruleId: Long) = ApiResponses.ok(
        mapOf(
            "rule_id" to ruleId,
            "deleted" to true.also { chatCurationService.delete(ruleId) },
        ),
    )
}
