package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ChatPreferenceControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var preferenceProfileService: PreferenceProfileService

    @Test
    fun `feedback should update profile and return snapshot`() {
        val request = ChatPreferenceFeedbackRequest(
            user_id = "1001",
            like_tags = listOf("culture"),
            like_categories = listOf("POI"),
        )
        given(preferenceProfileService.applyExplicitFeedback(request)).willReturn(
            PreferenceProfileSnapshot(
                tagWeights = mapOf("culture" to 2),
                categoryWeights = mapOf("POI" to 1),
            ),
        )

        mockMvc.perform(
            post("/v1/chat/preferences/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "user_id": "1001",
                      "like_tags": ["culture"],
                      "like_categories": ["POI"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.tagWeights.culture").value(2))
            .andExpect(jsonPath("$.data.categoryWeights.POI").value(1))

        then(preferenceProfileService).should().applyExplicitFeedback(request)
    }
}
