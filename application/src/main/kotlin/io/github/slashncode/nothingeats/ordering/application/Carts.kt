package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.PricedCart
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

/** The cart with the menu's current prices; opening hours in Berlin time. */
class ViewCart(
    private val carts: Carts,
    private val menu: Menu,
    private val clock: Clock = Clock.system(BERLIN),
) {
    suspend operator fun invoke(userId: String): PricedCart = price(carts.find(userId) ?: Cart.empty(userId))

    internal suspend fun price(cart: Cart): PricedCart {
        val restaurant = cart.restaurantId?.let { menu.restaurant(it) }
        val dishes = menu.dishes(cart.lines.map { it.dishId })
        val openNow = restaurant?.isOpenAt(LocalTime.now(clock.withZone(BERLIN))) ?: false
        return PricedCart.of(cart, restaurant, dishes, openNow)
    }

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/** Sets a dish's quantity; `dish_added_to_cart` for PostHog when the quantity goes up. */
class ChangeCart(
    private val carts: Carts,
    private val menu: Menu,
    private val view: ViewCart,
    private val analytics: ProductAnalytics,
) {
    suspend operator fun invoke(
        userId: String,
        dishId: String,
        quantity: Int,
        replaceCart: Boolean,
    ): PricedCart {
        val dish = menu.dishes(listOf(dishId))[dishId] ?: throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "There is no such dish")
        if (quantity > 0 && !dish.available) throw AppError.Conflict(ErrorCode("DISH_UNAVAILABLE"), "This dish is not available right now")
        val before = carts.find(userId) ?: Cart.empty(userId)
        val after = validated { before.set(dish, quantity, replaceCart) }
        if (after.isEmpty) carts.delete(userId) else carts.save(after)
        val added =
            quantity - (before.lines.firstOrNull { it.dishId == dishId && before.restaurantId == after.restaurantId }?.quantity ?: 0)
        if (added > 0) {
            analytics.capture(
                "dish_added_to_cart",
                mapOf("dish_id" to dishId, "restaurant_id" to dish.restaurantId, "quantity" to added, "price_cents" to dish.priceCents),
            )
        }
        return view.price(after)
    }
}

class ClearCart(
    private val carts: Carts,
) {
    suspend operator fun invoke(userId: String) = carts.delete(userId)
}
