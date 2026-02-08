package com.devoceanblue.stayvista.domain.packagee

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
class PackageControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var packageService: PackageService

    @Test
    fun `list package orders should forward status and limit to service`() {
        given(packageService.listOrders("HOLD", 30)).willReturn(
            PackageOrderListData(
                items = listOf(
                    PackageOrderSummary(
                        package_order_id = "pkg_9101",
                        package_id = 5001L,
                        user_id = 1001L,
                        status = "HOLD",
                        booking_id = "bkg_3001",
                        ticket_order_id = "tord_4001",
                        expires_at = "2026-02-10T10:00:00Z",
                        created_at = "2026-02-10T09:40:00Z",
                        updated_at = "2026-02-10T09:45:00Z",
                    ),
                ),
            ),
        )

        mockMvc.perform(
            get("/v1/admin/packages/orders")
                .header("X-Admin-Id", "1")
                .param("status", "HOLD")
                .param("limit", "30"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.items[0].package_order_id").value("pkg_9101"))
            .andExpect(jsonPath("$.data.items[0].status").value("HOLD"))
            .andExpect(jsonPath("$.data.items[0].booking_id").value("bkg_3001"))
            .andExpect(jsonPath("$.data.items[0].ticket_order_id").value("tord_4001"))

        then(packageService).should().listOrders("HOLD", 30)
    }

    @Test
    fun `list package orders should use default limit when omitted`() {
        given(packageService.listOrders(null, 50)).willReturn(
            PackageOrderListData(items = emptyList()),
        )

        mockMvc.perform(
            get("/v1/admin/packages/orders")
                .header("X-Admin-Id", "1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.request_id").isNotEmpty)
            .andExpect(jsonPath("$.data.items.length()").value(0))

        then(packageService).should().listOrders(null, 50)
    }

    @Test
    fun `list package orders should require admin header`() {
        mockMvc.perform(get("/v1/admin/packages/orders"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
    }
}
