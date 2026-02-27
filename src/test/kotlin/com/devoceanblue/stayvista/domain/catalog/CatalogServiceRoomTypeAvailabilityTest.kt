package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class CatalogServiceRoomTypeAvailabilityTest {
    @Autowired
    lateinit var catalogService: CatalogService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")

        jdbcTemplate.execute(
            """
            CREATE TABLE room_type (
              id BIGINT PRIMARY KEY,
              property_id BIGINT NOT NULL,
              name VARCHAR(120) NOT NULL,
              capacity_adults INT NOT NULL,
              status VARCHAR(20) NOT NULL,
              base_price BIGINT NOT NULL,
              bed_type VARCHAR(60),
              view_type VARCHAR(60),
              bedrooms INT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE inventory_night (
              room_type_id BIGINT NOT NULL,
              stay_date DATE NOT NULL,
              total INT NOT NULL,
              hold INT NOT NULL DEFAULT 0,
              sold INT NOT NULL DEFAULT 0,
              PRIMARY KEY (room_type_id, stay_date)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE booking (
              id BIGINT PRIMARY KEY,
              user_id BIGINT NOT NULL,
              room_type_id BIGINT NOT NULL,
              check_in DATE NOT NULL,
              check_out DATE NOT NULL,
              rooms INT NOT NULL,
              status VARCHAR(20) NOT NULL,
              expires_at TIMESTAMP(3) NOT NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, name, capacity_adults, status, base_price, bed_type, view_type, bedrooms)
            VALUES (501, 9001, 'Deluxe Twin', 2, 'ACTIVE', 120000, 'TWIN', 'CITY', 1)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, name, capacity_adults, status, base_price, bed_type, view_type, bedrooms)
            VALUES (502, 9001, 'Family Suite', 4, 'ACTIVE', 180000, 'DOUBLE', 'MOUNTAIN', 2)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, name, capacity_adults, status, base_price, bed_type, view_type, bedrooms)
            VALUES (503, 9001, 'Dormant Room', 2, 'INACTIVE', 99000, 'DOUBLE', 'CITY', 1)
            """.trimIndent(),
        )

        jdbcTemplate.update("INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold) VALUES (501, DATE '2026-04-01', 2, 0, 1)")
        jdbcTemplate.update("INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold) VALUES (501, DATE '2026-04-02', 2, 0, 1)")
        jdbcTemplate.update("INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold) VALUES (502, DATE '2026-04-01', 3, 0, 2)")
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
    }

    @Test
    fun `listRoomTypes should mark availability by nights coverage and requested rooms`() {
        val result = catalogService.listRoomTypes(
            propertyId = 9001L,
            checkIn = LocalDate.parse("2026-04-01"),
            checkOut = LocalDate.parse("2026-04-03"),
            rooms = 1,
        )

        assertEquals(2, result.items.size)
        val deluxe = result.items.first { it.room_type_id == 501L }
        val family = result.items.first { it.room_type_id == 502L }

        assertEquals(1, deluxe.available_rooms)
        assertTrue(deluxe.is_available ?: false)

        assertEquals(1, family.available_rooms)
        assertFalse(family.is_available ?: true)
    }

    @Test
    fun `listRoomTypes should include active hold metadata for requesting user`() {
        jdbcTemplate.update("UPDATE inventory_night SET hold = 1 WHERE room_type_id = 501 AND stay_date IN (DATE '2026-04-01', DATE '2026-04-02')")
        jdbcTemplate.update(
            """
            INSERT INTO booking(id, user_id, room_type_id, check_in, check_out, rooms, status, expires_at)
            VALUES (91001, 7001, 501, DATE '2026-04-01', DATE '2026-04-03', 1, 'HOLD', TIMESTAMP '2099-01-01 00:00:00')
            """.trimIndent(),
        )

        val result = catalogService.listRoomTypes(
            propertyId = 9001L,
            checkIn = LocalDate.parse("2026-04-01"),
            checkOut = LocalDate.parse("2026-04-03"),
            rooms = 1,
            userId = 7001L,
        )

        val deluxe = result.items.first { it.room_type_id == 501L }
        assertEquals(0, deluxe.available_rooms)
        assertFalse(deluxe.is_available ?: true)
        assertEquals("bkg_91001", deluxe.active_hold_booking_id)
        assertTrue((deluxe.active_hold_expires_at ?: "").contains("T"))

        val family = result.items.first { it.room_type_id == 502L }
        assertNull(family.active_hold_booking_id)
        assertNull(family.active_hold_expires_at)
    }

    @Test
    fun `listRoomTypes should return null availability fields without date range`() {
        val result = catalogService.listRoomTypes(propertyId = 9001L)
        val deluxe = result.items.first { it.room_type_id == 501L }
        assertNull(deluxe.available_rooms)
        assertNull(deluxe.is_available)
    }

    @Test
    fun `listRoomTypes should throw validation when check out is missing`() {
        val ex = assertThrows<DomainException> {
            catalogService.listRoomTypes(
                propertyId = 9001L,
                checkIn = LocalDate.parse("2026-04-01"),
                checkOut = null,
                rooms = 1,
            )
        }
        assertEquals(ErrorCode.VALIDATION_ERROR.code, ex.errorCode.code)
    }
}
