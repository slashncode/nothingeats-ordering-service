package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import java.time.Instant

/**
 * Ordering's projection of the catalog (record 024: snapshots on compacted topics). [version] is
 * the record's offset: an older snapshot never overwrites a newer one.
 */
interface Menu {
    suspend fun upsertRestaurant(
        restaurant: MenuRestaurant,
        version: Long,
    )

    suspend fun upsertDish(
        dish: MenuDish,
        version: Long,
    )

    suspend fun restaurant(id: String): MenuRestaurant?

    suspend fun dishes(ids: Collection<String>): Map<String, MenuDish>
}

interface Carts {
    suspend fun find(userId: String): Cart?

    suspend fun save(cart: Cart)

    suspend fun delete(userId: String)
}

/** Where a page of order history continues. */
data class OrderCursor(
    val placedAt: Instant,
    val id: String,
)

interface Orders {
    suspend fun insert(order: Order)

    suspend fun find(id: String): Order?

    /** Newest first, after [cursor]. */
    suspend fun page(
        userId: String,
        cursor: OrderCursor?,
        limit: Int,
    ): List<Order>

    suspend fun all(userId: String): List<Order>

    suspend fun updateStatus(order: Order)

    suspend fun deleteByUser(userId: String): Int
}

data class StoredRequest(
    val requestHash: String,
    val orderId: String,
)

/** Idempotency keys of `POST /orders`, per user. */
interface OrderRequests {
    suspend fun find(
        userId: String,
        key: String,
    ): StoredRequest?

    /** @throws DuplicateRequest when a concurrent request with the same key won. */
    suspend fun insert(
        userId: String,
        key: String,
        request: StoredRequest,
    )
}

class DuplicateRequest : Exception("A request with this idempotency key exists already")

/** `ordering.order.v1` through the outbox: call inside the transaction that stores the order. */
interface OrderEvents {
    suspend fun placed(order: Order)
}

data class DeliveryQuote(
    val etaSeconds: Int,
    val simulatedMinutes: Int,
    val distanceMeters: Int,
    val courierShortage: Boolean,
)

/** The delivery service (gRPC), asked on the customer's behalf. */
interface DeliveryQuotes {
    /**
     * Null when the delivery service did not answer in time: checkout goes on with an estimate.
     * @throws io.github.slashncode.nothingeats.platform.core.error.AppError.Invalid when the drop-off is out of range.
     */
    suspend fun quote(
        pickup: Location,
        dropOff: Location,
        preparationMinutes: Int,
        accessToken: String,
    ): DeliveryQuote?
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
