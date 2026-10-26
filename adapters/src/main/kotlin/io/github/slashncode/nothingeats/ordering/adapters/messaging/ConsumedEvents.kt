package io.github.slashncode.nothingeats.ordering.adapters.messaging

import io.github.slashncode.nothingeats.ordering.application.FollowDelivery
import io.github.slashncode.nothingeats.ordering.application.ForgetUser
import io.github.slashncode.nothingeats.ordering.application.ProjectMenu
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.Text
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.EventPosition
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime

// Tolerant readers of other services' contracts: only the fields ordering needs.

@Serializable
internal data class TextV1(
    val de: String,
    val en: String,
)

@Serializable
internal data class RestaurantPublished(
    val restaurantId: String,
    val slug: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val opensAt: String,
    val closesAt: String,
)

@Serializable
internal data class DishPublished(
    val dishId: String,
    val restaurantId: String,
    val name: TextV1,
    val priceCents: Long,
    val calories: Int,
    val available: Boolean = true,
)

/**
 * `catalog.restaurant.v1` and `catalog.dish.v1` (catalog-service docs/events.md) into the menu
 * projection, versioned by offset, so a late or repeated snapshot never wins over a newer one.
 */
class CatalogEventsHandler(
    override val topic: String,
    private val project: ProjectMenu,
) : EventHandler {
    override suspend fun handle(event: CloudEvent): Unit = error("needs the record position")

    override suspend fun handle(
        event: CloudEvent,
        position: EventPosition,
    ) {
        when (event.type) {
            RESTAURANT -> {
                val r = event.decode(RestaurantPublished.serializer())
                project.restaurant(
                    MenuRestaurant(
                        r.restaurantId,
                        r.slug,
                        r.name,
                        Location(r.latitude, r.longitude),
                        r.deliveryMinutes,
                        r.deliveryFeeCents,
                        r.minimumOrderCents,
                        LocalTime.parse(r.opensAt),
                        LocalTime.parse(r.closesAt),
                    ),
                    position.offset,
                )
            }
            DISH -> {
                val d = event.decode(DishPublished.serializer())
                project.dish(
                    MenuDish(d.dishId, d.restaurantId, Text(d.name.de, d.name.en), d.priceCents, d.calories, d.available),
                    position.offset,
                )
            }
        }
    }

    companion object {
        const val RESTAURANTS = "catalog.restaurant.v1"
        const val DISHES = "catalog.dish.v1"
        private const val RESTAURANT = "nothingeats.catalog.restaurant.published.v1"
        private const val DISH = "nothingeats.catalog.dish.published.v1"
    }
}

@Serializable
internal data class StatusChanged(
    val orderId: String,
    val status: String,
    val at: String,
    val estimatedArrivalAt: String? = null,
)

/** `delivery.delivery.v1` (delivery-service docs/events.md): the order follows its delivery. */
class DeliveryEventsHandler(
    private val follow: FollowDelivery,
) : EventHandler {
    override val topic = "delivery.delivery.v1"

    private val log = LoggerFactory.getLogger(DeliveryEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        if (event.type != STATUS_CHANGED) return
        val change = event.decode(StatusChanged.serializer())
        val status = OrderStatus.entries.firstOrNull { it.name == change.status }
        if (status == null) {
            log.info("Ignoring unknown delivery status {}", change.status)
            return
        }
        follow(change.orderId, status, Instant.parse(change.at), change.estimatedArrivalAt?.let(Instant::parse))
    }

    private companion object {
        const val STATUS_CHANGED = "nothingeats.delivery.delivery.status-changed.v1"
    }
}

@Serializable
internal data class UserDeleted(
    val userId: String,
)

/** `user.deleted` (record 030): orders with their drop-off locations, and the cart, go. */
class IdentityEventsHandler(
    private val forget: ForgetUser,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        if (event.type != DELETED) return
        log.info("Account deleted: {} orders erased", forget(event.decode(UserDeleted.serializer()).userId))
    }

    private companion object {
        const val DELETED = "nothingeats.identity.user.deleted.v1"
    }
}
