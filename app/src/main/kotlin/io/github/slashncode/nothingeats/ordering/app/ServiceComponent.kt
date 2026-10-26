package io.github.slashncode.nothingeats.ordering.app

import io.github.slashncode.nothingeats.ordering.adapters.grpc.GrpcDeliveryQuotes
import io.github.slashncode.nothingeats.ordering.adapters.http.OrderingRoutes
import io.github.slashncode.nothingeats.ordering.adapters.messaging.CatalogEventsHandler
import io.github.slashncode.nothingeats.ordering.adapters.messaging.DeliveryEventsHandler
import io.github.slashncode.nothingeats.ordering.adapters.messaging.IdentityEventsHandler
import io.github.slashncode.nothingeats.ordering.adapters.messaging.OrderingTopics
import io.github.slashncode.nothingeats.ordering.adapters.messaging.OutboxOrderEvents
import io.github.slashncode.nothingeats.ordering.adapters.persistence.ExposedCarts
import io.github.slashncode.nothingeats.ordering.adapters.persistence.ExposedMenu
import io.github.slashncode.nothingeats.ordering.adapters.persistence.ExposedOrderRequests
import io.github.slashncode.nothingeats.ordering.adapters.persistence.ExposedOrders
import io.github.slashncode.nothingeats.ordering.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.ordering.application.Carts
import io.github.slashncode.nothingeats.ordering.application.ChangeCart
import io.github.slashncode.nothingeats.ordering.application.ClearCart
import io.github.slashncode.nothingeats.ordering.application.DeliveryQuotes
import io.github.slashncode.nothingeats.ordering.application.ExportOrders
import io.github.slashncode.nothingeats.ordering.application.FollowDelivery
import io.github.slashncode.nothingeats.ordering.application.ForgetUser
import io.github.slashncode.nothingeats.ordering.application.GetOrder
import io.github.slashncode.nothingeats.ordering.application.ListOrders
import io.github.slashncode.nothingeats.ordering.application.Menu
import io.github.slashncode.nothingeats.ordering.application.OrderEvents
import io.github.slashncode.nothingeats.ordering.application.OrderRequests
import io.github.slashncode.nothingeats.ordering.application.Orders
import io.github.slashncode.nothingeats.ordering.application.PlaceOrder
import io.github.slashncode.nothingeats.ordering.application.ProjectMenu
import io.github.slashncode.nothingeats.ordering.application.QuoteCheckout
import io.github.slashncode.nothingeats.ordering.application.Transactions
import io.github.slashncode.nothingeats.ordering.application.ViewCart
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.grpc.GrpcChannel
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.kafka.OutboxRelay
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes, event handlers and cron tasks are collected with
 * `@IntoSet`. [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val orders: Orders

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Messaging

    @ServiceScope
    @Provides
    fun outbox(clock: Clock): Outbox = Outbox(source = OrderingTopics.SOURCE, clock = clock)

    @ServiceScope
    @Provides
    fun outboxRelay(postgres: PostgresDatabase): OutboxRelay =
        OutboxRelay(postgres, KafkaClients.producer(config.kafka, "ordering-service"))

    @IntoSet
    @Provides
    fun outboxRelayLifecycle(relay: OutboxRelay): LifecycleComponent = relay

    @ServiceScope
    @Provides
    fun eventConsumer(
        handlers: Set<EventHandler>,
        postgres: PostgresDatabase,
    ): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "ordering-service"),
            handlers = handlers,
            db = postgres,
            consumerFactory = { KafkaClients.consumer(config.kafka, "ordering-service") },
            deadLetters = KafkaClients.producer(config.kafka, "ordering-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Delivery (gRPC)

    @ServiceScope
    @Provides
    fun deliveryChannel(): GrpcChannel = GrpcChannel(config.delivery)

    @IntoSet
    @Provides
    fun deliveryChannelLifecycle(channel: GrpcChannel): LifecycleComponent = channel

    @Provides
    fun deliveryQuotes(channel: GrpcChannel): DeliveryQuotes = GrpcDeliveryQuotes(channel)

    // Ordering

    @Provides
    fun menu(postgres: PostgresDatabase): Menu = ExposedMenu(postgres)

    @Provides
    fun carts(postgres: PostgresDatabase): Carts = ExposedCarts(postgres)

    @Provides
    fun orders(postgres: PostgresDatabase): Orders = ExposedOrders(postgres)

    @Provides
    fun orderRequests(postgres: PostgresDatabase): OrderRequests = ExposedOrderRequests(postgres)

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @Provides
    fun orderEvents(outbox: Outbox): OrderEvents = OutboxOrderEvents(outbox)

    @Provides
    fun viewCart(
        carts: Carts,
        menu: Menu,
    ): ViewCart = ViewCart(carts, menu)

    @IntoSet
    @Provides
    fun orderingRoutes(
        view: ViewCart,
        carts: Carts,
        menu: Menu,
        orders: Orders,
        requests: OrderRequests,
        events: OrderEvents,
        quotes: DeliveryQuotes,
        transactions: Transactions,
        analytics: ProductAnalytics,
        clock: Clock,
    ): HttpRoutes =
        OrderingRoutes(
            viewCart = view,
            changeCart = ChangeCart(carts, menu, view, analytics),
            clearCart = ClearCart(carts),
            quoteCheckout = QuoteCheckout(view, quotes, config.checkout),
            placeOrder = PlaceOrder(view, carts, orders, requests, events, quotes, transactions, analytics, config.checkout, clock),
            listOrders = ListOrders(orders),
            getOrder = GetOrder(orders),
            exportOrders = ExportOrders(orders),
        )

    @IntoSet
    @Provides
    fun restaurantEvents(menu: Menu): EventHandler = CatalogEventsHandler(CatalogEventsHandler.RESTAURANTS, ProjectMenu(menu))

    @IntoSet
    @Provides
    fun dishEvents(menu: Menu): EventHandler = CatalogEventsHandler(CatalogEventsHandler.DISHES, ProjectMenu(menu))

    @IntoSet
    @Provides
    fun deliveryEvents(orders: Orders): EventHandler = DeliveryEventsHandler(FollowDelivery(orders))

    @IntoSet
    @Provides
    fun identityEvents(
        orders: Orders,
        carts: Carts,
        transactions: Transactions,
    ): EventHandler = IdentityEventsHandler(ForgetUser(orders, carts, transactions))
}
