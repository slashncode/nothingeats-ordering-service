-- Ordering's copy of the catalog (projected from catalog.restaurant.v1 / catalog.dish.v1).
-- version is the record's offset: an older snapshot never overwrites a newer one.
create table menu_restaurants
(
    id                  text primary key,
    slug                text             not null,
    name                text             not null,
    latitude            double precision not null,
    longitude           double precision not null,
    delivery_minutes    integer          not null,
    delivery_fee_cents  bigint           not null,
    minimum_order_cents bigint           not null,
    opens_at            time             not null,
    closes_at           time             not null,
    version             bigint           not null
);

create table menu_dishes
(
    id            text primary key,
    restaurant_id text    not null,
    name_de       text    not null,
    name_en       text    not null,
    price_cents   bigint  not null,
    calories      integer not null,
    available     boolean not null,
    version       bigint  not null
);

create table cart_lines
(
    user_id       text        not null,
    dish_id       text        not null,
    restaurant_id text        not null,
    quantity      integer     not null check (quantity between 1 and 20),
    added_at      timestamptz not null,
    primary key (user_id, dish_id)
);

create table orders
(
    id                   text primary key,
    user_id              text             not null,
    language             text             not null,
    restaurant_id        text             not null,
    restaurant_slug      text             not null,
    restaurant_name      text             not null,
    pickup_latitude      double precision not null,
    pickup_longitude     double precision not null,
    preparation_minutes  integer          not null,
    delivery_fee_cents   bigint           not null,
    drop_off_latitude    double precision not null,
    drop_off_longitude   double precision not null,
    drop_off_label       text,
    quoted_seconds       integer          not null,
    quote_source         text             not null,
    placed_at            timestamptz      not null,
    status               text             not null,
    status_changed_at    timestamptz      not null,
    estimated_arrival_at timestamptz      not null
);

create index orders_user_placed on orders (user_id, placed_at desc, id desc);

create table order_lines
(
    order_id         text    not null references orders (id) on delete cascade,
    position         integer not null,
    dish_id          text    not null,
    name_de          text    not null,
    name_en          text    not null,
    unit_price_cents bigint  not null,
    quantity         integer not null,
    calories         integer not null,
    primary key (order_id, position)
);

-- Idempotency keys of POST /orders, per user.
create table order_requests
(
    user_id         text        not null,
    idempotency_key text        not null,
    request_hash    text        not null,
    order_id        text        not null references orders (id) on delete cascade,
    created_at      timestamptz not null default now(),
    primary key (user_id, idempotency_key)
);
