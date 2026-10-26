package io.github.slashncode.nothingeats.ordering.app

import io.github.slashncode.nothingeats.delivery.grpc.v1.DeliveryServiceGrpcKt
import io.github.slashncode.nothingeats.delivery.grpc.v1.QuoteDeliveryRequest
import io.github.slashncode.nothingeats.delivery.grpc.v1.QuoteDeliveryResponse
import io.github.slashncode.nothingeats.delivery.grpc.v1.quoteDeliveryResponse
import io.github.slashncode.nothingeats.platform.grpc.AuthInterceptor
import io.github.slashncode.nothingeats.platform.grpc.GrpcCaller
import io.github.slashncode.nothingeats.platform.grpc.GrpcClientConfig
import io.github.slashncode.nothingeats.platform.grpc.GrpcServer
import io.github.slashncode.nothingeats.platform.grpc.GrpcServerConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

private val restaurantId = UUID.randomUUID().toString()
private val margheritaId = UUID.randomUUID().toString()
private val tiramisuId = UUID.randomUUID().toString()

private fun event(
    type: String,
    subject: String,
    data: JsonObject,
) = CloudEvent(
    id = UUID.randomUUID().toString(),
    source = "/test",
    type = type,
    subject = subject,
    time = "2026-10-26T12:00:00Z",
    data = data,
)

private fun Producer<String, ByteArray>.publish(
    topic: String,
    event: CloudEvent,
) {
    send(ProducerRecord(topic, event.subject, EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray())).get()
}

private fun dish(
    id: String,
    de: String,
    en: String,
    price: Long,
) = event(
    "nothingeats.catalog.dish.published.v1",
    id,
    buildJsonObject {
        put("dishId", id)
        put("restaurantId", restaurantId)
        put("restaurantSlug", "phantom-pizza")
        putJsonObject("name") {
            put("de", de)
            put("en", en)
        }
        put("priceCents", price)
        put("calories", 800)
        put("available", true)
    },
)

private val restaurant =
    event(
        "nothingeats.catalog.restaurant.published.v1",
        restaurantId,
        buildJsonObject {
            put("restaurantId", restaurantId)
            put("slug", "phantom-pizza")
            put("name", "Phantom Pizza")
            put("latitude", 53.5503)
            put("longitude", 9.9667)
            put("deliveryMinutes", 30)
            put("deliveryFeeCents", 199)
            put("minimumOrderCents", 1200)
            // Open around the clock, so the test does not depend on the time of day.
            put("opensAt", "00:00")
            put("closesAt", "23:59")
        },
    )

/** The delivery service as far as ordering can tell: the real contract, a fixed answer, and who asked. */
private class FakeDelivery : DeliveryServiceGrpcKt.DeliveryServiceCoroutineImplBase() {
    val callers = CopyOnWriteArrayList<String>()

    override suspend fun quoteDelivery(request: QuoteDeliveryRequest): QuoteDeliveryResponse {
        callers += GrpcCaller.current().id
        return quoteDeliveryResponse {
            etaSeconds = 150
            simulatedMinutes = 25
            distanceMeters = 2_300
        }
    }
}

private fun placedOrders(): List<CloudEvent> =
    KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KafkaTestCluster.bootstrapServers,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        ByteArrayDeserializer(),
    ).use { consumer ->
        val partitions = consumer.partitionsFor("ordering.order.v1").map { TopicPartition("ordering.order.v1", it.partition()) }
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val end = consumer.endOffsets(partitions)
        val events = mutableListOf<CloudEvent>()
        while (partitions.any { consumer.position(it) < end.getValue(it) }) {
            consumer.poll(Duration.ofMillis(200)).forEach {
                events +=
                    EventJson.decodeFromString(CloudEvent.serializer(), it.value().decodeToString())
            }
        }
        events
    }

private fun statusChanged(
    orderId: String,
    status: String,
) = event(
    "nothingeats.delivery.delivery.status-changed.v1",
    orderId,
    buildJsonObject {
        put("orderId", orderId)
        put("userId", "ghost-1")
        put("status", status)
        put("at", "2026-10-26T12:01:00Z")
        put("estimatedArrivalAt", "2026-10-26T12:03:00Z")
    },
)

/** Catalog in, cart, quote over gRPC, checkout, order event out, delivery status back — through the real composition root. */
class OrderingFlowTest :
    FunSpec({
        test("from menu to placed order to delivered, idempotent, with a fallback when delivery is down") {
            KafkaTestCluster.createTopics(
                "catalog.restaurant.v1",
                "catalog.dish.v1",
                "delivery.delivery.v1",
                "identity.user.v1",
                "ordering.order.v1",
                "ordering-service.dlq",
            )
            val tokens = TestTokens()
            val delivery = FakeDelivery()
            val grpcPort = freePort()
            val deliveryServer = GrpcServer(GrpcServerConfig(port = grpcPort), setOf(delivery), AuthInterceptor(tokens.jwtAuth))
            deliveryServer.onReady()

            val port = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    delivery = GrpcClientConfig(target = "localhost:$grpcPort", deadline = 2.seconds, maxAttempts = 1),
                    lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                )
            // A failing setup must not leave the fake server running: its threads would keep the test JVM alive.
            val service =
                try {
                    ServiceComponent::class.create(config, tokens.jwtAuth).also {
                        it.migrations.migrate()
                        it.lifecycle.start()
                    }
                } catch (e: Throwable) {
                    deliveryServer.stop()
                    throw e
                }
            val http = HttpClient.newHttpClient()
            val token = tokens.accessToken(userId = "ghost-1", locale = "en")
            val base = "http://localhost:$port/api/ordering/v1"

            fun call(
                method: String,
                path: String,
                body: String? = null,
                headers: Map<String, String> = emptyMap(),
            ): HttpResponse<String> {
                val request =
                    HttpRequest
                        .newBuilder(URI("$base$path"))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                        .build()
                return http.send(request, BodyHandlers.ofString())
            }

            fun json(response: HttpResponse<String>) = Json.parseToJsonElement(response.body()).jsonObject

            fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

            try {
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish("catalog.restaurant.v1", restaurant)
                    producer.publish("catalog.dish.v1", dish(margheritaId, "Margherita des Nichts", "Margherita of Nothing", 1090))
                    producer.publish("catalog.dish.v1", dish(tiramisuId, "Tiramisu von morgen", "Tiramisu of Tomorrow", 650))
                    // A newer snapshot of the same dish (higher offset): its price wins.
                    producer.publish("catalog.dish.v1", dish(margheritaId, "Margherita des Nichts", "Margherita of Nothing", 1190))
                }

                eventually(20.seconds) {
                    call("PUT", "/cart/items/$margheritaId", """{"quantity":2}""").statusCode() shouldBe 200
                    json(call("GET", "/cart")).text("subtotalCents") shouldBe "2380"
                }
                val cart = json(call("PUT", "/cart/items/$tiramisuId?lang=en", """{"quantity":1}"""))
                cart.text("subtotalCents") shouldBe (2 * 1190 + 650).toString()
                cart.text("orderable") shouldBe "true"

                val quote = json(call("POST", "/quotes", """{"dropOff":{"latitude":53.5636,"longitude":9.9654}}"""))
                quote.text("source") shouldBe "delivery"
                delivery.callers.single() shouldBe "ghost-1"

                val checkout = """{"dropOff":{"latitude":53.5636,"longitude":9.9654,"label":"Schanze"}}"""
                val placed = call("POST", "/orders?lang=en", checkout, mapOf("Idempotency-Key" to "checkout-1"))
                placed.statusCode() shouldBe 201
                val order = json(placed)
                val orderId = order.text("id")
                order
                    .getValue("lines")
                    .jsonArray
                    .first()
                    .jsonObject
                    .text("name") shouldBe "Margherita of Nothing"
                order.text("totalCents") shouldBe (2 * 1190 + 650 + 199).toString()

                val again = call("POST", "/orders", checkout, mapOf("Idempotency-Key" to "checkout-1"))
                again.statusCode() shouldBe 200
                again.headers().firstValue("Idempotent-Replayed").orElse("") shouldBe "true"
                json(again).text("id") shouldBe orderId
                json(call("GET", "/cart")).text("itemCount") shouldBe "0"

                eventually(20.seconds) {
                    val event = placedOrders().single { it.subject == orderId }
                    event.data.jsonObject.text("quotedSeconds") shouldBe "150"
                    event.data.jsonObject.text("locale") shouldBe "en"
                }

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    // EN_ROUTE arrives after DELIVERED; the order must not go back.
                    listOf("ACCEPTED", "COOKING", "DELIVERED", "EN_ROUTE").forEach {
                        producer.publish("delivery.delivery.v1", statusChanged(orderId, it))
                    }
                }
                eventually(20.seconds) { json(call("GET", "/orders/$orderId")).text("status") shouldBe "DELIVERED" }
                call("GET", "/orders").body() shouldContain orderId

                // Delivery down: checkout still works, with the restaurant's own estimate.
                deliveryServer.stop()
                call("PUT", "/cart/items/$margheritaId", """{"quantity":2}""").statusCode() shouldBe 200
                json(call("POST", "/quotes", """{"dropOff":{"latitude":53.5636,"longitude":9.9654}}""")).text("source") shouldBe "estimate"
                call("POST", "/orders", checkout, mapOf("Idempotency-Key" to "checkout-2")).statusCode() shouldBe 201

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "identity.user.v1",
                        event("nothingeats.identity.user.deleted.v1", "ghost-1", buildJsonObject { put("userId", "ghost-1") }),
                    )
                }
                eventually(20.seconds) { service.orders.all("ghost-1") shouldBe emptyList() }
            } finally {
                service.lifecycle.stop()
                deliveryServer.stop()
            }
        }
    })
