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
                  "sources":[
                    {
                      "doc_id":"property:100001",
                      "title":"Dongnae Sky Hotel",
                      "url":"https://stayvista.local/property/100001",
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
        assertEquals("https://stayvista.local/property/100001", parsed.cards.first().source.first().url)
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

    @Test
    fun `parseStrict should accept legacy source key`() {
        val raw = """
            {
              "assistant_text":"x",
              "cards":[
                {
                  "type":"PROPERTY",
                  "title":"x",
                  "source":[
                    {
                      "doc_id":"property:1",
                      "title":"x",
                      "snippet":"x",
                      "source_type":"PROPERTY"
                    }
                  ]
                }
              ],
              "followups":[],
              "context_used":{},
              "llm_used":true
            }
        """.trimIndent()

        val parsed = parser.parseStrict(raw)
        assertEquals(1, parsed.cards.size)
        assertEquals("property:1", parsed.cards.first().source.first().doc_id)
    }
}
