package io.github.slashncode.nothingeats.ordering.application

import io.github.slashncode.nothingeats.ordering.domain.InvalidOrder
import io.github.slashncode.nothingeats.ordering.domain.OtherRestaurant
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation

internal inline fun <T> validated(block: () -> T): T =
    try {
        block()
    } catch (e: InvalidOrder) {
        throw invalid(e.field, e.message ?: "invalid")
    } catch (e: OtherRestaurant) {
        throw AppError.Conflict(
            ErrorCode("CART_OTHER_RESTAURANT"),
            "The cart holds dishes of another restaurant; start a new cart to add this one",
        )
    }

internal fun invalid(
    field: String,
    reason: String,
) = AppError.Invalid(ErrorCode("ORDERING_INVALID"), "The request is invalid", listOf(Violation(field, reason)))

internal fun orderNotFound() = AppError.NotFound(ErrorCode("ORDER_NOT_FOUND"), "There is no such order")
