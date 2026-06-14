-- finance-service baseline (M1.5) — the financial core, a purely downstream consumer.
--
-- Three tables:
--   * `ledger_posting`       — the append-only dimensional ledger. One row per posted
--                              SaleRecorded: a REVENUE posting carrying the mandatory
--                              Auditable columns + a Money amount (amount_minor BIGINT +
--                              currency CHAR(3), never a float) + the period (YYYY-MM,
--                              derived from occurred_at in UTC) + source_event_id, which
--                              is UNIQUE so a re-delivered event can never double-post.
--   * `consolidated_revenue` — the consolidated-revenue read model, accumulated
--                              incrementally on each posting (company_id, period,
--                              currency -> total_minor). What the dashboard reads.
--   * `processed_event`      — the idempotent-consumer dedupe store (libs/events
--                              ProcessedEventStore): one row per handled event UUID.
--
-- finance connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely
-- enforced; FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner /
-- migration role, so no connection silently bypasses tenant isolation (rule 5 — defense
-- in depth). The CONSUMER runs each handler inside a TenantContext scope bound to the
-- event's company_id (the inbound tenant comes from the event, not a request), so the
-- auto-RLS aspect sets app.current_tenant and the WITH CHECK passes on every insert.

-- ---------------------------------------------------------------------------
-- ledger_posting (the append-only dimensional ledger)
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_posting (
    id              UUID         NOT NULL PRIMARY KEY,
    business_id     UUID         NOT NULL,

    -- The accounting period this posting falls in: YYYY-MM, derived from the source
    -- event's occurred_at in UTC. A read/aggregation dimension alongside company_id.
    period          VARCHAR(7)   NOT NULL,

    -- The posting kind. M1.5 posts only REVENUE (from SaleRecorded); kept as a column
    -- so the ledger generalises to expenses/payroll without a schema change.
    posting_type    VARCHAR(32)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    -- The id of the event that produced this posting (the consumed SaleRecorded's
    -- event UUID). UNIQUE: a second posting from a re-delivered event is impossible at
    -- the database level, the backstop behind the ProcessedEventStore dedupe (rule 3).
    source_event_id UUID         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_ledger_posting_source_event UNIQUE (source_event_id)
);

ALTER TABLE ledger_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_posting FORCE ROW LEVEL SECURITY;

CREATE POLICY ledger_posting_tenant_isolation ON ledger_posting
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The consolidated-revenue aggregation and the dashboard query both scan by period
-- within the bound tenant; index the access path.
CREATE INDEX idx_ledger_posting_period ON ledger_posting (period);

-- ---------------------------------------------------------------------------
-- consolidated_revenue (the incrementally-accumulated read model)
-- ---------------------------------------------------------------------------
-- One row per (company_id, period, currency); total_minor accumulates as each posting
-- lands. CQRS: post on write, this is the aggregate-on-read projection the query API
-- serves. It is maintained in the SAME transaction as the posting, so it is consistent
-- with the ledger. It still extends Auditable and is under RLS — it is an app-maintained
-- read model (not a Debezium-CDC-derived projection), so the rule-4 Auditable + rule-5
-- RLS guarantees apply uniformly.
CREATE TABLE consolidated_revenue (
    id              UUID         NOT NULL PRIMARY KEY,

    period          VARCHAR(7)   NOT NULL,

    -- The accumulated revenue total as Money (minor units + ISO-4217 code). NEVER float.
    total_minor     BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- At most one accumulator per tenant + period + currency. The writer upserts on
    -- this key (find-or-create then add), so a tenant's period revenue is a single row.
    CONSTRAINT uq_consolidated_revenue_company_period_currency
        UNIQUE (company_id, period, currency)
);

ALTER TABLE consolidated_revenue ENABLE ROW LEVEL SECURITY;
ALTER TABLE consolidated_revenue FORCE ROW LEVEL SECURITY;

CREATE POLICY consolidated_revenue_tenant_isolation ON consolidated_revenue
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled event UUID here and skips
-- duplicates (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered SaleRecorded is
-- processed at most once (rule 3 / HR-3). The claim runs INSIDE the same transaction
-- as the posting + read-model update, so dedupe and side effects commit together.
--
-- Not Auditable and not under RLS: it is consumer infrastructure keyed solely by the
-- globally-unique event id, not tenant-scoped business data. (It carries no company_id;
-- the event id is unique across all tenants, and gating on it before the tenant scope
-- even matters is exactly the point — a dupe is dropped regardless of tenant.)
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
