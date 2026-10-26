package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.Text
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class InMemoryMenu : Menu {
    val restaurants = ConcurrentHashMap<String, Pair<MenuRestaurant, Long>>()
    val dishes = ConcurrentHashMap<String, Pair<MenuDish, Long>>()

    override suspend fun upsertRestaurant(
        restaurant: MenuRestaurant,
        version: Long,
    ) {
        restaurants.merge(restaurant.id, restaurant to version) { old, new -> if (new.second > old.second) new else old }
    }

    override suspend fun upsertDish(
        dish: MenuDish,
        version: Long,
    ) {
        dishes.merge(dish.id, dish to version) { old, new -> if (new.second > old.second) new else old }
    }

    override suspend fun restaurant(id: String) = restaurants[id]?.first

    override suspend fun dishes(ids: Collection<String>) = ids.mapNotNull { dishes[it]?.first }.associateBy { it.id }
}

class InMemoryCarts : Carts {
    val rows = ConcurrentHashMap<String, Cart>()

    override suspend fun find(userId: String) = rows[userId]

    override suspend fun save(cart: Cart) {
        rows[cart.userId] = cart
    }

    override suspend fun delete(userId: String) {
        rows.remove(userId)
    }
}

class InMemoryOrders : Orders {
    val rows = ConcurrentHashMap<String, Order>()

    override suspend fun insert(order: Order) {
        rows[order.id] = order
    }

    override suspend fun find(id: String) = rows[id]

    override suspend fun page(
        userId: String,
        cursor: OrderCursor?,
        limit: Int,
    ) = all(userId)
        .filter { cursor == null || it.placedAt < cursor.placedAt || (it.placedAt == cursor.placedAt && it.id < cursor.id) }
        .take(limit)

    override suspend fun all(userId: String) =
        rows.values.filter { it.userId == userId }.sortedWith(compareByDescending<Order> { it.placedAt }.thenByDescending { it.id })

    override suspend fun updateStatus(order: Order) {
        rows[order.id] = order
    }

    override suspend fun deleteByUser(userId: String): Int {
        val mine = rows.values.filter { it.userId == userId }
        mine.forEach { rows.remove(it.id) }
        return mine.size
    }
}

class InMemoryRequests : OrderRequests {
    val rows = ConcurrentHashMap<Pair<String, String>, StoredRequest>()

    override suspend fun find(
        userId: String,
        key: String,
    ) = rows[userId to key]

    override suspend fun insert(
        userId: String,
        key: String,
        request: StoredRequest,
    ) {
        if (rows.putIfAbsent(userId to key, request) != null) throw DuplicateRequest()
    }
}

class RecordingOrderEvents : OrderEvents {
    val placed = mutableListOf<Order>()

    override suspend fun placed(order: Order) {
        placed += order
    }
}

/** Answers with [answer]; null plays a delivery service that did not answer in time. */
class FakeQuotes(
    var answer: DeliveryQuote? = DeliveryQuote(etaSeconds = 160, simulatedMinutes = 27, distanceMeters = 2_300, courierShortage = false),
) : DeliveryQuotes {
    val tokens = mutableListOf<String>()

    override suspend fun quote(
        pickup: Location,
        dropOff: Location,
        preparationMinutes: Int,
        accessToken: String,
    ): DeliveryQuote? {
        tokens += accessToken
        return answer
    }
}

object DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class MovableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId) = this

    override fun instant(): Instant = now
}

val phantomPizza =
    MenuRestaurant(
        "r-1",
        "phantom-pizza",
        "Phantom Pizza",
        Location(53.5503, 9.9667),
        30,
        199,
        1200,
        LocalTime.of(11, 0),
        LocalTime.of(23, 0),
    )
val ghostGyros =
    MenuRestaurant("r-2", "ghost-gyros", "Ghost Gyros", Location(53.5511, 9.9937), 25, 149, 1000, LocalTime.of(11, 30), LocalTime.of(1, 0))
val margherita = MenuDish("d-1", "r-1", Text("Margherita des Nichts", "Margherita of Nothing"), 1090, 820, true)
val tiramisu = MenuDish("d-2", "r-1", Text("Tiramisu von morgen", "Tiramisu of Tomorrow"), 650, 450, true)
val gyrosPlate = MenuDish("d-3", "r-2", Text("Gyros-Teller aus Luft", "Gyros Plate of Air"), 1450, 1100, true)

suspend fun InMemoryMenu.seeded() =
    apply {
        upsertRestaurant(phantomPizza, 1)
        upsertRestaurant(ghostGyros, 2)
        upsertDish(margherita, 1)
        upsertDish(tiramisu, 2)
        upsertDish(gyrosPlate, 3)
    }
