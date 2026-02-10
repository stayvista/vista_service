package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class StructuredChatParserTest {
    private val parser = StructuredChatParser(jacksonObjectMapper())

    @Test
    fun `parseStrict parses valid structured json`() {
        val raw = """
            {
              "assistant_text":"서울 기준으로 추천했어요.",
              "cards":[
                {
                  "type":"PROPERTY",
                  "id":"property:100001",
                  "title":"Dongnae Sky Hotel",
                  "price":"105000 KRW",
                  "why":"도심 접근성이 좋아요",
                  "source":[
                    {
                      "doc_id":"property:100001",
                      "title":"Dongnae Sky Hotel",
                      "snippet":"Busan · 평점 4.1",
                      "source_type":"PROPERTY"
                    }
                  ]
                }
              ],
              "followups":["위치를 도심으로 좁힐까요?"],
              "context_used":{"city":"Seoul"},
              "llm_used":true
            }
        """.trimIndent()

        val parsed = parser.parseStrict(raw)
        assertEquals("서울 기준으로 추천했어요.", parsed.assistantText)
        assertEquals(1, parsed.cards.size)
        assertEquals(true, parsed.llmUsed)
    }

    @Test
    fun `parseStrict throws when card source is missing`() {
        val raw = """
            {
              "assistant_text":"x",
              "cards":[{"type":"PROPERTY","title":"x","source":[]}],
              "followups":[],
              "context_used":{},
              "llm_used":true
            }
        """.trimIndent()

        assertThrows(StructuredOutputParseException::class.java) {
            parser.parseStrict(raw)
        }
    }
}
