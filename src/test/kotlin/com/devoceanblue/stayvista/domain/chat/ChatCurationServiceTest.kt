package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class ChatCurationServiceTest {
    @Autowired
    lateinit var chatCurationService: ChatCurationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupTable() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_curation_rule (
              rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
              doc_id VARCHAR(128) NOT NULL,
              rule_type VARCHAR(32) NOT NULL,
              weight INT NOT NULL DEFAULT 100,
              enabled INT NOT NULL DEFAULT 1,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE (doc_id, rule_type)
            )
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM chat_curation_rule")
    }

    @Test
    fun `upsert should apply blacklist and top-pick immediately`() {
        chatCurationService.upsert(
            ChatCurationUpsertRequest(
                doc_id = "poi:11",
                rule_type = "BLACKLIST",
                enabled = true,
            ),
        )
        val topPick = chatCurationService.upsert(
            ChatCurationUpsertRequest(
                doc_id = "ticket:22",
                rule_type = "TOP_PICK",
                weight = 180,
                enabled = true,
            ),
        )

        val active = chatCurationService.activeRules()
        assertTrue(active.blacklistedDocIds.contains("poi:11"))
        assertEquals(180, active.topPickWeights["ticket:22"])

        chatCurationService.update(topPick.rule_id, ChatCurationUpdateRequest(weight = 120, enabled = false))
        val afterDisable = chatCurationService.activeRules()
        assertTrue(!afterDisable.topPickWeights.containsKey("ticket:22"))
    }
}
