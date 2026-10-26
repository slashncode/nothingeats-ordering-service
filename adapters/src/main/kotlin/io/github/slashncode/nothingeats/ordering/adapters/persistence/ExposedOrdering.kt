package io.github.slashncode.nothingeats.ordering.adapters.persistence

import io.github.slashncode.nothingeats.ordering.application.Carts
import io.github.slashncode.nothingeats.ordering.application.DuplicateRequest
import io.github.slashncode.nothingeats.ordering.application.Menu
import io.github.slashncode.nothingeats.ordering.application.OrderCursor
import io.github.slashncode.nothingeats.ordering.application.OrderRequests
import io.github.slashncode.nothingeats.ordering.application.Orders
import io.github.slashncode.nothingeats.ordering.application.StoredRequest
import io.github.slashncode.nothingeats.ordering.application.Transactions
import io.github.slashncode.nothingeats.ordering.domain.Cart
import io.github.slashncode.nothingeats.ordering.domain.CartLine
import io.github.slashncode.nothingeats.ordering.domain.DropOff
import io.github.slashncode.nothingeats.ordering.domain.Language
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.ordering.domain.MenuDish
import io.github.slashncode.nothingeats.ordering.domain.MenuRestaurant
import io.github.slashncode.nothingeats.ordering.domain.Order
import io.github.slashncode.nothingeats.ordering.domain.OrderLine
import io.github.slashncode.nothingeats.ordering.domain.OrderStatus
import io.github.slashncode.nothingeats.ordering.domain.OrderedRestaurant
import io.github.slashncode.nothingeats.ordering.domain.QuoteSource
import io.github.slashncode.nothingeats.ordering.domain.Text
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.persistence.isUniqueViolation
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal object MenuRestaurantsTable : Table("menu_restaurants") {
    val id = text("id")
    val slug = text("slug")
    val name = text("name")
    val latitude = double("latitude")
    val longitude = double("longitude")
    val deliveryMinutes = integer("delivery_minutes")
    val deliveryFeeCents = long("delivery_fee_cents")
    val minimumOrderCents = long("minimum_order_cents")
    val opensAt = time("opens_at")
    val closesAt = time("closes_at")
    val version = long("version")

    override val primaryKey = PrimaryKey(id)
}

internal object MenuDishesTable : Table("menu_dishes") {
    val id = text("id")
    val restaurantId = text("restaurant_id")
    val nameDe = text("name_de")
    val nameEn = text("name_en")
    val priceCents = long("price_cents")
    val calories = integer("calories")
    val available = bool("available")
    val version = long("version")

    override val primaryKey = PrimaryKey(id)
}

internal object CartLinesTable : Table("cart_lines") {
    val userId = text("user_id")
    val dishId = text("dish_id")
    val restaurantId = text("restaurant_id")
    val quantity = integer("quantity")
    val addedAt = timestampWithTimeZone("added_at")

    override val primaryKey = PrimaryKey(userId, dishId)
}

internal object OrdersTable : Table("orders") {
    val id = text("id")
    val userId = text("user_id")
    val language = text("language")
    val restaurantId = text("restaurant_id")
    val restaurantSlug = text("restaurant_slug")
    val restaurantName = text("restaurant_name")
    val pickupLatitude = double("pickup_latitude")
    val pickupLongitude = double("pickup_longitude")
    val preparationMinutes = integer("preparation_minutes")
    val deliveryFeeCents = long("delivery_fee_cents")
    val dropOffLatitude = double("drop_off_latitude")
    val dropOffLongitude = double("drop_off_longitude")
    val dropOffLabel = text("drop_off_label").nullable()
    val quotedSeconds = integer("quoted_seconds")
    val quoteSource = text("quote_source")
    val placedAt = timestampWithTimeZone("placed_at")
    val status = text("status")
    val statusChangedAt = timestampWithTimeZone("status_changed_at")
    val estimatedArrivalAt = timestampWithTimeZone("estimated_arrival_at")

    override val primaryKey = PrimaryKey(id)
}

internal object OrderLinesTable : Table("order_lines") {
    val orderId = text("order_id")
    val position = integer("position")
    val dishId = text("dish_id")
    val nameDe = text("name_de")
    val nameEn = text("name_en")
    val unitPriceCents = long("unit_price_cents")
    val quantity = integer("quantity")
    val calories = integer("calories")

    override val primaryKey = PrimaryKey(orderId, position)
}

internal object OrderRequestsTable : Table("order_requests") {
    val userId = text("user_id")
    val idempotencyKey = text("idempotency_key")
    val requestHash = text("request_hash")
    val orderId = text("order_id")

    override val primaryKey = PrimaryKey(userId, idempotencyKey)
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedMenu(
    private val db: PostgresDatabase,
) : Menu {
    override suspend fun upsertRestaurant(
        restaurant: MenuRestaurant,
        version: Long,
    ) {
        db.transaction {
            // Only a newer snapshot wins (ON CONFLICT ... DO UPDATE ... WHERE version < new version).
            MenuRestaurantsTable.upsert(MenuRestaurantsTable.id, where = { MenuRestaurantsTable.version less longLiteral(version) }) {
                it[id] = restaurant.id
                it[slug] = restaurant.slug
                it[name] = restaurant.name
                it[latitude] = restaurant.location.latitude
                it[longitude] = restaurant.location.longitude
                it[deliveryMinutes] = restaurant.deliveryMinutes
                it[deliveryFeeCents] = restaurant.deliveryFeeCents
                it[minimumOrderCents] = restaurant.minimumOrderCents
                it[opensAt] = restaurant.opensAt
                it[closesAt] = restaurant.closesAt
                it[this.version] = version
            }
        }
    }

    override suspend fun upsertDish(
        dish: MenuDish,
        version: Long,
    ) {
        db.transaction {
            MenuDishesTable.upsert(MenuDishesTable.id, where = { MenuDishesTable.version less longLiteral(version) }) {
                it[id] = dish.id
                it[restaurantId] = dish.restaurantId
                it[nameDe] = dish.name.de
                it[nameEn] = dish.name.en
                it[priceCents] = dish.priceCents
                it[calories] = dish.calories
                it[available] = dish.available
                it[this.version] = version
            }
        }
    }

    override suspend fun restaurant(id: String): MenuRestaurant? =
        db.transaction(readOnly = true) {
            MenuRestaurantsTable.selectAll().where { MenuRestaurantsTable.id eq id }.singleOrNull()?.let {
                val t = MenuRestaurantsTable
                MenuRestaurant(
                    it[t.id],
                    it[t.slug],
                    it[t.name],
                    Location(it[t.latitude], it[t.longitude]),
                    it[t.deliveryMinutes],
                    it[t.deliveryFeeCents],
                    it[t.minimumOrderCents],
                    it[t.opensAt],
                    it[t.closesAt],
                )
            }
        }

    override suspend fun dishes(ids: Collection<String>): Map<String, MenuDish> {
        if (ids.isEmpty()) return emptyMap()
        return db.transaction(readOnly = true) {
            val t = MenuDishesTable
            t.selectAll().where { t.id inList ids }.associate {
                it[t.id] to
                    MenuDish(
                        it[t.id],
                        it[t.restaurantId],
                        Text(it[t.nameDe], it[t.nameEn]),
                        it[t.priceCents],
                        it[t.calories],
                        it[t.available],
                    )
            }
        }
    }
}

class ExposedCarts(
    private val db: PostgresDatabase,
) : Carts {
    override suspend fun find(userId: String): Cart? =
        db.transaction(readOnly = true) {
            val rows =
                CartLinesTable
                    .selectAll()
                    .where { CartLinesTable.userId eq userId }
                    .orderBy(CartLinesTable.addedAt to SortOrder.ASC, CartLinesTable.dishId to SortOrder.ASC)
                    .toList()
            if (rows.isEmpty()) {
                null
            } else {
                Cart(
                    userId,
                    rows.first()[CartLinesTable.restaurantId],
                    rows.map { CartLine(it[CartLinesTable.dishId], it[CartLinesTable.quantity]) },
                )
            }
        }

    // The whole cart is replaced; lines that stay keep their position.
    override suspend fun save(cart: Cart) {
        db.transaction {
            val existing =
                CartLinesTable
                    .selectAll()
                    .where { CartLinesTable.userId eq cart.userId }
                    .associate { it[CartLinesTable.dishId] to it[CartLinesTable.addedAt] }
            CartLinesTable.deleteWhere { CartLinesTable.userId eq cart.userId }
            val now = Instant.now().utc()
            CartLinesTable.batchInsert(cart.lines) { line ->
                this[CartLinesTable.userId] = cart.userId
                this[CartLinesTable.dishId] = line.dishId
                this[CartLinesTable.restaurantId] = requireNotNull(cart.restaurantId)
                this[CartLinesTable.quantity] = line.quantity
                this[CartLinesTable.addedAt] = existing[line.dishId] ?: now
            }
        }
    }

    override suspend fun delete(userId: String) {
        db.transaction { CartLinesTable.deleteWhere { CartLinesTable.userId eq userId } }
    }
}

class ExposedOrders(
    private val db: PostgresDatabase,
) : Orders {
    override suspend fun insert(order: Order) {
        db.transaction {
            OrdersTable.insert {
                it[id] = order.id
                it[userId] = order.userId
                it[language] = order.language.tag
                it[restaurantId] = order.restaurant.id
                it[restaurantSlug] = order.restaurant.slug
                it[restaurantName] = order.restaurant.name
                it[pickupLatitude] = order.restaurant.location.latitude
                it[pickupLongitude] = order.restaurant.location.longitude
                it[preparationMinutes] = order.restaurant.preparationMinutes
                it[deliveryFeeCents] = order.deliveryFeeCents
                it[dropOffLatitude] = order.dropOff.location.latitude
                it[dropOffLongitude] = order.dropOff.location.longitude
                it[dropOffLabel] = order.dropOff.label
                it[quotedSeconds] = order.quotedSeconds
                it[quoteSource] = order.quoteSource.name
                it[placedAt] = order.placedAt.utc()
                it[status] = order.status.name
                it[statusChangedAt] = order.statusChangedAt.utc()
                it[estimatedArrivalAt] = order.estimatedArrivalAt.utc()
            }
            OrderLinesTable.batchInsert(order.lines.withIndex()) { (index, line) ->
                this[OrderLinesTable.orderId] = order.id
                this[OrderLinesTable.position] = index
                this[OrderLinesTable.dishId] = line.dishId
                this[OrderLinesTable.nameDe] = line.name.de
                this[OrderLinesTable.nameEn] = line.name.en
                this[OrderLinesTable.unitPriceCents] = line.unitPriceCents
                this[OrderLinesTable.quantity] = line.quantity
                this[OrderLinesTable.calories] = line.calories
            }
        }
    }

    override suspend fun find(id: String): Order? = db.transaction(readOnly = true) { load { OrdersTable.id eq id }.singleOrNull() }

    override suspend fun page(
        userId: String,
        cursor: OrderCursor?,
        limit: Int,
    ): List<Order> =
        db.transaction(readOnly = true) {
            val t = OrdersTable
            load(limit) {
                val mine = t.userId eq userId
                if (cursor == null) {
                    mine
                } else {
                    mine and ((t.placedAt less cursor.placedAt.utc()) or ((t.placedAt eq cursor.placedAt.utc()) and (t.id less cursor.id)))
                }
            }
        }

    override suspend fun all(userId: String): List<Order> = db.transaction(readOnly = true) { load { OrdersTable.userId eq userId } }

    override suspend fun updateStatus(order: Order) {
        db.transaction {
            OrdersTable.update({ OrdersTable.id eq order.id }) {
                it[status] = order.status.name
                it[statusChangedAt] = order.statusChangedAt.utc()
                it[estimatedArrivalAt] = order.estimatedArrivalAt.utc()
            }
        }
    }

    override suspend fun deleteByUser(userId: String): Int = db.transaction { OrdersTable.deleteWhere { OrdersTable.userId eq userId } }

    private fun load(
        limit: Int? = null,
        where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>,
    ): List<Order> {
        val t = OrdersTable
        val rows =
            t
                .selectAll()
                .where(where)
                .orderBy(t.placedAt to SortOrder.DESC, t.id to SortOrder.DESC)
                .let { if (limit != null) it.limit(limit) else it }
                .toList()
        if (rows.isEmpty()) return emptyList()
        val lines =
            OrderLinesTable
                .selectAll()
                .where { OrderLinesTable.orderId inList rows.map { it[t.id] } }
                .orderBy(OrderLinesTable.position to SortOrder.ASC)
                .groupBy({ it[OrderLinesTable.orderId] }) {
                    OrderLine(
                        it[OrderLinesTable.dishId],
                        Text(it[OrderLinesTable.nameDe], it[OrderLinesTable.nameEn]),
                        it[OrderLinesTable.unitPriceCents],
                        it[OrderLinesTable.quantity],
                        it[OrderLinesTable.calories],
                    )
                }
        return rows.map { it.toOrder(lines[it[t.id]].orEmpty()) }
    }

    private fun ResultRow.toOrder(lines: List<OrderLine>): Order {
        val t = OrdersTable
        return Order(
            id = this[t.id],
            userId = this[t.userId],
            language = Language.parse(this[t.language]),
            restaurant =
                OrderedRestaurant(
                    this[t.restaurantId],
                    this[t.restaurantSlug],
                    this[t.restaurantName],
                    Location(this[t.pickupLatitude], this[t.pickupLongitude]),
                    this[t.preparationMinutes],
                ),
            lines = lines,
            deliveryFeeCents = this[t.deliveryFeeCents],
            dropOff = DropOff(Location(this[t.dropOffLatitude], this[t.dropOffLongitude]), this[t.dropOffLabel]),
            quotedSeconds = this[t.quotedSeconds],
            quoteSource = QuoteSource.valueOf(this[t.quoteSource]),
            placedAt = this[t.placedAt].toInstant(),
            status = OrderStatus.valueOf(this[t.status]),
            statusChangedAt = this[t.statusChangedAt].toInstant(),
            estimatedArrivalAt = this[t.estimatedArrivalAt].toInstant(),
        )
    }
}

class ExposedOrderRequests(
    private val db: PostgresDatabase,
) : OrderRequests {
    override suspend fun find(
        userId: String,
        key: String,
    ): StoredRequest? =
        db.transaction(readOnly = true) {
            OrderRequestsTable
                .selectAll()
                .where { (OrderRequestsTable.userId eq userId) and (OrderRequestsTable.idempotencyKey eq key) }
                .singleOrNull()
                ?.let { StoredRequest(it[OrderRequestsTable.requestHash], it[OrderRequestsTable.orderId]) }
        }

    override suspend fun insert(
        userId: String,
        key: String,
        request: StoredRequest,
    ) {
        try {
            db.transaction {
                OrderRequestsTable.insert {
                    it[this.userId] = userId
                    it[idempotencyKey] = key
                    it[requestHash] = request.requestHash
                    it[orderId] = request.orderId
                }
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation()) throw DuplicateRequest()
            throw e
        }
    }
}
