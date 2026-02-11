package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/chat/prompts")
class ChatPromptAdminController(
    private val promptRegistryService: PromptRegistryService,
) {
    @GetMapping
    fun list(@RequestParam(name = "prompt_key", required = false) promptKey: String?) = ApiResponses.ok(
        mapOf("items" to promptRegistryService.list(promptKey)),
    )

    @PostMapping
    fun upsert(@Valid @RequestBody request: PromptTemplateUpsertRequest) = ApiResponses.ok(
        promptRegistryService.upsert(request),
    )

    @PostMapping("/rollback")
    fun rollback(@Valid @RequestBody request: PromptTemplateRollbackRequest) = ApiResponses.ok(
        promptRegistryService.rollback(request.prompt_key, request.version),
    )
}
