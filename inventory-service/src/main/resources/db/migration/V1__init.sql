-- Stock levels, one row per sku. version is the optimistic-locking column
-- Hibernate uses to detect concurrent reservation attempts on the same sku.
CREATE TABLE stock_item (
    sku      VARCHAR(64) PRIMARY KEY,
    quantity INTEGER     NOT NULL CHECK (quantity >= 0),
    version  BIGINT      NOT NULL DEFAULT 0
);

-- Transactional outbox: a row here is written in the same DB transaction as
-- the stock_item update it accompanies, so the reservation outcome and the
-- event describing it either both commit or both roll back together. A
-- separate poller (OutboxRelay) publishes unpublished rows to Kafka.
CREATE TABLE outbox_event (
    id         UUID PRIMARY KEY,
    topic      VARCHAR(128)  NOT NULL,
    event_key  VARCHAR(128)  NOT NULL,
    event_type VARCHAR(128)  NOT NULL,
    payload    JSONB         NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published  BOOLEAN       NOT NULL DEFAULT false
);

CREATE INDEX idx_outbox_event_unpublished ON outbox_event (created_at) WHERE NOT published;

-- Tracks Kafka event ids already handled, so a redelivered order.created
-- (Kafka is at-least-once) doesn't reserve stock twice for the same order.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO stock_item (sku, quantity) VALUES
    ('SKU-WIDGET', 50),
    ('SKU-GADGET', 20),
    ('SKU-GIZMO', 5);
