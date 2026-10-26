package io.github.slashncode.nothingeats.ordering.domain

import java.time.Instant

/** The order's progress; after PLACED, the delivery service says what happens (`delivery.delivery.v1`). */
enum class OrderStatus { PLACED, ACCEPTED, COOKING, PICKED_UP, EN_ROUTE, DELIVERED }

data class DropOff(
    val location: Location,
    val label: String?,
) {
    init {
        if ((label?.length ?: 0) > MAX_LABEL) throw InvalidOrder("dropOff.label", "must be at most $MAX_LABEL characters")
    }

    companion object {
        const val MAX_LABEL = 80
    }
}

/** What was ordered, frozen at checkout: later menu changes never touch a placed order. */
data class OrderLine(
    val dishId: String,
    val name: Text,
    val unitPriceCents: Long,
    val quantity: Int,
    val calories: Int,
    /** The menu price when a flash deal made the dish cheaper; null otherwise. */
    val regularUnitPriceCents: Long? = null,
) {
    val totalCents: Long get() = unitPriceCents * quantity
}

data class OrderedRestaurant(
    val id: String,
    val slug: String,
    val name: String,
    val location: Location,
    val preparationMinutes: Int,
)

enum class QuoteSource {
    /** From the delivery service, with the current load. */
    DELIVERY,

    /** The restaurant's advertised time, because the delivery service did not answer in time. */
    ESTIMATE,
}

data class Order(
    val id: String,
    val userId: String,
    val language: Language,
    val restaurant: OrderedRestaurant,
    val lines: List<OrderLine>,
    val deliveryFeeCents: Long,
    val dropOff: DropOff,
    val quotedSeconds: Int,
    val quoteSource: QuoteSource,
    val placedAt: Instant,
    val status: OrderStatus,
    val statusChangedAt: Instant,
    val estimatedArrivalAt: Instant,
    /** What the coupon took off. */
    val discountCents: Long = 0,
    val couponCode: String? = null,
    /** Whether the customer had agreed to analytics at checkout; travels with the order's events. */
    val analytics: Boolean = false,
) {
    val subtotalCents: Long get() = lines.sumOf { it.totalCents }
    val totalCents: Long get() = subtotalCents + deliveryFeeCents - discountCents
    val calories: Int get() = lines.sumOf { it.calories * it.quantity }

    /** Status updates may arrive late or twice; only a step forward counts. */
    fun follow(
        next: OrderStatus,
        at: Instant,
        estimatedArrival: Instant?,
    ): Order? =
        if (next.ordinal <= status.ordinal) {
            null
        } else {
            copy(status = next, statusChangedAt = at, estimatedArrivalAt = estimatedArrival ?: estimatedArrivalAt)
        }
}

class InvalidOrder(
    val field: String,
    reason: String,
) : Exception(reason)
