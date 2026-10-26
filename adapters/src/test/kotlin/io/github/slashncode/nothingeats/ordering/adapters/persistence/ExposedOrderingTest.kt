package io.github.slashncode.nothingeats.ordering.adapters.persistence

import io.github.slashncode.nothingeats.ordering.application.margherita
import io.github.slashncode.nothingeats.ordering.application.phantomPizza
import io.github.slashncode.nothingeats.ordering.application.tiramisu
import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.CartLine
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDeal
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.OrderLine
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.OrderedRestaurant
import io.github.slashncode.nothingeats.ordering.domain.QuoteSource
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

class ExposedOrderingTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
            migrations.migrate()
            database = PostgresDatabase(config, migrations).also { it.start() }
        }

        afterTest { database.stop() }

        val noon = Instant.parse("2026-11-02T11:00:00Z")

        test("a deal applies to its dish while it runs; a repeated deal event changes nothing") {
            val menu = ExposedMenu(database)
            menu.upsertDish(margherita, 1)
            menu.upsertDish(tiramisu, 2)
            val deal = MenuDeal("deal-1", margherita.id, 760, 30, noon, noon.plusSeconds(1800))
            menu.upsertDeal(deal)
            menu.upsertDeal(deal.copy(priceCents = 1))

            val during = menu.dishes(listOf(margherita.id, tiramisu.id), noon.plusSeconds(60))
            during.getValue(margherita.id).deal shouldBe deal
            during.getValue(margherita.id).unitPriceCents shouldBe 760
            during.getValue(tiramisu.id).deal.shouldBeNull()
            menu
                .dishes(listOf(margherita.id), noon.plusSeconds(1800))
                .getValue(margherita.id)
                .deal
                .shouldBeNull()
        }

        test("the cart keeps its coupon, which goes with the cart") {
            val carts = ExposedCarts(database)
            carts.save(Cart("u-1", phantomPizza.id, listOf(CartLine(margherita.id, 2)), "NE-TEN-2345"))

            carts.find("u-1")!!.couponCode shouldBe "NE-TEN-2345"
            carts.save(carts.find("u-1")!!.copy(couponCode = null))
            carts.find("u-1")!!.couponCode.shouldBeNull()
            carts.save(carts.find("u-1")!!.copy(couponCode = "NE-TEN-2345"))
            carts.delete("u-1")
            carts.save(Cart("u-1", phantomPizza.id, listOf(CartLine(margherita.id, 1))))
            carts.find("u-1")!!.couponCode.shouldBeNull()
        }

        test("orders keep their discount, coupon, consent and deal prices") {
            val orders = ExposedOrders(database)
            val order =
                Order(
                    id = "o-1",
                    userId = "u-1",
                    language = Language.DE,
                    restaurant = OrderedRestaurant(phantomPizza.id, phantomPizza.slug, phantomPizza.name, phantomPizza.location, 15),
                    lines = listOf(OrderLine(margherita.id, margherita.name, 760, 2, 820, regularUnitPriceCents = 1090)),
                    deliveryFeeCents = 199,
                    dropOff = DropOff(Location(53.5636, 9.9654), null),
                    quotedSeconds = 170,
                    quoteSource = QuoteSource.DELIVERY,
                    placedAt = noon,
                    status = OrderStatus.PLACED,
                    statusChangedAt = noon,
                    estimatedArrivalAt = noon.plusSeconds(170),
                    discountCents = 152,
                    couponCode = "NE-TEN-2345",
                    analytics = true,
                )
            orders.insert(order)

            orders.find("o-1") shouldBe order
            orders.find("o-1")!!.totalCents shouldBe 2 * 760 + 199 - 152
        }
    })
