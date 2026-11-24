-- Demo catalogue for local and dev environments.
insert into stock_item (sku, available, reserved, updated_at) values
    ('SKU-1001', 500, 0, now()),
    ('SKU-1002', 250, 0, now()),
    ('SKU-1003', 100, 0, now()),
    ('SKU-1004', 10, 0, now()),
    ('SKU-1005', 0, 0, now());
