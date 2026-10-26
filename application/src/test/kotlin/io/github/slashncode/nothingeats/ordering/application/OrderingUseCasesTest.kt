package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.QuoteSource
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.error.AppError
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
    val analytics = RecordingAnalytics()
    val view = ViewCart(carts, menu, clock)
    private val changeCart = ChangeCart(carts, menu, view, analytics)
    private val placeOrder =
        PlaceOrder(view, carts, orders, requests, events, quotes, DirectTransactions, analytics, CheckoutSettings(), clock)

    // Server-side events need the visitor's distinct id, which arrives with the request after consent.
    private val visitor = AnalyticsContext("visitor-1", "session-1")

    suspend fun change(
        userId: String,
        dishId: String,
        quantity: Int,
        replaceCart: Boolean,
    ) = withContext(visitor) { changeCart(userId, dishId, quantity, replaceCart) }

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

            FollowDelivery(world.orders)(first.id, OrderStatus.EN_ROUTE, lunch.plusSeconds(90), lunch.plusSeconds(200))
            FollowDelivery(world.orders)(first.id, OrderStatus.COOKING, lunch.plusSeconds(91), null)
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
    })
