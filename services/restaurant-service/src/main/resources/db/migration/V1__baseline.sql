-- restaurant-service baseline (M1.4).
--
-- Two tables:
--   * `sale`   — the aggregate (system of record for a recorded sale), carrying the
--                mandatory Auditable columns + a Money amount (amount_minor BIGINT +
--                currency CHAR(3), never a float) + a tenant-RLS policy.
--   * `outbox` — the transactional-outbox table the libs/events OutboxWriter writes
--                to; the SaleRecorded event row commits atomically with the sale.
--
-- The application connects as a NON-superuser role (without BYPASSRLS), so the RLS
-- policy is genuinely enforced; FORCE ROW LEVEL SECURITY makes the policy apply even
-- to the table owner / migration role, so no connection silently bypasses tenant
-- isolation (CLAUDE.md rule 5 — defense in depth).

-- ---------------------------------------------------------------------------
-- sale aggregate
-- ---------------------------------------------------------------------------
CREATE TABLE sale (
    id              UUID         NOT NULL PRIMARY KEY,
    business_id     UUID         NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    occurred_at     TIMESTAMPTZ  NOT NULL,

    -- The client's request id; producer-idempotency dedupe key (with company_id).
    idempotency_key TEXT         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- Producer idempotency: at most one sale per (tenant, client request id). A
    -- retried record-sale resolves to the existing row -> exactly one SaleRecorded.
    CONSTRAINT uq_sale_company_idempotency UNIQUE (company_id, idempotency_key)
);

-- Defense in depth: tenant isolation enforced a second time by the database.
ALTER TABLE sale ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role.
ALTER TABLE sale FORCE ROW LEVEL SECURITY;

-- A row is visible/writable only when its company_id equals the session tenant.
-- current_setting(..., true) returns NULL (not an error) when the GUC is unset, so
-- with no tenant bound the predicate is false for every row -> fail closed.
CREATE POLICY sale_tenant_isolation ON sale
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is
-- set by the relay (ignored by Debezium). This table is NOT Auditable and is NOT
-- under RLS: it is infrastructure the relay tails, and its rows already carry
-- company_id for downstream tenant scoping; the writer always runs inside the
-- sale's transaction, which is itself RLS-scoped.
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index
-- (WHERE published_at IS NULL) indexes only the rows the relay still has to ship,
-- so it stays tiny and the index does not bloat with every already-published row.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;
