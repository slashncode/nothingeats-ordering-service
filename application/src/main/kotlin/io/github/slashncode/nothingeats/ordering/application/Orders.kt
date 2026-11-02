package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.MenuDeal
import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

data class OrderPage(
    val orders: List<Order>,
    val nextCursor: String?,
)

/** Order history, newest first, with an opaque cursor. */
class ListOrders(
    private val orders: Orders,
) {
    suspend operator fun invoke(
        userId: String,
        cursor: String?,
        limit: Int,
    ): OrderPage {
        if (limit !in 1..MAX_LIMIT) throw invalid("limit", "must be between 1 and $MAX_LIMIT")
        return pageOf(limit) { orders.page(userId, cursor?.let(::decodeCursor), it) }
    }
}

internal const val MAX_LIMIT = 50

/** One more than asked for tells whether there is a next page. */
private suspend fun pageOf(
    limit: Int,
    load: suspend (Int) -> List<Order>,
): OrderPage {
    val page = load(limit + 1)
    val shown = page.take(limit)
    return OrderPage(shown, if (page.size > limit) encodeCursor(shown.last()) else null)
}

private fun encodeCursor(order: Order) =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${order.placedAt}|${order.id}".toByteArray())

private fun decodeCursor(cursor: String): OrderCursor =
    try {
        val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split('|', limit = 2)
        OrderCursor(Instant.parse(at), id)
    } catch (e: Exception) {
        throw invalid("cursor", "is not a cursor from this API")
    }

/** Today's orders in Hamburg, counted by where they are now. */
data class OrdersToday(
    val day: LocalDate,
    val byStatus: Map<OrderStatus, Int>,
) {
    val placed: Int get() = byStatus.values.sum()
}

/**
 * The backoffice's view of every customer's orders. Only for role `admin` (the routes check it);
 * read only: orders change through their delivery, never by hand.
 */
class AdminOrders(
    private val orders: Orders,
    private val clock: Clock,
) {
    suspend fun search(
        status: String?,
        userId: String?,
        restaurantSlug: String?,
        cursor: String?,
        limit: Int,
    ): OrderPage {
        if (limit !in 1..MAX_LIMIT) throw invalid("limit", "must be between 1 and $MAX_LIMIT")
        val filter =
            OrderFilter(
                status =
                    status?.takeIf { it.isNotBlank() }?.let { raw ->
                        OrderStatus.entries.firstOrNull { it.name == raw.uppercase() } ?: throw invalid("status", "is not an order status")
                    },
                userId = userId?.takeIf { it.isNotBlank() },
                restaurantSlug = restaurantSlug?.takeIf { it.isNotBlank() },
            )
        return pageOf(limit) { orders.search(filter, cursor?.let(::decodeCursor), it) }
    }

    suspend fun find(orderId: String): Order = orders.find(orderId) ?: throw orderNotFound()

    suspend fun today(): OrdersToday {
        val day = LocalDate.now(clock.withZone(HAMBURG))
        val counts = orders.countByStatus(day.atStartOfDay(HAMBURG).toInstant())
        return OrdersToday(day, OrderStatus.entries.associateWith { counts[it] ?: 0 })
    }

    private companion object {
        val HAMBURG: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

class GetOrder(
    private val orders: Orders,
) {
    /** Someone else's order looks exactly like no order. */
    suspend operator fun invoke(
        userId: String,
        orderId: String,
    ): Order = orders.find(orderId)?.takeIf { it.userId == userId } ?: throw orderNotFound()
}

/**
 * `delivery.delivery.v1`: the order follows its delivery, one step forward at a time. Arriving is
 * ordering's own news too (`delivered` on `ordering.order.v1`, after `placed` under the same key),
 * so loyalty and others learn it from the order's topic.
 */
class FollowDelivery(
    private val orders: Orders,
    private val events: OrderEvents,
    private val transactions: Transactions,
) {
    suspend operator fun invoke(
        orderId: String,
        status: OrderStatus,
        at: Instant,
        estimatedArrivalAt: Instant?,
    ) {
        transactions.run {
            val order = orders.find(orderId) ?: return@run
            val next = order.follow(status, at, estimatedArrivalAt) ?: return@run
            orders.updateStatus(next)
            if (next.status == OrderStatus.DELIVERED) events.delivered(next)
        }
    }
}

/** Keeps the menu projection current from the catalog's snapshots. */
class ProjectMenu(
    private val menu: Menu,
) {
    suspend fun restaurant(
        restaurant: MenuRestaurant,
        version: Long,
    ) = menu.upsertRestaurant(restaurant, version)

    suspend fun dish(
        dish: MenuDish,
        version: Long,
    ) = menu.upsertDish(dish, version)

    suspend fun deal(deal: MenuDeal) = menu.upsertDeal(deal)
}

/** `user.deleted`: orders (with drop-off locations) and the cart go. */
class ForgetUser(
    private val orders: Orders,
    private val carts: Carts,
    private val transactions: Transactions,
) {
    suspend operator fun invoke(userId: String): Int =
        transactions.run {
            carts.delete(userId)
            orders.deleteByUser(userId)
        }
}

/** GDPR art. 15/20: everything ordering holds about the user. */
class ExportOrders(
    private val orders: Orders,
) {
    suspend operator fun invoke(userId: String): List<Order> = orders.all(userId)
}
