package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.CartCoupon
import io.github.slashncode.nothingeats.ordering.domain.PricedCart
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

internal fun PricedCart.couponInput(): CouponCartInput? {
    val restaurant = restaurant ?: return null
    if (lines.isEmpty()) return null
    return CouponCartInput(restaurant.id, subtotalCents, deliveryFeeCents, lines.map { it.dish.unitPriceCents })
}

internal fun couponUnavailable() =
    AppError.Unavailable(ErrorCode("COUPON_UNAVAILABLE"), "Coupons cannot be checked right now; remove the coupon or try again")

/**
 * The cart with the menu's current prices (flash deals included) and the coupon as loyalty prices
 * it right now; opening hours in Berlin time. A coupon that does not apply stays in the cart, with
 * the reason, and keeps it from being ordered until the customer removes it.
 */
class ViewCart(
    private val carts: Carts,
    private val menu: Menu,
    private val coupons: LoyaltyCoupons,
    private val clock: Clock = Clock.system(BERLIN),
) {
    suspend operator fun invoke(
        userId: String,
        accessToken: String,
    ): PricedCart = price(carts.find(userId) ?: Cart.empty(userId), accessToken)

    internal suspend fun price(
        cart: Cart,
        accessToken: String,
    ): PricedCart {
        val restaurant = cart.restaurantId?.let { menu.restaurant(it) }
        val dishes = menu.dishes(cart.lines.map { it.dishId }, clock.instant())
        val openNow = restaurant?.isOpenAt(LocalTime.now(clock.withZone(BERLIN))) ?: false
        val priced = PricedCart.of(cart, restaurant, dishes, openNow)
        val code = cart.couponCode ?: return priced
        val input = priced.couponInput() ?: return priced
        val coupon =
            try {
                coupons
                    .price(
                        code,
                        input,
                        accessToken,
                    )?.let { CartCoupon(it.code, it.kind, it.value, it.maxDiscountCents, it.discountCents, null) }
                    ?: CartCoupon.refused(code, "COUPON_UNAVAILABLE")
            } catch (e: AppError) {
                CartCoupon.refused(code, e.code.value)
            }
        return priced.with(coupon)
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
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        userId: String,
        dishId: String,
        quantity: Int,
        replaceCart: Boolean,
        accessToken: String,
    ): PricedCart {
        val dish =
            menu.dishes(listOf(dishId), clock.instant())[dishId]
                ?: throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "There is no such dish")
        if (quantity > 0 && !dish.available) throw AppError.Conflict(ErrorCode("DISH_UNAVAILABLE"), "This dish is not available right now")
        val before = carts.find(userId) ?: Cart.empty(userId)
        val after = validated { before.set(dish, quantity, replaceCart) }
        if (after.isEmpty) carts.delete(userId) else carts.save(after)
        val added =
            quantity - (before.lines.firstOrNull { it.dishId == dishId && before.restaurantId == after.restaurantId }?.quantity ?: 0)
        if (added > 0) {
            analytics.capture(
                "dish_added_to_cart",
                mapOf(
                    "dish_id" to dishId,
                    "restaurant_id" to dish.restaurantId,
                    "quantity" to added,
                    "price_cents" to dish.unitPriceCents,
                    "on_deal" to dish.onDeal,
                ),
            )
        }
        return view.price(after, accessToken)
    }
}

/**
 * `PUT /cart/coupon`: the coupon goes into the cart only when it applies right now, so the customer
 * learns at once why not. Later changes to the cart are priced again on every view.
 */
class ApplyCoupon(
    private val carts: Carts,
    private val view: ViewCart,
    private val analytics: ProductAnalytics,
) {
    suspend operator fun invoke(
        userId: String,
        code: String,
        accessToken: String,
    ): PricedCart {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty() || normalized.length > MAX_CODE) throw invalid("code", "must be 1–$MAX_CODE characters")
        val cart = carts.find(userId)?.takeIf { !it.isEmpty } ?: throw AppError.Conflict(ErrorCode("CART_EMPTY"), "The cart is empty")
        val priced = view.price(cart.copy(couponCode = normalized), accessToken)
        val coupon = checkNotNull(priced.coupon)
        when (val problem = coupon.problem) {
            null -> Unit
            "COUPON_UNAVAILABLE" -> throw couponUnavailable()
            else -> throw AppError.Conflict(ErrorCode(problem), "This coupon does not apply to the cart")
        }
        carts.save(cart.copy(couponCode = normalized))
        analytics.capture("coupon_applied", mapOf("kind" to coupon.kind?.lowercase(), "discount_cents" to coupon.discountCents))
        return priced
    }

    private companion object {
        const val MAX_CODE = 32
    }
}

class RemoveCoupon(
    private val carts: Carts,
    private val view: ViewCart,
) {
    suspend operator fun invoke(
        userId: String,
        accessToken: String,
    ): PricedCart {
        val cart = carts.find(userId) ?: return view(userId, accessToken)
        val without = cart.copy(couponCode = null)
        carts.save(without)
        return view.price(without, accessToken)
    }
}

class ClearCart(
    private val carts: Carts,
) {
    suspend operator fun invoke(userId: String) = carts.delete(userId)
}
