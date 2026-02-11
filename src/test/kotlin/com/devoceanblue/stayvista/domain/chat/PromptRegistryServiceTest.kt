package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class PromptRegistryServiceTest {
    @Autowired
    lateinit var promptRegistryService: PromptRegistryService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearPromptTemplates() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_prompt_template (
              template_id BIGINT AUTO_INCREMENT PRIMARY KEY,
              prompt_key VARCHAR(64) NOT NULL,
              version VARCHAR(64) NOT NULL,
              system_prompt CLOB,
              user_prompt_template CLOB,
              is_active INT NOT NULL DEFAULT 0,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE (prompt_key, version)
            )
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM chat_prompt_template WHERE prompt_key = 'chat-core'")
    }

    @Test
    fun `upsert and rollback should switch active prompt version`() {
        promptRegistryService.upsert(
            PromptTemplateUpsertRequest(
                prompt_key = "chat-core",
                version = "v1",
                system_prompt = "system-v1",
                user_prompt_template = "template-v1",
                activate = true,
            ),
        )
        promptRegistryService.upsert(
            PromptTemplateUpsertRequest(
                prompt_key = "chat-core",
                version = "v2",
                system_prompt = "system-v2",
                user_prompt_template = "template-v2",
                activate = true,
            ),
        )

        assertEquals("system-v2", promptRegistryService.resolveSystemPrompt())
        assertEquals("template-v2", promptRegistryService.resolveUserPromptTemplate())

        promptRegistryService.rollback("chat-core", "v1")
        assertEquals("system-v1", promptRegistryService.resolveSystemPrompt())
        assertEquals("template-v1", promptRegistryService.resolveUserPromptTemplate())
    }
}
