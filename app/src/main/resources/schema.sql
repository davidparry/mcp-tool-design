CREATE TABLE product (
    id              VARCHAR(8)   PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    category        VARCHAR(20)  NOT NULL,
    return_category VARCHAR(20)  NOT NULL
);

CREATE TABLE variant (
    sku        VARCHAR(20)   PRIMARY KEY,
    product_id VARCHAR(8)    NOT NULL REFERENCES product (id),
    size       VARCHAR(10),
    color      VARCHAR(20),
    price      DECIMAL(10, 2) NOT NULL,
    sort_order INT           NOT NULL
);

CREATE TABLE warehouse (
    id     VARCHAR(10) PRIMARY KEY,
    name   VARCHAR(50) NOT NULL,
    city   VARCHAR(50) NOT NULL,
    state  CHAR(2)     NOT NULL,
    region VARCHAR(20) NOT NULL
);

CREATE TABLE delivery_zone (
    postal_prefix CHAR(1)     PRIMARY KEY,
    region        VARCHAR(20) NOT NULL
);

CREATE TABLE delivery_rate (
    warehouse_id  VARCHAR(10)    NOT NULL REFERENCES warehouse (id),
    region        VARCHAR(20)    NOT NULL,
    method        VARCHAR(10)    NOT NULL,
    business_days INT            NOT NULL,
    charge        DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (warehouse_id, region, method)
);

CREATE TABLE return_policy (
    return_category        VARCHAR(20)    PRIMARY KEY,
    description            VARCHAR(200)   NOT NULL,
    returnable             BOOLEAN        NOT NULL,
    exchangeable           BOOLEAN        NOT NULL,
    return_window_days     INT            NOT NULL,
    exchange_window_days   INT            NOT NULL,
    return_shipping_fee    DECIMAL(10, 2) NOT NULL,
    restocking_fee_percent INT            NOT NULL
);

CREATE TABLE customer_order (
    reference          VARCHAR(10)              PRIMARY KEY,
    customer_name      VARCHAR(100)             NOT NULL,
    customer_email     VARCHAR(100)             NOT NULL,
    ship_line1         VARCHAR(100)             NOT NULL,
    ship_city          VARCHAR(50)              NOT NULL,
    ship_state         CHAR(2)                  NOT NULL,
    ship_postal_code   VARCHAR(10)              NOT NULL,
    status             VARCHAR(12)              NOT NULL,
    placed_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    shipped_on         DATE,
    delivered_on       DATE,
    estimated_delivery DATE,
    shipping_charge    DECIMAL(10, 2)           NOT NULL
);

CREATE TABLE order_line (
    item_id            VARCHAR(16)    PRIMARY KEY,
    order_reference    VARCHAR(10)    NOT NULL REFERENCES customer_order (reference),
    line_no            INT            NOT NULL,
    sku                VARCHAR(20)    NOT NULL REFERENCES variant (sku),
    quantity           INT            NOT NULL,
    unit_price         DECIMAL(10, 2) NOT NULL,
    returned_quantity  INT            NOT NULL DEFAULT 0,
    exchanged_quantity INT            NOT NULL DEFAULT 0
);

CREATE TABLE inventory (
    sku          VARCHAR(20) NOT NULL REFERENCES variant (sku),
    warehouse_id VARCHAR(10) NOT NULL REFERENCES warehouse (id),
    available    INT         NOT NULL,
    PRIMARY KEY (sku, warehouse_id)
);

CREATE TABLE quote (
    quote_id            VARCHAR(16)              PRIMARY KEY,
    type                VARCHAR(10)              NOT NULL,
    order_reference     VARCHAR(10)              NOT NULL,
    item_id             VARCHAR(16)              NOT NULL,
    sku                 VARCHAR(20)              NOT NULL,
    replacement_sku     VARCHAR(20),
    quantity            INT                      NOT NULL,
    warehouse_id        VARCHAR(10),
    delivery_method     VARCHAR(10),
    estimated_arrival   DATE,
    price_difference    DECIMAL(10, 2),
    shipping_charge     DECIMAL(10, 2),
    item_refund         DECIMAL(10, 2),
    return_shipping_fee DECIMAL(10, 2),
    restocking_fee      DECIMAL(10, 2),
    total               DECIMAL(10, 2)           NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(10)              NOT NULL
);

CREATE TABLE committed_action (
    action_id         VARCHAR(16)              PRIMARY KEY,
    type              VARCHAR(10)              NOT NULL,
    quote_id          VARCHAR(16)              NOT NULL UNIQUE REFERENCES quote (quote_id),
    order_reference   VARCHAR(10)              NOT NULL,
    item_id           VARCHAR(16)              NOT NULL,
    sku               VARCHAR(20)              NOT NULL,
    replacement_sku   VARCHAR(20),
    quantity          INT                      NOT NULL,
    warehouse_id      VARCHAR(10),
    delivery_method   VARCHAR(10),
    estimated_arrival DATE,
    amount            DECIMAL(10, 2)           NOT NULL,
    status            VARCHAR(12)              NOT NULL,
    submitted_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
