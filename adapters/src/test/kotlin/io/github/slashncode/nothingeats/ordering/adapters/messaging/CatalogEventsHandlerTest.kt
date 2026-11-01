package io.github.slashncode.nothingeats.ordering.adapters.messaging

import io.github.slashncode.nothingeats.ordering.application.InMemoryMenu
import io.github.slashncode.nothingeats.ordering.application.ProjectMenu
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.EventPosition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun dishEvent(
    available: Boolean,
    archived: Boolean?,
) = EventJson.decodeFromString(
    CloudEvent.serializer(),
    """{"id":"e1","source":"/nothingeats/catalog","type":"nothingeats.catalog.dish.published.v1","subject":"d1",
       "time":"2026-11-01T12:00:00Z","data":{"dishId":"d1","restaurantId":"r1","name":{"de":"Pizza","en":"Pizza"},
       "priceCents":990,"calories":800,"available":$available${archived?.let { ",\"archived\":$it" } ?: ""}}}""",
)

class CatalogEventsHandlerTest :
    FunSpec({
        test("an archived dish can no longer be ordered; events from before the field still read") {
            val menu = InMemoryMenu()
            val handler = CatalogEventsHandler(CatalogEventsHandler.DISHES, ProjectMenu(menu))

            handler.handle(dishEvent(available = true, archived = null), EventPosition(CatalogEventsHandler.DISHES, 0, 1))
            menu.dishes
                .getValue("d1")
                .first.available shouldBe true

            handler.handle(dishEvent(available = true, archived = true), EventPosition(CatalogEventsHandler.DISHES, 0, 2))
            menu.dishes
                .getValue("d1")
                .first.available shouldBe false
        }
    })
