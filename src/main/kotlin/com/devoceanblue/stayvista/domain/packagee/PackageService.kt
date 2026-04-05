package com.devoceanblue.stayvista.domain.packagee

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.idempotency.IdempotencyService
import com.devoceanblue.stayvista.domain.booking.BookingCancelRequest
import com.devoceanblue.stayvista.domain.booking.BookingConfirmRequest
import com.devoceanblue.stayvista.domain.booking.BookingHoldRequest
import com.devoceanblue.stayvista.domain.booking.BookingMoney
import com.devoceanblue.stayvista.domain.booking.BookingService
import com.devoceanblue.stayvista.domain.booking.BookingGuestRequest
import com.devoceanblue.stayvista.domain.ticket.TicketConfirmRequest
import com.devoceanblue.stayvista.domain.ticket.TicketHoldPrice
import com.devoceanblue.stayvista.domain.ticket.TicketHoldRequest
import com.devoceanblue.stayvista.domain.ticket.TicketService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class PackageService(
    private val mapper: PackageMapper,
    private val bookingService: BookingService,
    private val ticketService: TicketService,
    private val idempotencyService: IdempotencyService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun createPackage(request: CreatePackageRequest): Long {
        val command = PackageProductInsertCommand(
            name = request.name,
            status = request.status,
            currency = request.price.currency,
            amountTotal = request.price.amount_total,
            imageUrl = request.image_url,
        )
        mapper.insertPackageProduct(command)
        val packageId = command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package")

        request.components.forEach { component ->
            mapper.insertPackageComponent(
                packageId = packageId,
                componentType = component.type,
                roomTypeId = component.room_type_id,
                ticketEventId = component.event_id,
                nights = component.nights,
                rooms = component.rooms,
                quantity = component.quantity,
            )
        }
        return packageId
    }

    fun getPackage(packageId: Long): PackageDetail {
        val pack = mapper.findPackage(packageId) ?: throw DomainException(ErrorCode.NOT_FOUND, "Package not found")
        return PackageDetail(
            package_id = pack.id,
            name = pack.name,
            status = pack.status,
            components = components(pack.id),
            price = PackagePrice(pack.currency, pack.amount),
            image_url = pack.imageUrl,
        )
    }

    fun listPackages(): PackageListData {
        val items = mapper.listActivePackages().map { it.toSummary() }
        return PackageListData(items)
    }

    fun listOrders(status: String?, limit: Int): PackageOrderListData {
        val safeLimit = limit.coerceIn(1, 200)
        val params = mutableListOf<Any>()
        val where = mutableListOf<String>()
        if (!status.isNullOrBlank()) {
            where += "status = ?"
            params += status
        }

        val sql = buildString {
            append(
                """
                SELECT id, package_id, user_id, status, booking_id, ticket_order_id, expires_at, created_at, updated_at
                FROM package_order
                """.trimIndent(),
            )
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where.joinToString(" AND "))
            }
            append(" ORDER BY id DESC LIMIT ?")
        }
        params += safeLimit

        val items = if (!status.isNullOrBlank()) {
            mapper.listOrdersByStatus(status = status, limit = safeLimit).map { it.toSummary() }
        } else {
            mapper.listOrders(limit = safeLimit).map { it.toSummary() }
        }
        return PackageOrderListData(items)
    }

    fun hold(userId: Long, packageId: Long, idempotencyKey: String, request: PackageHoldRequest): PackageHoldData {
        return idempotencyService.execute(
            scope = "PACKAGE_HOLD",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "package_id" to packageId, "request" to request),
            responseType = PackageHoldData::class.java,
        ) {
            val components = components(packageId)
            val accommodation = components.firstOrNull { it.type == "ACCOMMODATION" }
                ?: throw DomainException(ErrorCode.NOT_FOUND, "Accommodation component missing")
            val ticket = components.firstOrNull { it.type == "TICKET" }
                ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket component missing")

            val packageOrderId = transactionTemplate.execute {
                createPackageOrder(packageId, userId)
            } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package order")

            val bookingIdem = "pkg-hold-booking-$packageOrderId"
            val ticketIdem = "pkg-hold-ticket-$packageOrderId"
            val bookingHold = try {
                bookingService.createHold(
                    userId = userId,
                    idempotencyKey = bookingIdem,
                    request = BookingHoldRequest(
                        room_type_id = accommodation.room_type_id ?: throw DomainException(ErrorCode.NOT_FOUND, "room_type_id missing"),
                        check_in = request.check_in,
                        check_out = request.check_out,
                        rooms = request.rooms,
                        guests = BookingGuestRequest(adults = 2, children = 0),
                        price = BookingMoney(currency = "KRW", amount_total = 0),
                    ),
                )
            } catch (ex: RuntimeException) {
                transactionTemplate.execute {
                    updatePackageOrderStatus(packageOrderId, "FAILED")
                }
                throw ex
            }

            val ticketHold = try {
                ticketService.hold(
                    userId = userId,
                    idempotencyKey = ticketIdem,
                    request = TicketHoldRequest(
                        event_id = ticket.event_id ?: throw DomainException(ErrorCode.NOT_FOUND, "event_id missing"),
                        quantity = request.ticket_quantity,
                        price = TicketHoldPrice(currency = "KRW", amount_total = 0),
                    ),
                )
            } catch (ex: RuntimeException) {
                bookingService.cancel(
                    userId = userId,
                    rawBookingId = bookingHold.booking_id,
                    idempotencyKey = "pkg-compensate-$packageOrderId",
                    request = BookingCancelRequest("PACKAGE_HOLD_FAILED"),
                )
                transactionTemplate.execute {
                    updatePackageOrderStatus(packageOrderId, "FAILED")
                }
                throw ex
            }

            val bookingNumericId = bookingHold.booking_id.removePrefix("bkg_").toLong()
            val ticketNumericId = ticketHold.order_id.removePrefix("tord_").toLong()
            val expiresAt = minOf(
                Instant.parse(bookingHold.expires_at),
                Instant.parse(ticketHold.expires_at),
            )

            transactionTemplate.execute {
                mapper.markPackageOrderHeld(
                    packageOrderId = packageOrderId,
                    bookingId = bookingNumericId,
                    ticketOrderId = ticketNumericId,
                    expiresAt = java.sql.Timestamp.from(expiresAt),
                )
            }

            PackageHoldData(
                package_order_id = "pkg_$packageOrderId",
                status = "HOLD",
                components = PackageHoldComponents(
                    booking_id = bookingHold.booking_id,
                    ticket_order_id = ticketHold.order_id,
                ),
                expires_at = expiresAt.toString(),
            )
        }
    }

    fun confirm(userId: Long, packageId: Long, idempotencyKey: String, request: PackageConfirmRequest): PackageConfirmData {
        return idempotencyService.execute(
            scope = "PACKAGE_CONFIRM",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "package_id" to packageId, "request" to request),
            responseType = PackageConfirmData::class.java,
        ) {
            val packageOrderId = request.package_order_id.removePrefix("pkg_").toLongOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid package_order_id")
            val row = mapper.findPackageOrderForUpdate(
                packageOrderId = packageOrderId,
                packageId = packageId,
                userId = userId,
            ) ?: throw DomainException(ErrorCode.NOT_FOUND, "Package order not found")
            if (row.status != "HOLD") {
                throw DomainException(ErrorCode.CONFLICT, "Package order cannot be confirmed from ${row.status}")
            }
            if (row.expiresAt != null && row.expiresAt.isBefore(Instant.now())) {
                transactionTemplate.execute {
                    updatePackageOrderStatus(row.id, "EXPIRED")
                }
                throw DomainException(ErrorCode.ORDER_EXPIRED, "Package order hold expired")
            }

            val bookingId = "bkg_${row.bookingId}"
            val ticketOrderId = "tord_${row.ticketOrderId}"
            bookingService.confirm(
                userId = userId,
                rawBookingId = bookingId,
                idempotencyKey = "pkg-confirm-booking-${row.id}",
                request = BookingConfirmRequest(
                    payment_method = "CARD",
                    payment_token = request.payment_token,
                    agree_terms = true,
                ),
            )
            try {
                ticketService.confirm(
                    userId = userId,
                    rawOrderId = ticketOrderId,
                    idempotencyKey = "pkg-confirm-ticket-${row.id}",
                    request = TicketConfirmRequest(
                        payment_method = "CARD",
                        payment_token = request.payment_token,
                    ),
                )
            } catch (ex: RuntimeException) {
                bookingService.cancel(
                    userId = userId,
                    rawBookingId = bookingId,
                    idempotencyKey = "pkg-confirm-compensate-${row.id}",
                    request = BookingCancelRequest("PACKAGE_CONFIRM_FAILED"),
                )
                transactionTemplate.execute {
                    updatePackageOrderStatus(row.id, "FAILED")
                }
                throw ex
            }

            transactionTemplate.execute {
                updatePackageOrderStatus(row.id, "CONFIRMED")
            }
            PackageConfirmData(
                package_order_id = "pkg_${row.id}",
                status = "CONFIRMED",
            )
        }
    }

    private fun createPackageOrder(packageId: Long, userId: Long): Long {
        val command = PackageOrderInsertCommand(packageId = packageId, userId = userId)
        mapper.insertPackageOrder(command)
        return command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package order")
    }

    private fun updatePackageOrderStatus(packageOrderId: Long, status: String) {
        mapper.updatePackageOrderStatus(packageOrderId = packageOrderId, status = status)
    }

    private fun components(packageId: Long): List<PackageComponent> {
        return mapper.listComponents(packageId).map { it.toComponent() }
    }
}

data class CreatePackageRequest(
    val name: String,
    val status: String = "ACTIVE",
    val price: PackagePrice,
    val image_url: String? = null,
    val components: List<PackageComponent>,
)

data class PackagePrice(
    val currency: String,
    val amount_total: Long,
)

data class PackageComponent(
    val type: String,
    val room_type_id: Long? = null,
    val event_id: Long? = null,
    val nights: Int? = null,
    val rooms: Int? = null,
    val quantity: Int? = null,
)

data class PackageSummary(
    val package_id: Long,
    val name: String,
    val status: String,
    val price: PackagePrice,
    val image_url: String?,
)

data class PackageListData(
    val items: List<PackageSummary>,
)

data class PackageDetail(
    val package_id: Long,
    val name: String,
    val status: String,
    val components: List<PackageComponent>,
    val price: PackagePrice,
    val image_url: String?,
)

data class PackageHoldRequest(
    val check_in: LocalDate,
    val check_out: LocalDate,
    val rooms: Int,
    val ticket_quantity: Int,
)

data class PackageHoldData(
    val package_order_id: String,
    val status: String,
    val components: PackageHoldComponents,
    val expires_at: String,
)

data class PackageHoldComponents(
    val booking_id: String,
    val ticket_order_id: String,
)

data class PackageConfirmRequest(
    val package_order_id: String,
    val payment_token: String,
)

data class PackageConfirmData(
    val package_order_id: String,
    val status: String,
)

data class PackageOrderSummary(
    val package_order_id: String,
    val package_id: Long,
    val user_id: Long,
    val status: String,
    val booking_id: String?,
    val ticket_order_id: String?,
    val expires_at: String?,
    val created_at: String?,
    val updated_at: String?,
)

data class PackageOrderListData(
    val items: List<PackageOrderSummary>,
)

data class PackageSummaryRow(
    val packageId: Long,
    val name: String,
    val status: String,
    val currency: String,
    val amountTotal: Long,
    val imageUrl: String?,
) {
    fun toSummary(): PackageSummary = PackageSummary(
        package_id = packageId,
        name = name,
        status = status,
        price = PackagePrice(currency = currency, amount_total = amountTotal),
        image_url = imageUrl,
    )
}

data class PackageOrderSummaryRow(
    val packageOrderId: String,
    val packageId: Long,
    val userId: Long,
    val status: String,
    val bookingId: String?,
    val ticketOrderId: String?,
    val expiresAt: java.sql.Timestamp?,
    val createdAt: java.sql.Timestamp?,
    val updatedAt: java.sql.Timestamp?,
) {
    fun toSummary(): PackageOrderSummary = PackageOrderSummary(
        package_order_id = packageOrderId,
        package_id = packageId,
        user_id = userId,
        status = status,
        booking_id = bookingId,
        ticket_order_id = ticketOrderId,
        expires_at = expiresAt?.toInstant()?.toString(),
        created_at = createdAt?.toInstant()?.toString(),
        updated_at = updatedAt?.toInstant()?.toString(),
    )
}

data class PackageComponentRow(
    val type: String,
    val roomTypeId: Long?,
    val eventId: Long?,
    val nights: Int?,
    val rooms: Int?,
    val quantity: Int?,
) {
    fun toComponent(): PackageComponent = PackageComponent(
        type = type,
        room_type_id = roomTypeId,
        event_id = eventId,
        nights = nights,
        rooms = rooms,
        quantity = quantity,
    )
}

data class PackageBase(
    val id: Long,
    val name: String,
    val status: String,
    val currency: String,
    val amount: Long,
    val imageUrl: String?,
)

data class PackageOrderRow(
    val id: Long,
    val status: String,
    val bookingId: Long,
    val ticketOrderId: Long,
    val expiresAt: Instant?,
)

data class PackageProductInsertCommand(
    val name: String,
    val status: String,
    val currency: String,
    val amountTotal: Long,
    val imageUrl: String?,
    var id: Long? = null,
)

data class PackageOrderInsertCommand(
    val packageId: Long,
    val userId: Long,
    var id: Long? = null,
)

@Mapper
interface PackageMapper {
    @Insert(
        """
        INSERT INTO package_product(name, status, currency, amount_total, image_url)
        VALUES (#{name}, #{status}, #{currency}, #{amountTotal}, #{imageUrl})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertPackageProduct(command: PackageProductInsertCommand): Int

    @Insert(
        """
        INSERT INTO package_product_component(
            package_id, component_type, room_type_id, ticket_event_id, nights, rooms, quantity
        )
        VALUES (#{packageId}, #{componentType}, #{roomTypeId}, #{ticketEventId}, #{nights}, #{rooms}, #{quantity})
        """,
    )
    fun insertPackageComponent(
        @Param("packageId") packageId: Long,
        @Param("componentType") componentType: String,
        @Param("roomTypeId") roomTypeId: Long?,
        @Param("ticketEventId") ticketEventId: Long?,
        @Param("nights") nights: Int?,
        @Param("rooms") rooms: Int?,
        @Param("quantity") quantity: Int?,
    ): Int

    @Select(
        """
        SELECT id,
               name,
               status,
               currency,
               amount_total AS amount,
               image_url AS imageUrl
        FROM package_product
        WHERE id = #{packageId}
        LIMIT 1
        """,
    )
    fun findPackage(@Param("packageId") packageId: Long): PackageBase?

    @Select(
        """
        SELECT id AS packageId,
               name,
               status,
               currency,
               amount_total AS amountTotal,
               image_url AS imageUrl
        FROM package_product
        WHERE status='ACTIVE'
        ORDER BY id DESC
        """,
    )
    fun listActivePackages(): List<PackageSummaryRow>

    @Select(
        """
        SELECT CONCAT('pkg_', id) AS packageOrderId,
               package_id AS packageId,
               user_id AS userId,
               status,
               CASE WHEN booking_id IS NULL THEN NULL ELSE CONCAT('bkg_', booking_id) END AS bookingId,
               CASE WHEN ticket_order_id IS NULL THEN NULL ELSE CONCAT('tord_', ticket_order_id) END AS ticketOrderId,
               expires_at AS expiresAt,
               created_at AS createdAt,
               updated_at AS updatedAt
        FROM package_order
        ORDER BY id DESC
        LIMIT #{limit}
        """,
    )
    fun listOrders(@Param("limit") limit: Int): List<PackageOrderSummaryRow>

    @Select(
        """
        SELECT CONCAT('pkg_', id) AS packageOrderId,
               package_id AS packageId,
               user_id AS userId,
               status,
               CASE WHEN booking_id IS NULL THEN NULL ELSE CONCAT('bkg_', booking_id) END AS bookingId,
               CASE WHEN ticket_order_id IS NULL THEN NULL ELSE CONCAT('tord_', ticket_order_id) END AS ticketOrderId,
               expires_at AS expiresAt,
               created_at AS createdAt,
               updated_at AS updatedAt
        FROM package_order
        WHERE status = #{status}
        ORDER BY id DESC
        LIMIT #{limit}
        """,
    )
    fun listOrdersByStatus(
        @Param("status") status: String,
        @Param("limit") limit: Int,
    ): List<PackageOrderSummaryRow>

    @Update(
        """
        UPDATE package_order
        SET status='HOLD',
            booking_id=#{bookingId},
            ticket_order_id=#{ticketOrderId},
            expires_at=#{expiresAt},
            updated_at=NOW(3)
        WHERE id=#{packageOrderId}
        """,
    )
    fun markPackageOrderHeld(
        @Param("packageOrderId") packageOrderId: Long,
        @Param("bookingId") bookingId: Long,
        @Param("ticketOrderId") ticketOrderId: Long,
        @Param("expiresAt") expiresAt: java.sql.Timestamp,
    ): Int

    @Select(
        """
        SELECT id,
               status,
               booking_id AS bookingId,
               ticket_order_id AS ticketOrderId,
               expires_at AS expiresAt
        FROM package_order
        WHERE id = #{packageOrderId} AND package_id = #{packageId} AND user_id = #{userId}
        FOR UPDATE
        """,
    )
    fun findPackageOrderForUpdate(
        @Param("packageOrderId") packageOrderId: Long,
        @Param("packageId") packageId: Long,
        @Param("userId") userId: Long,
    ): PackageOrderRow?

    @Insert(
        """
        INSERT INTO package_order(package_id, user_id, status)
        VALUES (#{packageId}, #{userId}, 'HOLDING')
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertPackageOrder(command: PackageOrderInsertCommand): Int

    @Update(
        """
        UPDATE package_order
        SET status=#{status}, updated_at=NOW(3)
        WHERE id=#{packageOrderId}
        """,
    )
    fun updatePackageOrderStatus(
        @Param("packageOrderId") packageOrderId: Long,
        @Param("status") status: String,
    ): Int

    @Select(
        """
        SELECT component_type AS type,
               room_type_id AS roomTypeId,
               ticket_event_id AS eventId,
               nights,
               rooms,
               quantity
        FROM package_product_component
        WHERE package_id = #{packageId}
        ORDER BY id
        """,
    )
    fun listComponents(@Param("packageId") packageId: Long): List<PackageComponentRow>
}
