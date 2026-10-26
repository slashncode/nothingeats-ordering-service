package io.github.slashncode.nothingeats.ordering.app

import io.github.slashncode.nothingeats.ordering.application.CheckoutSettings
import io.github.slashncode.nothingeats.platform.grpc.GrpcClientConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.persistence.PostgresConfig
import io.github.slashncode.nothingeats.platform.posthog.PostHogSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the service reads from its environment, validated at startup. Required:
 * `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`. In the cluster also `JWT__JWKS_URL`,
 * `KAFKA__BOOTSTRAP_SERVERS`, `DELIVERY__TARGET` (`dns:///delivery-service-grpc:9090`) and
 * `LOYALTY__TARGET` (`dns:///loyalty-service-grpc:9090`).
 */
data class ServiceConfig(
    val http: HttpServerConfig = HttpServerConfig(),
    val database: PostgresConfig,
    val kafka: KafkaConfig = KafkaConfig(),
    val jwt: JwtConfig = JwtConfig(),
    val posthog: PostHogSettings = PostHogSettings(),
    /** The delivery service's gRPC endpoint; checkout must not wait long for it. */
    val delivery: GrpcClientConfig = GrpcClientConfig(target = "localhost:9090", deadline = 800.milliseconds, maxAttempts = 2),
    /** The loyalty service's gRPC endpoint (coupons); a cart view must not wait long for it either. */
    val loyalty: GrpcClientConfig = GrpcClientConfig(target = "localhost:9091", deadline = 800.milliseconds, maxAttempts = 2),
    val checkout: CheckoutSettings = CheckoutSettings(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

data class LifecycleConfig(
    /** Time between readiness going down and the shutdown, so load balancers stop sending traffic first. */
    val drainDelay: Duration = 5.seconds,
)
