package io.github.slashncode.nothingeats.ordering.adapters.http

import io.github.slashncode.nothingeats.ordering.application.ChangeCart
import io.github.slashncode.nothingeats.ordering.application.CheckoutQuote
import io.github.slashncode.nothingeats.ordering.application.ClearCart
import io.github.slashncode.nothingeats.ordering.application.ExportOrders
import io.github.slashncode.nothingeats.ordering.application.GetOrder
import io.github.slashncode.nothingeats.ordering.application.ListOrders
import io.github.slashncode.nothingeats.ordering.application.PlaceOrder
import io.github.slashncode.nothingeats.ordering.application.QuoteCheckout
import io.github.slashncode.nothingeats.ordering.application.ViewCart
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.InvalidOrder
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.PricedCart
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class CartRestaurantResponse(
    val slug: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
)

@Serializable
data class CartLineResponse(
    val dishId: String,
    val name: String,
    val unitPriceCents: Long,
    val quantity: Int,
    val totalCents: Long,
    val available: Boolean,
)

@Serializable
data class CartResponse(
    val restaurant: CartRestaurantResponse?,
    val lines: List<CartLineResponse>,
    val itemCount: Int,
    val subtotalCents: Long,
    val deliveryFeeCents: Long,
    val totalCents: Long,
    val calories: Int,
    /** `DISH_UNAVAILABLE`, `MINIMUM_NOT_REACHED`, `RESTAURANT_CLOSED` */
    val problems: List<String>,
    val orderable: Boolean,
)

@Serializable
data class CartItemRequest(
    val quantity: Int,
    /** Start a new cart when the dish is from another restaurant. */
    val replaceCart: Boolean = false,
)

@Serializable
data class DropOffRequest(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)

@Serializable
data class CheckoutRequest(
    val dropOff: DropOffRequest,
)

@Serializable
data class QuoteResponse(
    val etaSeconds: Int,
    val simulatedMinutes: Int,
    val distanceMeters: Int?,
    val courierShortage: Boolean,
    /** `delivery` (live) or `estimate` (delivery service did not answer) */
    val source: String,
)

@Serializable
data class OrderLineResponse(
    val dishId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalCents: Long,
)

@Serializable
data class OrderRestaurantResponse(
    val slug: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class DropOffResponse(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
)

@Serializable
data class OrderResponse(
    val id: String,
    val status: String,
    val placedAt: String,
    val statusChangedAt: String,
    val estimatedArrivalAt: String,
    val restaurant: OrderRestaurantResponse,
    val lines: List<OrderLineResponse>,
    val subtotalCents: Long,
    val deliveryFeeCents: Long,
    val totalCents: Long,
    val calories: Int,
    val dropOff: DropOffResponse,
    val quoteSource: String,
)

@Serializable
data class OrderPageResponse(
    val items: List<OrderResponse>,
    val nextCursor: String?,
)

/**
 * `/api/ordering/v1`, all for the signed-in customer: cart, quote, checkout with an
 * `Idempotency-Key`, order history and the GDPR export. Names come in `?lang=` or Accept-Language.
 */
class OrderingRoutes(
    private val viewCart: ViewCart,
    private val changeCart: ChangeCart,
    private val clearCart: ClearCart,
    private val quoteCheckout: QuoteCheckout,
    private val placeOrder: PlaceOrder,
    private val listOrders: ListOrders,
    private val getOrder: GetOrder,
    private val exportOrders: ExportOrders,
) : HttpRoutes {
    override fun Route.register() {
        route("/api/ordering/v1") {
            authenticated {
                get("/cart") { call.respond(viewCart(call.user.id).response(call.language())) }
                put("/cart/items/{dishId}") {
                    val request = call.receive<CartItemRequest>()
                    call.respond(
                        changeCart(
                            call.user.id,
                            call.parameters["dishId"].orEmpty(),
                            request.quantity,
                            request.replaceCart,
                        ).response(call.language()),
                    )
                }
                delete("/cart") {
                    clearCart(call.user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/quotes") {
                    val dropOff = call.receive<CheckoutRequest>().dropOff.domain()
                    call.respond(quoteCheckout(call.user.id, dropOff, call.accessToken()).response())
                }
                post("/orders") {
                    val dropOff = call.receive<CheckoutRequest>().dropOff.domain()
                    val language = call.language()
                    val placed = placeOrder(call.user.id, language, call.accessToken(), call.request.headers["Idempotency-Key"], dropOff)
                    call.response.header(HttpHeaders.Location, "/api/ordering/v1/orders/${placed.order.id}")
                    if (placed.replayed) call.response.header("Idempotent-Replayed", "true")
                    call.respond(if (placed.replayed) HttpStatusCode.OK else HttpStatusCode.Created, placed.order.response(language))
                }
                get("/orders") {
                    val language = call.language()
                    val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: 0 } ?: DEFAULT_PAGE
                    val page = listOrders(call.user.id, call.request.queryParameters["cursor"], limit)
                    call.respond(OrderPageResponse(page.orders.map { it.response(language) }, page.nextCursor))
                }
                get("/orders/{id}") { call.respond(getOrder(call.user.id, call.parameters["id"].orEmpty()).response(call.language())) }
                get("/me/export") {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"nothingeats-orders.json\"")
                    call.respond(exportOrders(call.user.id).map { it.response(call.language()) })
                }
            }
        }
    }

    private fun ApplicationCall.language(): Language {
        val language = Language.parse(request.queryParameters["lang"] ?: request.acceptLanguage())
        response.header(HttpHeaders.ContentLanguage, language.tag)
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        return language
    }

    // The customer's own token, forwarded to the delivery service (it authorizes the customer itself).
    private fun ApplicationCall.accessToken(): String = request.headers[HttpHeaders.Authorization].orEmpty().substringAfter("Bearer ", "")

    private fun DropOffRequest.domain(): DropOff =
        try {
            DropOff(Location(latitude, longitude), label?.trim()?.ifEmpty { null })
        } catch (e: InvalidOrder) {
            throw AppError.Invalid(
                ErrorCode("ORDERING_INVALID"),
                "The request is invalid",
                listOf(Violation(e.field, e.message ?: "invalid")),
            )
        }

    private fun PricedCart.response(language: Language) =
        CartResponse(
            restaurant =
                restaurant?.let {
                    CartRestaurantResponse(
                        it.slug,
                        it.name,
                        it.location.latitude,
                        it.location.longitude,
                        it.deliveryMinutes,
                        it.deliveryFeeCents,
                        it.minimumOrderCents,
                    )
                },
            lines =
                lines.map {
                    CartLineResponse(
                        it.dish.id,
                        it.dish.name.of(language),
                        it.dish.priceCents,
                        it.quantity,
                        it.totalCents,
                        it.dish.available,
                    )
                },
            itemCount = lines.sumOf { it.quantity },
            subtotalCents = subtotalCents,
            deliveryFeeCents = deliveryFeeCents,
            totalCents = totalCents,
            calories = calories,
            problems = problems.map { it.name }.sorted(),
            orderable = orderable,
        )

    private fun CheckoutQuote.response() =
        QuoteResponse(etaSeconds, simulatedMinutes, distanceMeters, courierShortage, source.name.lowercase())

    private fun Order.response(language: Language) =
        OrderResponse(
            id = id,
            status = status.name,
            placedAt = placedAt.toString(),
            statusChangedAt = statusChangedAt.toString(),
            estimatedArrivalAt = estimatedArrivalAt.toString(),
            restaurant =
                OrderRestaurantResponse(
                    restaurant.slug,
                    restaurant.name,
                    restaurant.location.latitude,
                    restaurant.location.longitude,
                ),
            lines = lines.map { OrderLineResponse(it.dishId, it.name.of(language), it.quantity, it.unitPriceCents, it.totalCents) },
            subtotalCents = subtotalCents,
            deliveryFeeCents = deliveryFeeCents,
            totalCents = totalCents,
            calories = calories,
            dropOff = DropOffResponse(dropOff.location.latitude, dropOff.location.longitude, dropOff.label),
            quoteSource = quoteSource.name.lowercase(),
        )

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}
