-- Flash deals, projected from catalog.deal.v1. Deals never change once published; they end.
create table menu_deals
(
    id          text primary key,
    dish_id     text        not null,
    price_cents bigint      not null,
    percent_off integer     not null,
    starts_at   timestamptz not null,
    ends_at     timestamptz not null
);

create index menu_deals_dish on menu_deals (dish_id, ends_at);

-- The coupon a customer put in their cart; loyalty prices it on every look at the cart.
create table cart_coupons
(
    user_id text primary key,
    code    text not null
);

-- What the coupon took off, and whether the customer had agreed to analytics at checkout
-- (it travels with the order's events, so consumers send server-side analytics only then).
alter table orders
    add column discount_cents bigint  not null default 0,
    add column coupon_code    text,
    add column analytics      boolean not null default false;

-- The menu price of a dish bought on a flash deal.
alter table order_lines
    add column regular_unit_price_cents bigint;
