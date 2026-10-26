# nothingeats-ordering-service

Ordering in [NothingEats](https://github.com/slashncode/nothingeats): the cart, checkout without payment ("pay with willpower") and the order history. Everything is ordered; nothing is charged, nothing arrives.

## API (`/api/ordering/v1`, signed-in customers)

| Method | Path | What |
|---|---|---|
| `GET` | `/cart` | The cart with current menu prices, fee, totals, calories and what blocks an order (`DISH_UNAVAILABLE`, `MINIMUM_NOT_REACHED`, `RESTAURANT_CLOSED`) |
| `PUT` | `/cart/items/{dishId}` | `{quantity, replaceCart}`; 0 removes. One restaurant per cart: another one is `409 CART_OTHER_RESTAURANT` unless `replaceCart` |
| `DELETE` | `/cart` | Empty the cart |
| `POST` | `/quotes` | `{dropOff}` → arrival time from the delivery service (gRPC), or the restaurant's estimate when it does not answer |
| `POST` | `/orders` | `{dropOff: {latitude, longitude, label}}` with an `Idempotency-Key` header: `201` and the order; the same key again answers `200` with `Idempotent-Replayed: true`; the same key with a different request is `422 IDEMPOTENCY_KEY_REUSED` |
| `GET` | `/orders?cursor=&limit=` | History, newest first |
| `GET` | `/orders/{id}` | One order (others' orders are `404`) |
| `GET` | `/me/export` | Everything held about the user (GDPR) |

Names in `?lang=de|en` or Accept-Language. PostHog server events: `dish_added_to_cart`, `order_placed` (`money_not_spent_cents`, `calories_avoided`, quote source).

## How it works

- **Menu projection**: ordering keeps its own copy of restaurants and dishes from the catalog's compacted topics, versioned by offset. Checkout never calls the catalog.
- **Checkout**: the delivery quote is asked over gRPC (`nothingeats.delivery.v1.DeliveryService/QuoteDelivery`, the customer's token travels along, 800 ms deadline) before the transaction; if delivery does not answer, the restaurant's advertised time stands in. Order, idempotency key, outbox event (`ordering.order.v1`) and the emptied cart are one transaction; a concurrent request with the same key loses on the key's primary key and answers with the winner's order.
- **Status**: the order follows `delivery.delivery.v1`, forward only.

Events: [docs/events.md](docs/events.md). The gRPC contract comes from the sibling checkout `../nothingeats-delivery-service` as a composite build (CI clones it).

## Run

```bash
./gradlew build                     # tests need Docker (Postgres, Kafka via Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/ordering DATABASE__USER=ordering DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate
./gradlew :app:run                  # DELIVERY__TARGET=localhost:9090 by default
```

## License

MIT
