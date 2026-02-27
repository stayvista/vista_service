package com.devoceanblue.stayvista.domain.catalog

import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerRoomTypeQueryBindingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var catalogService: CatalogService

    @MockitoBean
    lateinit var homeContentService: HomeContentService

    @MockitoBean
    lateinit var propertyContentService: PropertyContentService

    @Test
    fun `list room types should bind snake case availability params`() {
        given(
            catalogService.listRoomTypes(
                propertyId = 100001L,
                checkIn = LocalDate.parse("2026-03-02"),
                checkOut = LocalDate.parse("2026-03-03"),
                rooms = 2,
                userId = null,
            ),
        ).willReturn(RoomTypeListData(items = emptyList()))

        mockMvc.perform(
            get("/v1/properties/100001/room-types")
                .param("check_in", "2026-03-02")
                .param("check_out", "2026-03-03")
                .param("rooms", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.items.length()").value(0))

        then(catalogService).should().listRoomTypes(
            propertyId = 100001L,
            checkIn = LocalDate.parse("2026-03-02"),
            checkOut = LocalDate.parse("2026-03-03"),
            rooms = 2,
            userId = null,
        )
    }

    @Test
    fun `list room types should pass authenticated user id when provided`() {
        given(
            catalogService.listRoomTypes(
                propertyId = 100001L,
                checkIn = LocalDate.parse("2026-03-02"),
                checkOut = LocalDate.parse("2026-03-03"),
                rooms = 1,
                userId = 1001L,
            ),
        ).willReturn(RoomTypeListData(items = emptyList()))

        mockMvc.perform(
            get("/v1/properties/100001/room-types")
                .param("check_in", "2026-03-02")
                .param("check_out", "2026-03-03")
                .param("rooms", "1")
                .header("X-User-Id", "1001"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.items.length()").value(0))

        then(catalogService).should().listRoomTypes(
            propertyId = 100001L,
            checkIn = LocalDate.parse("2026-03-02"),
            checkOut = LocalDate.parse("2026-03-03"),
            rooms = 1,
            userId = 1001L,
        )
    }
}
