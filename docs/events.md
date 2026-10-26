# Events of the ordering service

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/ordering`,
record key and `subject` = the order id. Consumers must ignore unknown fields.

## `ordering.order.v1` (outbox, published with the order)

| `type` | When | `data` |
|---|---|---|
| `nothingeats.ordering.order.placed.v1` | Checkout | `orderId`, `userId`, `locale` (`de`/`en`), `placedAt`, `restaurant` (`id`, `slug`, `name`, `latitude`, `longitude`, `preparationMinutes`), `dropOff` (`latitude`, `longitude`, `label`), `lines` (`dishId`, `name` `{de, en}`, `quantity`, `unitPriceCents`, `calories`, `regularUnitPriceCents` — the menu price when a flash deal applied, else null), `subtotalCents`, `deliveryFeeCents`, `discountCents`, `totalCents` (after the coupon), `calories`, `quotedSeconds` (real seconds until arrival, as promised), `quoteSource` (`DELIVERY`/`ESTIMATE`), `estimatedArrivalAt`, `coupon` (`code`, or null), `analytics` |
| `nothingeats.ordering.order.delivered.v1` | The delivery arrived (after `placed`, same key) | `orderId`, `userId`, `deliveredAt`, `analytics` |

The `placed` event is a snapshot: names and prices as ordered, never looked up again. The order's
progress is the delivery service's (`delivery.delivery.v1`); ordering repeats only the arrival, so
consumers of orders (loyalty) need one topic and get `placed` before `delivered`.

`analytics` is the customer's analytics consent at checkout (the browser sent its PostHog distinct
id): consumers send server-side analytics about this order only when it is `true`.

## Consumed

- `catalog.restaurant.v1`, `catalog.dish.v1`: the menu projection (prices, minimum order, fees, opening hours, locations), versioned by offset.
- `catalog.deal.v1`: flash deals (deal price while `startsAt <= now < endsAt`); immutable, pruned a day after they end.
- `delivery.delivery.v1`: `status-changed` moves the order forward (never back).
- `identity.user.v1`: `user.deleted` erases the user's orders and cart.
