# Events of the ordering service

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/ordering`,
record key and `subject` = the order id. Consumers must ignore unknown fields.

## `ordering.order.v1` (outbox, published with the order)

| `type` | When | `data` |
|---|---|---|
| `nothingeats.ordering.order.placed.v1` | Checkout | `orderId`, `userId`, `locale` (`de`/`en`), `placedAt`, `restaurant` (`id`, `slug`, `name`, `latitude`, `longitude`, `preparationMinutes`), `dropOff` (`latitude`, `longitude`, `label`), `lines` (`dishId`, `name` `{de, en}`, `quantity`, `unitPriceCents`, `calories`), `subtotalCents`, `deliveryFeeCents`, `totalCents`, `calories`, `quotedSeconds` (real seconds until arrival, as promised), `quoteSource` (`DELIVERY`/`ESTIMATE`), `estimatedArrivalAt` |

The event is a snapshot: names and prices as ordered, never looked up again. The order's progress
after that is the delivery service's (`delivery.delivery.v1`).

## Consumed

- `catalog.restaurant.v1`, `catalog.dish.v1`: the menu projection (prices, minimum order, fees, opening hours, locations), versioned by offset.
- `delivery.delivery.v1`: `status-changed` moves the order forward (never back).
- `identity.user.v1`: `user.deleted` erases the user's orders and cart.
