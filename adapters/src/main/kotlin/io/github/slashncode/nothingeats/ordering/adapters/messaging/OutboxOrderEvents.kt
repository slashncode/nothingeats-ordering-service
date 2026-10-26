package io.github.slashncode.nothingeats.ordering.adapters.messaging

import io.github.slashncode.nothingeats.ordering.application.OrderEvents
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import kotlinx.serialization.Serializable

/** Contract: docs/events.md. */
object OrderingTopics {
    const val ORDERS = "ordering.order.v1"
    const val PLACED = "nothingeats.ordering.order.placed.v1"
    const val SOURCE = "/nothingeats/ordering"
}

@Serializable
data class PlacedRestaurantV1(
    val id: String,
    val slug: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val preparationMinutes: Int,
)

@Serializable
data class DropOffV1(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
)

@Serializable
data class NameV1(
    val de: String,
    val en: String,
)

@Serializable
data class LineV1(
    val dishId: String,
    val name: NameV1,
    val quantity: Int,
    val unitPriceCents: Long,
    val calories: Int,
)

@Serializable
data class OrderPlacedV1(
    val orderId: String,
    val userId: String,
    val locale: String,
    val placedAt: String,
    val restaurant: PlacedRestaurantV1,
    val dropOff: DropOffV1,
    val lines: List<LineV1>,
    val subtotalCents: Long,
    val deliveryFeeCents: Long,
    val totalCents: Long,
    val calories: Int,
    val quotedSeconds: Int,
    val quoteSource: String,
    val estimatedArrivalAt: String,
)

class OutboxOrderEvents(
    private val outbox: Outbox,
) : OrderEvents {
    override suspend fun placed(order: Order) {
        outbox.enqueue(
            OrderingTopics.ORDERS,
            order.id,
            OrderingTopics.PLACED,
            OrderPlacedV1(
                orderId = order.id,
                userId = order.userId,
                locale = order.language.tag,
                placedAt = order.placedAt.toString(),
                restaurant =
                    PlacedRestaurantV1(
                        order.restaurant.id,
                        order.restaurant.slug,
                        order.restaurant.name,
                        order.restaurant.location.latitude,
                        order.restaurant.location.longitude,
                        order.restaurant.preparationMinutes,
                    ),
                dropOff = DropOffV1(order.dropOff.location.latitude, order.dropOff.location.longitude, order.dropOff.label),
                lines = order.lines.map { LineV1(it.dishId, NameV1(it.name.de, it.name.en), it.quantity, it.unitPriceCents, it.calories) },
                subtotalCents = order.subtotalCents,
                deliveryFeeCents = order.deliveryFeeCents,
                totalCents = order.totalCents,
                calories = order.calories,
                quotedSeconds = order.quotedSeconds,
                quoteSource = order.quoteSource.name,
                estimatedArrivalAt = order.estimatedArrivalAt.toString(),
            ),
            OrderPlacedV1.serializer(),
        )
    }
}
