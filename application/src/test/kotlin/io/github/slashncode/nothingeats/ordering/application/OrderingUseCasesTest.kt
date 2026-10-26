package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.CartProblem
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDeal
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.QuoteSource
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withContext
import java.time.Instant

// 12:00 UTC is 13:00 in Berlin: everyone is open.
private val lunch = Instant.parse("2026-10-26T12:00:00Z")
private val schanze = DropOff(Location(53.5636, 9.9654), "Schanze")

private class World(
    val clock: MovableClock = MovableClock(lunch),
) {
    val menu = InMemoryMenu()
    val carts = InMemoryCarts()
    val orders = InMemoryOrders()
    val requests = InMemoryRequests()
    val events = RecordingOrderEvents()
    val quotes = FakeQuotes()
    val coupons = FakeCoupons()
    val analytics = RecordingAnalytics()
    val view = ViewCart(carts, menu, coupons, clock)
    private val changeCart = ChangeCart(carts, menu, view, analytics, clock)
    private val placeOrder =
        PlaceOrder(view, carts, orders, requests, events, quotes, coupons, DirectTransactions, analytics, CheckoutSettings(), clock)
    val applyCoupon = ApplyCoupon(carts, view, analytics)
    val follow = FollowDelivery(orders, events, DirectTransactions)

    // Server-side events need the visitor's distinct id, which arrives with the request after consent.
    private val visitor = AnalyticsContext("visitor-1", "session-1")

    suspend fun change(
        userId: String,
        dishId: String,
        quantity: Int,
        replaceCart: Boolean,
    ) = withContext(visitor) { changeCart(userId, dishId, quantity, replaceCart, "t") }

    suspend fun place(
        userId: String,
        language: Language,
        token: String,
        key: String?,
        dropOff: DropOff,
    ) = withContext(visitor) { placeOrder(userId, language, token, key, dropOff) }

    suspend fun fill() =
        apply {
            menu.seeded()
            change("u-1", "d-1", 2, replaceCart = false)
        }
}

class OrderingUseCasesTest :
    FunSpec({
        test("the cart prices from the menu and keeps one restaurant unless told otherwise") {
            val world = World().fill()

            val cart = world.change("u-1", "d-2", 1, replaceCart = false)
            cart.subtotalCents shouldBe 2 * 1090 + 650
            cart.orderable shouldBe true
            shouldThrow<AppError.Conflict> { world.change("u-1", "d-3", 1, replaceCart = false) }.code.value shouldBe
                "CART_OTHER_RESTAURANT"
            world.change("u-1", "d-3", 1, replaceCart = true).restaurant?.slug shouldBe "ghost-gyros"
            shouldThrow<AppError.NotFound> { world.change("u-1", "nope", 1, replaceCart = false) }.code.value shouldBe "DISH_NOT_FOUND"
            world.analytics.events.map { it.name to it.properties["quantity"] } shouldBe
                listOf("dish_added_to_cart" to 2, "dish_added_to_cart" to 1, "dish_added_to_cart" to 1)
        }

        test("placing an order freezes the cart, asks delivery with the customer's token, and empties the cart") {
            val world = World().fill()

            val placed = world.place("u-1", Language.EN, "token-1", "key-1", schanze)

            placed.replayed shouldBe false
            val order = placed.order
            order.lines
                .single()
                .name.en shouldBe "Margherita of Nothing"
            order.totalCents shouldBe 2 * 1090 + 199
            order.quoteSource shouldBe QuoteSource.DELIVERY
            order.estimatedArrivalAt shouldBe lunch.plusSeconds(160)
            world.quotes.tokens shouldBe listOf("token-1")
            world.events.placed
                .single()
                .id shouldBe order.id
            world.carts.rows["u-1"] shouldBe null
            world.analytics.events.last().let {
                it.name shouldBe "order_placed"
                it.properties["money_not_spent_cents"] shouldBe 2379L
            }
        }

        test("the same idempotency key returns the first order; a different request with it is refused") {
            val world = World().fill()
            val first = world.place("u-1", Language.DE, "t", "key-1", schanze)

            val again = world.place("u-1", Language.DE, "t", "key-1", schanze)
            again.replayed shouldBe true
            again.order.id shouldBe first.order.id
            world.events.placed.size shouldBe 1

            shouldThrow<AppError.Invalid> { world.place("u-1", Language.DE, "t", "key-1", schanze.copy(label = "Büro")) }
                .code.value shouldBe "IDEMPOTENCY_KEY_REUSED"
            shouldThrow<AppError.Invalid> { world.place("u-1", Language.DE, "t", null, schanze) }.code.value shouldBe
                "IDEMPOTENCY_KEY_REQUIRED"
            shouldThrow<AppError.Invalid> { world.place("u-1", Language.DE, "t", "no spaces please", schanze) }.code.value shouldBe
                "IDEMPOTENCY_KEY_REQUIRED"
        }

        test("without an answer from delivery, checkout goes on with the restaurant's own time") {
            val world = World().fill()
            world.quotes.answer = null

            val order = world.place("u-1", Language.DE, "t", "key-1", schanze).order

            order.quoteSource shouldBe QuoteSource.ESTIMATE
            order.quotedSeconds shouldBe 30 * 60 / 10
        }

        test("empty, too small or closed carts are not orders") {
            val world = World()
            world.menu.seeded()
            shouldThrow<AppError.Conflict> { world.place("u-1", Language.DE, "t", "k1", schanze) }.code.value shouldBe "CART_EMPTY"

            world.change("u-1", "d-2", 1, replaceCart = false)
            shouldThrow<AppError.Conflict> { world.place("u-1", Language.DE, "t", "k2", schanze) }.code.value shouldBe
                "CART_MINIMUM_NOT_REACHED"

            world.change("u-1", "d-1", 2, replaceCart = false)
            // 23:30 UTC is 00:30 in Berlin.
            world.clock.now = Instant.parse("2026-10-26T23:30:00Z")
            shouldThrow<AppError.Conflict> { world.place("u-1", Language.DE, "t", "k3", schanze) }.code.value shouldBe
                "CART_RESTAURANT_CLOSED"
        }

        test("orders follow their delivery forward only, page newest first, and belong to their customer") {
            val world = World().fill()
            val first = world.place("u-1", Language.DE, "t", "k1", schanze).order
            world.clock.now = lunch.plusSeconds(60)
            world.change("u-1", "d-1", 2, replaceCart = false)
            val second = world.place("u-1", Language.DE, "t", "k2", schanze).order

            world.follow(first.id, OrderStatus.EN_ROUTE, lunch.plusSeconds(90), lunch.plusSeconds(200))
            world.follow(first.id, OrderStatus.COOKING, lunch.plusSeconds(91), null)
            world.orders.rows
                .getValue(first.id)
                .status shouldBe OrderStatus.EN_ROUTE
            world.orders.rows
                .getValue(first.id)
                .estimatedArrivalAt shouldBe lunch.plusSeconds(200)

            val page = ListOrders(world.orders)("u-1", null, 1)
            page.orders.single().id shouldBe second.id
            ListOrders(world.orders)("u-1", page.nextCursor, 1).orders.single().id shouldBe first.id
            shouldThrow<AppError.NotFound> { GetOrder(world.orders)("u-2", first.id) }.code.value shouldBe "ORDER_NOT_FOUND"

            ForgetUser(world.orders, world.carts, DirectTransactions)("u-1") shouldBe 2
            world.orders.rows shouldBe emptyMap()
        }

        test("a running flash deal prices the cart and is frozen into the order; after it ends the menu price is back") {
            val world = World()
            world.menu.seeded()
            world.menu.upsertDeal(MenuDeal("deal-1", "d-1", 760, 30, lunch.minusSeconds(60), lunch.plusSeconds(1800)))
            world.change("u-1", "d-1", 2, replaceCart = false)

            val cart = world.view("u-1", "t")
            cart.subtotalCents shouldBe 2 * 760
            cart.lines
                .single()
                .dish.onDeal shouldBe true
            world.analytics.events
                .single()
                .properties["price_cents"] shouldBe 760L

            val order = world.place("u-1", Language.DE, "t", "k1", schanze).order
            order.lines.single().unitPriceCents shouldBe 760
            order.lines.single().regularUnitPriceCents shouldBe 1090

            world.change("u-1", "d-1", 2, replaceCart = false)
            world.clock.now = lunch.plusSeconds(1800)
            world.view("u-1", "t").subtotalCents shouldBe 2 * 1090
        }

        test("a coupon goes into the cart only when it applies, is reserved under the checkout's key and lowers the total") {
            val world = World().fill()
            world.coupons.refusals["NE-USED-0000"] = AppError.Conflict(ErrorCode("COUPON_USED"), "used")

            shouldThrow<AppError.Conflict> { world.applyCoupon("u-1", "ne-used-0000", "t") }.code.value shouldBe "COUPON_USED"
            world.carts.rows
                .getValue("u-1")
                .couponCode shouldBe null

            val cart = world.applyCoupon("u-1", " ne-ten-2345 ", "t")
            cart.discountCents shouldBe 218
            cart.totalCents shouldBe 2 * 1090 + 199 - 218
            world.carts.rows
                .getValue("u-1")
                .couponCode shouldBe "NE-TEN-2345"

            val order = world.place("u-1", Language.DE, "t", "key-9", schanze).order
            order.discountCents shouldBe 218
            order.couponCode shouldBe "NE-TEN-2345"
            order.totalCents shouldBe 2 * 1090 + 199 - 218
            world.coupons.reservations shouldBe listOf("NE-TEN-2345" to "key-9")
            world.events.placed
                .single()
                .analytics shouldBe true
        }

        test("a coupon that stops applying blocks the cart; loyalty being down blocks the checkout, not the cart view") {
            val world = World().fill()
            world.applyCoupon("u-1", "NE-TEN-2345", "t")

            world.coupons.refusals["NE-TEN-2345"] = AppError.Invalid(ErrorCode("COUPON_MINIMUM_NOT_REACHED"), "too small")
            val refused = world.view("u-1", "t")
            refused.coupon?.problem shouldBe "COUPON_MINIMUM_NOT_REACHED"
            refused.problems shouldBe setOf(CartProblem.COUPON_NOT_APPLICABLE)
            refused.totalCents shouldBe 2 * 1090 + 199

            world.coupons.refusals.clear()
            world.coupons.down = true
            world.view("u-1", "t").coupon?.problem shouldBe "COUPON_UNAVAILABLE"
            shouldThrow<AppError.Conflict> { world.place("u-1", Language.DE, "t", "k1", schanze) }.code.value shouldBe
                "CART_COUPON_NOT_APPLICABLE"
            world.coupons.down = false
            RemoveCoupon(world.carts, world.view)("u-1", "t").coupon shouldBe null
        }

        test("without analytics consent the order says so; arriving publishes delivered once") {
            val world = World().fill()
            val order =
                PlaceOrder(
                    world.view,
                    world.carts,
                    world.orders,
                    world.requests,
                    world.events,
                    world.quotes,
                    world.coupons,
                    DirectTransactions,
                    world.analytics,
                    CheckoutSettings(),
                    world.clock,
                )("u-1", Language.DE, "t", "k1", schanze).order

            order.analytics shouldBe false
            world.follow(order.id, OrderStatus.DELIVERED, lunch.plusSeconds(170), null)
            world.follow(order.id, OrderStatus.DELIVERED, lunch.plusSeconds(171), null)
            world.events.delivered.map { it.id } shouldBe listOf(order.id)
        }
    })
