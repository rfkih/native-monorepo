-- Outbox + idempotency DDL for tests.
--
-- In production these tables live in each service's own schema (database-per-service)
-- and are created via that service's Flyway migrations. This script exists so the
-- libs/events plumbing can be exercised against H2 (PostgreSQL compatibility mode) and
-- against a real PostgreSQL 16 instance with identical types.
--
-- The `outbox` columns match OutboxRecord. `published_at` is consumed by StubRelay (and by
-- a real Debezium setup it is simply ignored). NOTE: row-level security and the Auditable
-- columns are applied by the owning service's migration, not here — this DDL only covers the
-- two infrastructure tables this library reads and writes.

CREATE TABLE IF NOT EXISTS outbox (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE NULL
);

-- The relay polls for unpublished rows in occurrence order.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox (occurred_at, id);

CREATE TABLE IF NOT EXISTS processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
