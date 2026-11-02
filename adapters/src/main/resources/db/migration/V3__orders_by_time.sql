-- The backoffice lists every customer's orders, newest first.
create index orders_placed on orders (placed_at desc, id desc);
