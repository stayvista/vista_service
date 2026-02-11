package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class ChatExperimentServiceTest {
    @Autowired
    lateinit var chatExperimentService: ChatExperimentService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun resetConfig() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_experiment (
              experiment_key VARCHAR(64) PRIMARY KEY,
              enabled INT NOT NULL DEFAULT 0,
              rollout_percent INT NOT NULL DEFAULT 0,
              treatment_model VARCHAR(128),
              prompt_version VARCHAR(64),
              parameters_json VARCHAR(4000),
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_experiment WHERE experiment_key = 'chat-core'",
            Int::class.java,
        ) ?: 0
        if (count == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO chat_experiment (
                  experiment_key,
                  enabled,
                  rollout_percent,
                  treatment_model,
                  prompt_version,
                  parameters_json
                ) VALUES ('chat-core', 0, 0, NULL, NULL, '{}')
                """.trimIndent(),
            )
        }
        jdbcTemplate.update(
            """
            UPDATE chat_experiment
            SET enabled = 0,
                rollout_percent = 0,
                treatment_model = NULL,
                prompt_version = NULL,
                parameters_json = '{}'
            WHERE experiment_key = 'chat-core'
            """.trimIndent(),
        )
    }

    @Test
    fun `update should apply rollout and treatment assignment`() {
        chatExperimentService.update(
            ChatExperimentUpdateRequest(
                enabled = true,
                rollout_percent = 100,
                treatment_model = "llama3.1:70b-instruct",
                prompt_version = "v2",
                parameters_json = mapOf("route_threshold" to 0.7),
            ),
        )

        val assignment = chatExperimentService.assign(
            request = ChatRecommendRequest(
                message = "서울 일정 추천",
                context = mapOf("user_id" to "1001"),
            ),
            sessionKey = "user:1001",
        )

        assertEquals("TREATMENT", assignment.bucket)
        assertEquals("llama3.1:70b-instruct", assignment.model_override)
        assertEquals("v2", assignment.prompt_version)
        assertTrue(assignment.parameters.containsKey("route_threshold"))
    }

    @Test
    fun `assign should return OFF when rollout is zero`() {
        chatExperimentService.update(
            ChatExperimentUpdateRequest(
                enabled = true,
                rollout_percent = 0,
            ),
        )

        val assignment = chatExperimentService.assign(
            request = ChatRecommendRequest(message = "부산 추천"),
            sessionKey = "session:test",
        )
        assertEquals("OFF", assignment.bucket)
    }
}
