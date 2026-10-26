package io.github.slashncode.nothingeats.ordering.domain

import java.time.LocalTime

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts `de`, `en-GB` or an Accept-Language header; anything unknown is German. */
        fun parse(raw: String?): Language {
            val first =
                raw
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.take(2)
            return entries.firstOrNull { it.tag == first } ?: DE
        }
    }
}

data class Text(
    val de: String,
    val en: String,
) {
    fun of(language: Language) =
        when (language) {
            Language.DE -> de
            Language.EN -> en
        }
}

data class Location(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) throw InvalidOrder("location", "is not a place on earth")
    }
}

/**
 * Ordering's own copy of a restaurant, projected from `catalog.restaurant.v1`: what checkout
 * needs without calling the catalog (fees, minimum order, opening hours, location).
 */
data class MenuRestaurant(
    val id: String,
    val slug: String,
    val name: String,
    val location: Location,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val opensAt: LocalTime,
    val closesAt: LocalTime,
) {
    /** Opening hours may run past midnight (18:00–02:00), like in the catalog. */
    fun isOpenAt(time: LocalTime): Boolean =
        if (opensAt <= closesAt) time >= opensAt && time < closesAt else time >= opensAt || time < closesAt

    /** The kitchen's part of the advertised delivery time. */
    val preparationMinutes: Int get() = (deliveryMinutes * PREPARATION_SHARE).toInt()

    private companion object {
        const val PREPARATION_SHARE = 0.5
    }
}

/** A dish as ordering knows it, projected from `catalog.dish.v1`. */
data class MenuDish(
    val id: String,
    val restaurantId: String,
    val name: Text,
    val priceCents: Long,
    val calories: Int,
    val available: Boolean,
)
