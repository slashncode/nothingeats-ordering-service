package io.github.slashncode.nothingeats.ordering.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalTime

private val pizza =
    MenuRestaurant("r-1", "phantom-pizza", "Phantom Pizza", Location(53.55, 9.96), 30, 199, 1200, LocalTime.of(11, 0), LocalTime.of(23, 0))
private val margherita = MenuDish("d-1", "r-1", Text("Margherita des Nichts", "Margherita of Nothing"), 1090, 820, true)
private val tiramisu = MenuDish("d-2", "r-1", Text("Tiramisu von morgen", "Tiramisu of Tomorrow"), 650, 450, true)
private val gyros = MenuDish("d-3", "r-2", Text("Gyros-Teller", "Gyros Plate"), 1450, 1100, true)

class OrderingTest :
    FunSpec({
        test("a cart holds one restaurant; another one needs an explicit fresh start") {
            val cart = Cart.empty("u-1").set(margherita, 2, replace = false).set(tiramisu, 1, replace = false)

            cart.lines shouldBe listOf(CartLine("d-1", 2), CartLine("d-2", 1))
            shouldThrow<OtherRestaurant> { cart.set(gyros, 1, replace = false) }.current shouldBe "r-1"
            cart.set(gyros, 1, replace = true) shouldBe Cart("u-1", "r-2", listOf(CartLine("d-3", 1)))
            cart.set(margherita, 0, replace = false).set(tiramisu, 0, replace = false) shouldBe Cart("u-1", null, emptyList())
            shouldThrow<InvalidOrder> { cart.set(margherita, 21, replace = false) }.field shouldBe "quantity"
        }

        test("prices come from the menu, with everything that blocks an order") {
            val cart = Cart.empty("u-1").set(margherita, 1, replace = false)

            val small = PricedCart.of(cart, pizza, mapOf("d-1" to margherita), openNow = true)
            small.subtotalCents shouldBe 1090
            small.totalCents shouldBe 1289
            small.problems shouldBe setOf(CartProblem.MINIMUM_NOT_REACHED)

            val big = PricedCart.of(cart.set(margherita, 2, replace = false), pizza, mapOf("d-1" to margherita), openNow = false)
            big.problems shouldBe setOf(CartProblem.RESTAURANT_CLOSED)
            big.calories shouldBe 1640

            val gone =
                PricedCart.of(
                    cart.set(margherita, 2, replace = false),
                    pizza,
                    mapOf("d-1" to margherita.copy(available = false)),
                    true,
                )
            gone.orderable shouldBe false
            gone.problems shouldBe setOf(CartProblem.DISH_UNAVAILABLE)
        }

        test("an order only moves forward") {
            val at = Instant.parse("2026-10-26T12:00:00Z")
            val order =
                Order(
                    "o-1",
                    "u-1",
                    Language.DE,
                    OrderedRestaurant("r-1", "phantom-pizza", "Phantom Pizza", pizza.location, 15),
                    listOf(OrderLine("d-1", margherita.name, 1090, 2, 820)),
                    199,
                    DropOff(Location(53.56, 9.97), null),
                    180,
                    QuoteSource.DELIVERY,
                    at,
                    OrderStatus.PLACED,
                    at,
                    at.plusSeconds(180),
                )

            val cooking = order.follow(OrderStatus.COOKING, at.plusSeconds(10), null)!!
            cooking.status shouldBe OrderStatus.COOKING
            cooking.follow(OrderStatus.ACCEPTED, at.plusSeconds(11), null) shouldBe null
            order.totalCents shouldBe 2379
            order.calories shouldBe 1640
        }

        test("opening hours past midnight, preparation share, languages") {
            pizza.copy(opensAt = LocalTime.of(18, 0), closesAt = LocalTime.of(2, 0)).isOpenAt(LocalTime.of(1, 0)) shouldBe true
            pizza.preparationMinutes shouldBe 15
            Language.parse("en-GB,en;q=0.9") shouldBe Language.EN
        }
    })
