package io.github.slashncode.nothingeats.ordering.adapters.grpc

import io.github.slashncode.nothingeats.loyalty.grpc.v1.Coupon
import io.github.slashncode.nothingeats.loyalty.grpc.v1.LoyaltyServiceGrpcKt
import io.github.slashncode.nothingeats.loyalty.grpc.v1.couponCart
import io.github.slashncode.nothingeats.loyalty.grpc.v1.priceCouponRequest
import io.github.slashncode.nothingeats.loyalty.grpc.v1.redeemCouponRequest
import io.github.slashncode.nothingeats.ordering.application.CouponCartInput
import io.github.slashncode.nothingeats.ordering.application.CouponOffer
import io.github.slashncode.nothingeats.ordering.application.LoyaltyCoupons
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.grpc.GrpcChannel
import io.github.slashncode.nothingeats.platform.grpc.errorCode
import io.grpc.Status
import io.grpc.StatusException
import org.slf4j.LoggerFactory

/**
 * Coupons live in loyalty; ordering asks on the customer's behalf (their token travels along, so
 * only their own coupons exist). A refusal comes back as the customer's problem with loyalty's
 * code; no answer in time means null, and the caller decides (cart: "cannot check", checkout: 503).
 */
class GrpcLoyaltyCoupons(
    val channel: GrpcChannel,
) : LoyaltyCoupons {
    private val log = LoggerFactory.getLogger(GrpcLoyaltyCoupons::class.java)
    private val stub = LoyaltyServiceGrpcKt.LoyaltyServiceCoroutineStub(channel.channel)

    override suspend fun price(
        code: String,
        cart: CouponCartInput,
        accessToken: String,
    ): CouponOffer? =
        call {
            val response =
                channel.prepare(stub, accessToken).priceCoupon(
                    priceCouponRequest {
                        this.code = code
                        this.cart = cart.message()
                    },
                )
            response.coupon.offer(response.discountCents)
        }

    override suspend fun redeem(
        code: String,
        reservationId: String,
        cart: CouponCartInput,
        accessToken: String,
    ): CouponOffer? =
        call {
            val response =
                channel.prepare(stub, accessToken).redeemCoupon(
                    redeemCouponRequest {
                        this.code = code
                        this.reservationId = reservationId
                        this.cart = cart.message()
                    },
                )
            response.coupon.offer(response.discountCents)
        }

    private suspend fun call(block: suspend () -> CouponOffer): CouponOffer? =
        try {
            block()
        } catch (e: StatusException) {
            val code = e.errorCode()
            when {
                code != null && code.startsWith("COUPON_") -> throw refusal(code, e.status)
                else -> {
                    log.warn("Loyalty did not answer ({}); the coupon cannot be checked", e.status.code)
                    null
                }
            }
        }

    private fun refusal(
        code: String,
        status: Status,
    ): AppError =
        when (status.code) {
            Status.Code.NOT_FOUND -> AppError.NotFound(ErrorCode(code), "There is no such coupon")
            Status.Code.INVALID_ARGUMENT -> AppError.Invalid(ErrorCode(code), "The coupon does not apply to this cart")
            else -> AppError.Conflict(ErrorCode(code), "The coupon cannot be used: ${status.description.orEmpty()}")
        }

    private fun CouponCartInput.message() =
        couponCart {
            restaurantId = this@message.restaurantId
            subtotalCents = this@message.subtotalCents
            deliveryFeeCents = this@message.deliveryFeeCents
            unitPricesCents += this@message.unitPricesCents
        }

    private fun Coupon.offer(discount: Long) = CouponOffer(code, kind, value, maxDiscountCents.takeIf { it > 0 }, discount)
}
