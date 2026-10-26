package io.github.slashncode.nothingeats.ordering.adapters.grpc

import io.github.slashncode.nothingeats.delivery.grpc.v1.DeliveryServiceGrpcKt
import io.github.slashncode.nothingeats.delivery.grpc.v1.geoPoint
import io.github.slashncode.nothingeats.delivery.grpc.v1.quoteDeliveryRequest
import io.github.slashncode.nothingeats.ordering.application.DeliveryQuote
import io.github.slashncode.nothingeats.ordering.application.DeliveryQuotes
import io.github.slashncode.nothingeats.ordering.domain.Location
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.grpc.GrpcChannel
import io.github.slashncode.nothingeats.platform.grpc.errorCode
import io.grpc.StatusException
import org.slf4j.LoggerFactory

/**
 * The first gRPC edge: ordering asks delivery on the customer's behalf (their token travels
 * along). Out of range is the customer's problem and comes back as 422; anything else (deadline,
 * delivery down) means checkout goes on with the restaurant's own estimate.
 */
class GrpcDeliveryQuotes(
    val channel: GrpcChannel,
) : DeliveryQuotes {
    private val log = LoggerFactory.getLogger(GrpcDeliveryQuotes::class.java)
    private val stub = DeliveryServiceGrpcKt.DeliveryServiceCoroutineStub(channel.channel)

    override suspend fun quote(
        pickup: Location,
        dropOff: Location,
        preparationMinutes: Int,
        accessToken: String,
    ): DeliveryQuote? =
        try {
            val response =
                channel.prepare(stub, accessToken).quoteDelivery(
                    quoteDeliveryRequest {
                        this.pickup =
                            geoPoint {
                                latitude = pickup.latitude
                                longitude = pickup.longitude
                            }
                        this.dropOff =
                            geoPoint {
                                latitude = dropOff.latitude
                                longitude = dropOff.longitude
                            }
                        this.preparationMinutes = preparationMinutes
                    },
                )
            DeliveryQuote(response.etaSeconds, response.simulatedMinutes, response.distanceMeters, response.courierShortage)
        } catch (e: StatusException) {
            if (e.errorCode() == OUT_OF_RANGE) {
                throw AppError.Invalid(
                    ErrorCode(OUT_OF_RANGE),
                    "The drop-off is too far away, even for nothing",
                    listOf(Violation("dropOff", "too far away")),
                )
            }
            log.warn("No delivery quote ({}); using the restaurant's estimate", e.status.code)
            null
        }

    private companion object {
        const val OUT_OF_RANGE = "DELIVERY_OUT_OF_RANGE"
    }
}
