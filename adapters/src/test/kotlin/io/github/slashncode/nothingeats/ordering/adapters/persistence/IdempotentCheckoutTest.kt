package io.github.slashncode.nothingeats.ordering.adapters.persistence

import io.github.slashncode.nothingeats.ordering.application.ChangeCart
import io.github.slashncode.nothingeats.ordering.application.CheckoutSettings
import io.github.slashncode.nothingeats.ordering.application.FakeQuotes
import io.github.slashncode.nothingeats.ordering.application.MovableClock
import io.github.slashncode.nothingeats.ordering.application.PlaceOrder
import io.github.slashncode.nothingeats.ordering.application.RecordingOrderEvents
import io.github.slashncode.nothingeats.ordering.application.ViewCart
import io.github.slashncode.nothingeats.ordering.application.margherita
import io.github.slashncode.nothingeats.ordering.application.phantomPizza
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/** Against real Postgres: the idempotency key's primary key decides a race between two requests. */
class IdempotentCheckoutTest :
    FunSpec({
        test("two concurrent checkouts with the same key make one order, and both answer with it") {
            val config = PostgresTestDatabases.create()
            val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
            migrations.migrate()
            val db = PostgresDatabase(config, migrations).also { it.start() }
            val menu =
                ExposedMenu(db).apply {
                    upsertRestaurant(phantomPizza, 1)
                    upsertDish(margherita, 1)
                }
            val carts = ExposedCarts(db)
            val orders = ExposedOrders(db)
            val events = RecordingOrderEvents()
            // 12:00 UTC is 13:00 in Berlin: the restaurant is open.
            val view = ViewCart(carts, menu, MovableClock(Instant.parse("2026-10-26T12:00:00Z")))
            ChangeCart(carts, menu, view, RecordingAnalytics())("u-1", margherita.id, 2, replaceCart = false)
            val place =
                PlaceOrder(
                    view,
                    carts,
                    orders,
                    ExposedOrderRequests(db),
                    events,
                    FakeQuotes(),
                    ExposedTransactions(db),
                    RecordingAnalytics(),
                    CheckoutSettings(),
                )
            val dropOff = DropOff(Location(53.5636, 9.9654), "Schanze")

            val results =
                coroutineScope {
                    (1..2).map { async { place("u-1", Language.DE, "token", "same-key", dropOff) } }.awaitAll()
                }

            results.map { it.order.id }.toSet().size shouldBe 1
            results.count { it.replayed } shouldBe 1
            orders.all("u-1").size shouldBe 1
            db.stop()
        }
    })
