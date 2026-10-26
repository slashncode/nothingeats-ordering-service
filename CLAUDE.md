# CLAUDE.md

Ordering service of NothingEats: cart, checkout (no payment), order history. Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers and the sibling checkouts `../nothingeats-delivery-service` and `../nothingeats-loyalty-service` (gRPC contracts, composite builds).
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`

## Rules

- Respect the module direction: `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- Prices always come from the menu projection; a placed order is a snapshot and never looks anything up again.
- No remote calls inside a transaction: the delivery quote and the coupon reservation are asked before, everything that must happen together (order, idempotency key, outbox event, cart) is one transaction.
- Coupons are loyalty's: the cart stores only the code and prices it on every view; never trust a stored discount.
- Server-side analytics only with consent: `Order.analytics` travels in the events; consumers check it.
- The menu projection is versioned by offset; never overwrite a newer snapshot.
- Orders only move forward (`Order.follow`); delivery events may arrive late or twice.
- `user.deleted` erases orders and the cart; `/me/export` must contain everything stored per user.
- Wiring happens only in `app/.../ServiceComponent.kt`; lifecycle components, health checks, routes, event handlers and cron tasks are contributed with `@IntoSet`.
