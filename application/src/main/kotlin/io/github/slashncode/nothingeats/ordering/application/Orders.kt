package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import java.time.Instant
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
        val page = orders.page(userId, cursor?.let(::decode), limit + 1)
        val more = page.size > limit
        val shown = page.take(limit)
        return OrderPage(shown, if (more) encode(shown.last()) else null)
    }

    private fun encode(order: Order) = Base64.getUrlEncoder().withoutPadding().encodeToString("${order.placedAt}|${order.id}".toByteArray())

    private fun decode(cursor: String): OrderCursor =
        try {
            val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split('|', limit = 2)
            OrderCursor(Instant.parse(at), id)
        } catch (e: Exception) {
            throw invalid("cursor", "is not a cursor from this API")
        }

    private companion object {
        const val MAX_LIMIT = 50
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

/** `delivery.delivery.v1`: the order follows its delivery, one step forward at a time. */
class FollowDelivery(
    private val orders: Orders,
) {
    suspend operator fun invoke(
        orderId: String,
        status: OrderStatus,
        at: Instant,
        estimatedArrivalAt: Instant?,
    ) {
        val order = orders.find(orderId) ?: return
        order.follow(status, at, estimatedArrivalAt)?.let { orders.updateStatus(it) }
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
