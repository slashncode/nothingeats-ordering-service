package io.github.slashncode.nothingeats.ordering.domain

data class CartLine(
    val dishId: String,
    val quantity: Int,
)

/**
 * A customer's cart: dishes of one restaurant, like every delivery app. Quantities, not prices:
 * prices are always taken from the menu when the cart is shown or ordered.
 */
data class Cart(
    val userId: String,
    val restaurantId: String?,
    val lines: List<CartLine>,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Sets [dish]'s quantity (0 removes it). A dish from another restaurant needs [replace],
     * which starts a new cart.
     */
    fun set(
        dish: MenuDish,
        quantity: Int,
        replace: Boolean,
    ): Cart {
        if (quantity !in 0..MAX_QUANTITY) throw InvalidOrder("quantity", "must be between 0 and $MAX_QUANTITY")
        val sameRestaurant = restaurantId == null || restaurantId == dish.restaurantId || isEmpty
        if (!sameRestaurant && !replace) throw OtherRestaurant(restaurantId, dish.restaurantId)
        val base = if (sameRestaurant) lines else emptyList()
        val others = base.filter { it.dishId != dish.id }
        val next = if (quantity == 0) others else others + CartLine(dish.id, quantity)
        if (next.size > MAX_LINES) throw InvalidOrder("dishId", "a cart holds at most $MAX_LINES different dishes")
        return Cart(userId, if (next.isEmpty()) null else dish.restaurantId, next)
    }

    companion object {
        const val MAX_QUANTITY = 20
        const val MAX_LINES = 30

        fun empty(userId: String) = Cart(userId, null, emptyList())
    }
}

data class PricedLine(
    val dish: MenuDish,
    val quantity: Int,
) {
    val totalCents: Long get() = dish.priceCents * quantity
}

enum class CartProblem {
    /** A dish was taken off the menu since it went into the cart. */
    DISH_UNAVAILABLE,

    /** Below the restaurant's minimum order. */
    MINIMUM_NOT_REACHED,

    RESTAURANT_CLOSED,
}

/** The cart with today's prices from the menu, and what keeps it from being ordered. */
data class PricedCart(
    val restaurant: MenuRestaurant?,
    val lines: List<PricedLine>,
    val problems: Set<CartProblem>,
) {
    val subtotalCents: Long get() = lines.sumOf { it.totalCents }
    val deliveryFeeCents: Long get() = if (lines.isEmpty()) 0 else restaurant?.deliveryFeeCents ?: 0
    val totalCents: Long get() = subtotalCents + deliveryFeeCents
    val calories: Int get() = lines.sumOf { it.dish.calories * it.quantity }
    val orderable: Boolean get() = restaurant != null && lines.isNotEmpty() && problems.isEmpty()

    companion object {
        fun of(
            cart: Cart,
            restaurant: MenuRestaurant?,
            dishes: Map<String, MenuDish>,
            openNow: Boolean,
        ): PricedCart {
            // Dishes the menu no longer knows are dropped silently; dishes marked unavailable stay visible.
            val lines = cart.lines.mapNotNull { line -> dishes[line.dishId]?.let { PricedLine(it, line.quantity) } }
            val problems =
                buildSet {
                    if (lines.any { !it.dish.available }) add(CartProblem.DISH_UNAVAILABLE)
                    if (restaurant != null && lines.isNotEmpty()) {
                        if (lines.sumOf { it.totalCents } < restaurant.minimumOrderCents) add(CartProblem.MINIMUM_NOT_REACHED)
                        if (!openNow) add(CartProblem.RESTAURANT_CLOSED)
                    }
                }
            return PricedCart(restaurant, lines, problems)
        }
    }
}

class OtherRestaurant(
    val current: String,
    val requested: String,
) : Exception("The cart holds dishes of another restaurant")
