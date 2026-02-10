package com.devoceanblue.stayvista.domain.chat

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LlmModelRegistry(
    @Value("\${stayvista.chat.llm.active-model:llama3.1:8b-instruct}") private val activeModel: String,
    @Value("\${stayvista.chat.llm.fallback-model:}") private val fallbackModel: String,
    @Value("\${stayvista.chat.embed.active-model:bge-m3}") private val embedModel: String,
    @Value("\${stayvista.chat.debug.expose-model-version:true}") private val exposeModelVersion: Boolean,
) {
    fun activeModel(): String = activeModel

    fun fallbackModel(): String? = fallbackModel.ifBlank { null }

    fun embedModel(): String = embedModel

    fun shouldExposeModelVersion(): Boolean = exposeModelVersion
}
