package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.CartProblem
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.OrderLine
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.OrderedRestaurant
import io.github.slashncode.nothingeats.ordering.domain.PricedCart
import io.github.slashncode.nothingeats.ordering.domain.QuoteSource
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlin.math.roundToInt

/** How long a delivery takes when the delivery service cannot be asked (same time scale as the simulation). */
data class CheckoutSettings(
    val fallbackTimeScale: Double = 0.1,
)

data class CheckoutQuote(
    val etaSeconds: Int,
    val simulatedMinutes: Int,
    val distanceMeters: Int?,
    val courierShortage: Boolean,
    val source: QuoteSource,
)

/** Asks the delivery service; if it does not answer in time, the restaurant's advertised time stands in. */
internal suspend fun DeliveryQuotes.quoteOrEstimate(
    restaurant: MenuRestaurant,
    dropOff: DropOff,
    accessToken: String,
    settings: CheckoutSettings,
): CheckoutQuote {
    val quote = quote(restaurant.location, dropOff.location, restaurant.preparationMinutes, accessToken)
    return if (quote != null) {
        CheckoutQuote(quote.etaSeconds, quote.simulatedMinutes, quote.distanceMeters, quote.courierShortage, QuoteSource.DELIVERY)
    } else {
        CheckoutQuote(
            (restaurant.deliveryMinutes * SECONDS_PER_MINUTE * settings.fallbackTimeScale).roundToInt(),
            restaurant.deliveryMinutes,
            null,
            false,
            QuoteSource.ESTIMATE,
        )
    }
}

private const val SECONDS_PER_MINUTE = 60

private fun PricedCart.requireOrderable(): MenuRestaurant {
    val restaurant = restaurant
    if (restaurant == null || lines.isEmpty()) throw AppError.Conflict(ErrorCode("CART_EMPTY"), "The cart is empty")
    if (problems.isNotEmpty()) {
        val problem = problems.minBy { it.ordinal }
        throw AppError.Conflict(ErrorCode("CART_${problem.name}"), problem.message())
    }
    return restaurant
}

private fun CartProblem.message() =
    when (this) {
        CartProblem.DISH_UNAVAILABLE -> "A dish in the cart is no longer available"
        CartProblem.MINIMUM_NOT_REACHED -> "The cart is below the restaurant's minimum order"
        CartProblem.RESTAURANT_CLOSED -> "The restaurant is closed right now"
    }

/** `POST /quotes`: the arrival time for the cart and a drop-off, before the customer commits. */
class QuoteCheckout(
    private val view: ViewCart,
    private val quotes: DeliveryQuotes,
    private val settings: CheckoutSettings,
) {
    suspend operator fun invoke(
        userId: String,
        dropOff: DropOff,
        accessToken: String,
    ): CheckoutQuote {
        val restaurant = view(userId).requireOrderable()
        return quotes.quoteOrEstimate(restaurant, dropOff, accessToken, settings)
    }
}

data class PlacedOrder(
    val order: Order,
    /** The same idempotency key came again: this is the order of the first request. */
    val replayed: Boolean,
)

/**
 * `POST /orders` ("pay with willpower"): the cart becomes an order with frozen names and prices.
 * The quote is asked before the transaction (no remote call inside one); order, idempotency key,
 * outbox event and the emptied cart are one transaction. The same key with the same request
 * returns the first order; with a different request it is refused.
 */
class PlaceOrder(
    private val view: ViewCart,
    private val carts: Carts,
    private val orders: Orders,
    private val requests: OrderRequests,
    private val events: OrderEvents,
    private val quotes: DeliveryQuotes,
    private val transactions: Transactions,
    private val analytics: ProductAnalytics,
    private val settings: CheckoutSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        userId: String,
        language: Language,
        accessToken: String,
        idempotencyKey: String?,
        dropOff: DropOff,
    ): PlacedOrder {
        val key = idempotencyKey?.takeIf { KEY.matches(it) } ?: throw keyRequired()
        val hash = hash(dropOff)
        replay(userId, key, hash)?.let { return it }

        val cart = view(userId)
        val restaurant =
            try {
                cart.requireOrderable()
            } catch (e: AppError.Conflict) {
                // A concurrent request with this key may have just ordered and emptied the cart.
                replay(userId, key, hash)?.let { return it }
                throw e
            }
        val quote = quotes.quoteOrEstimate(restaurant, dropOff, accessToken, settings)
        val now = clock.instant()
        val order =
            Order(
                id = UUID.randomUUID().toString(),
                userId = userId,
                language = language,
                restaurant =
                    OrderedRestaurant(
                        restaurant.id,
                        restaurant.slug,
                        restaurant.name,
                        restaurant.location,
                        restaurant.preparationMinutes,
                    ),
                lines = cart.lines.map { OrderLine(it.dish.id, it.dish.name, it.dish.priceCents, it.quantity, it.dish.calories) },
                deliveryFeeCents = cart.deliveryFeeCents,
                dropOff = dropOff,
                quotedSeconds = quote.etaSeconds,
                quoteSource = quote.source,
                placedAt = now,
                status = OrderStatus.PLACED,
                statusChangedAt = now,
                estimatedArrivalAt = now.plusSeconds(quote.etaSeconds.toLong()),
            )
        try {
            transactions.run {
                orders.insert(order)
                requests.insert(userId, key, StoredRequest(hash, order.id))
                events.placed(order)
                carts.delete(userId)
            }
        } catch (e: DuplicateRequest) {
            // A concurrent request with this key won the race; answer like it did.
            return replay(userId, key, hash) ?: throw e
        }
        analytics.capture(
            "order_placed",
            mapOf(
                "order_id" to order.id,
                "restaurant_slug" to restaurant.slug,
                "items" to order.lines.sumOf { it.quantity },
                "subtotal_cents" to order.subtotalCents,
                // Nothing is charged: what the customer saved by ordering nothing.
                "money_not_spent_cents" to order.totalCents,
                "calories_avoided" to order.calories,
                "eta_seconds" to quote.etaSeconds,
                "quote_source" to quote.source.name.lowercase(),
                "courier_shortage" to quote.courierShortage,
            ),
        )
        return PlacedOrder(order, replayed = false)
    }

    private suspend fun replay(
        userId: String,
        key: String,
        hash: String,
    ): PlacedOrder? {
        val stored = requests.find(userId, key) ?: return null
        if (stored.requestHash != hash) {
            throw AppError.Invalid(ErrorCode("IDEMPOTENCY_KEY_REUSED"), "This idempotency key was used for a different request")
        }
        return PlacedOrder(orders.find(stored.orderId) ?: throw orderNotFound(), replayed = true)
    }

    private fun hash(dropOff: DropOff): String {
        val request = "${dropOff.location.latitude}|${dropOff.location.longitude}|${dropOff.label.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(request.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun keyRequired() =
        AppError.Invalid(
            ErrorCode("IDEMPOTENCY_KEY_REQUIRED"),
            "Send an Idempotency-Key header (1–64 letters, digits, - or _), the same one when retrying",
        )

    private companion object {
        val KEY = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
