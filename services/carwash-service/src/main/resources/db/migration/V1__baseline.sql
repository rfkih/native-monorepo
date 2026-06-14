-- carwash-service baseline (#20) — the 2nd vertical: system of record for car-wash
-- operations, and the second SaleRecorded producer so finance consolidates carwash revenue
-- alongside restaurant.
--
-- Tables:
--   * wash                  — the aggregate (system of record for a recorded wash), carrying the
--                             mandatory Auditable columns + a Money amount (amount_minor BIGINT +
--                             currency CHAR(3), never a float), the carwash outlet business_id,
--                             the bay, optional upsell info, occurred_at, and an idempotency_key
--                             with a UNIQUE (company_id, idempotency_key) — so a retried
--                             record-wash resolves to the same row (exactly one SaleRecorded).
--   * entitlement_projection — the LOCAL entitlement read model: (company_id, module_key) ->
--                             entitled bool. An app-maintained projection kept current by the
--                             EntitlementGranted / EntitlementRevoked consumer; it backs the
--                             DB-backed EntitlementLoader the shared libs/entitlement-check cache
--                             consults on a miss. record-wash is gated on the "carwash" module.
--   * staff                 — the LOCAL staff read model: employee_id -> {org_unit_id, active}.
--                             An app-maintained projection kept current by the EmployeeChanged /
--                             AssignmentChanged consumer, so the vertical knows its staff.
--   * outbox                — the transactional-outbox table the libs/events OutboxWriter writes
--                             to; the SaleRecorded + MetricPublished rows commit atomically with
--                             the wash (rule 3).
--   * processed_event       — the idempotent-consumer dedupe store (libs/events
--                             ProcessedEventStore): one row per handled event UUID, so a
--                             re-delivered entitlement / staff event is applied at most once.
--
-- The app connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely enforced;
-- FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner / migration role, so no
-- connection silently bypasses tenant isolation (CLAUDE.md rule 5 — defense in depth).
-- current_setting('app.current_tenant', true) returns NULL when the GUC is unset, so with no
-- tenant bound every policy predicate is false -> fail closed.

-- ---------------------------------------------------------------------------
-- wash aggregate
-- ---------------------------------------------------------------------------
CREATE TABLE wash (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The carwash OUTLET (an org_unit) this wash was recorded at; the SaleRecorded business_id.
    business_id     UUID         NOT NULL,

    -- The wash bay it ran on (free text in this slice, e.g. "bay-1").
    bay             VARCHAR(64)  NOT NULL,

    -- Optional upsell info: the upsell name (e.g. "wax", "interior-clean") and its amount in
    -- minor units. Both NULL when the wash had no upsell. The upsell amount shares the wash's
    -- currency (one transaction currency per wash) and feeds the upsell_amount metric.
    upsell_name         VARCHAR(128) NULL,
    upsell_amount_minor BIGINT       NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float. This is the wash
    -- total (wash + any upsell), the amount on SaleRecorded.
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

    -- Producer idempotency: at most one wash per (tenant, client request id). A retried
    -- record-wash resolves to the existing row -> exactly one SaleRecorded + one MetricPublished.
    CONSTRAINT uq_wash_company_idempotency UNIQUE (company_id, idempotency_key)
);

ALTER TABLE wash ENABLE ROW LEVEL SECURITY;
ALTER TABLE wash FORCE ROW LEVEL SECURITY;

CREATE POLICY wash_tenant_isolation ON wash
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- entitlement_projection (the LOCAL entitlement read model)
-- ---------------------------------------------------------------------------
-- (company_id, module_key) -> entitled bool. Maintained by the EntitlementGranted / Revoked
-- consumer (idempotent via processed_event). It backs the DB-backed EntitlementLoader the shared
-- libs/entitlement-check cache consults on a miss; record-wash is gated on the "carwash" module.
--
-- Like the other app-maintained read models in the codebase (finance's consolidated_revenue,
-- employee's org_unit_projection), it is Auditable + RLS (NOT a Debezium-CDC-derived projection),
-- so the rule-4 Auditable + rule-5 RLS guarantees apply uniformly. The consumer writes it inside a
-- TenantContext scope bound to the EVENT's company_id, so the WITH CHECK passes.
CREATE TABLE entitlement_projection (
    id          UUID         NOT NULL PRIMARY KEY,

    -- The module this projection row is for (e.g. "carwash").
    module_key  VARCHAR(64)  NOT NULL,

    -- Whether the company is currently entitled to the module (true on Granted, false on Revoked).
    entitled    BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- At most one projection row per company + module; the consumer upserts this key.
    CONSTRAINT uq_entitlement_projection_company_module UNIQUE (company_id, module_key)
);

ALTER TABLE entitlement_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY entitlement_projection_tenant_isolation ON entitlement_projection
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- staff (the LOCAL staff read model)
-- ---------------------------------------------------------------------------
-- employee_id -> {org_unit_id, active}. Maintained by the EmployeeChanged / AssignmentChanged
-- consumer (idempotent via processed_event) so the vertical knows its staff (rule 2 — a cached
-- read model, never a sync call). EmployeeChanged carries the employee + status (active);
-- AssignmentChanged carries the org_unit the employee is assigned to. NO PII is ever projected
-- here — the events carry only employee_id / company_id / status / org_unit dimensions (rule 6).
--
-- App-maintained read model -> Auditable + RLS (NOT a Debezium-CDC-derived projection). The
-- consumer writes it inside a TenantContext scope bound to the EVENT's company_id.
CREATE TABLE staff (
    employee_id   UUID         NOT NULL PRIMARY KEY,

    -- The org_unit the employee is currently assigned to (from AssignmentChanged), or NULL until an
    -- assignment has been seen (EmployeeChanged may arrive first).
    org_unit_id   UUID         NULL,

    -- Whether the employee is ACTIVE (from EmployeeChanged status). Defaults true on a first
    -- assignment-only sighting; corrected by the EmployeeChanged replay.
    active        BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255) NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    VARCHAR(64)  NOT NULL
);

ALTER TABLE staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_tenant_isolation ON staff
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is set by the relay
-- (ignored by Debezium). NOT Auditable and NOT under RLS: it is infrastructure the relay tails, its
-- rows already carry company_id for downstream tenant scoping, and the writer always runs inside
-- the wash's RLS-scoped transaction. PII is NEVER written to a payload or header (rule 6).
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

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled event UUID here and skips duplicates
-- (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered EntitlementGranted/Revoked or
-- EmployeeChanged/AssignmentChanged is applied at most once (rule 3). The claim runs INSIDE the
-- same transaction as the projection upsert, so dedupe and side effects commit together. Not
-- Auditable and not under RLS: keyed solely by the globally-unique event id, not tenant-scoped data.
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
