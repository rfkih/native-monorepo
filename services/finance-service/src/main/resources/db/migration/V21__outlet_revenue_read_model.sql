-- finance-service V21 — per-outlet net-revenue read model.
--
-- `outlet_revenue` is an incrementally-accumulated read model keyed on
-- (company_id, business_id, period, currency). It is fed by the same
-- SaleRecorded.business_id dimension that ledger_posting already captures, and is
-- unwound by the void/refund reversal path (RevenuePostingWriter) so the accumulator
-- always reflects net-settled revenue.
--
-- This is a DERIVED read model, not a primary source of truth.
--   * Rule 4: derived read models are NOT hash-chain-audited; Debezium CDC covers
--     history (same ruling as consolidated_revenue / consolidated_pnl).
--   * business_id is an opaque outlet dimension carried on the inbound event — there
--     is NO foreign key to any other service's data (database-per-service rule 1).
--   * Money is stored as integer minor units + ISO-4217 currency code; never a float
--     (rule 8).
--   * The table still extends Auditable (company_id, created_at/by, updated_at/by,
--     version) and is under FORCE RLS (rules 4 + 5), consistent with the sibling
--     consolidated_revenue table in V1__baseline.sql.

-- ---------------------------------------------------------------------------
-- outlet_revenue (per-outlet net-revenue accumulator)
-- ---------------------------------------------------------------------------
-- One row per (company_id, business_id, period, currency). RevenuePostingWriter
-- upserts on every SALE posting and unwinds on every VOID/REFUND posting, keeping
-- the accumulator consistent with ledger_posting in the same transaction.
CREATE TABLE outlet_revenue (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The outlet (business unit) this row accumulates revenue for. Matches the
    -- business_id dimension on ledger_posting and the SaleRecorded event; opaque
    -- reference — no FK across service boundaries.
    business_id     UUID         NOT NULL,

    -- The accounting period: YYYY-MM, derived from SaleRecorded.occurred_at in UTC.
    period          VARCHAR(7)   NOT NULL,

    -- Accumulated net revenue as Money (minor units + ISO-4217 code). NEVER a float.
    -- Decremented (not floored at zero) so the value is correct during period close
    -- even if refunds land before some sales in a redelivery burst.
    revenue_minor   BIGINT       NOT NULL DEFAULT 0,
    currency        CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- At most one accumulator per tenant + outlet + period + currency.
    CONSTRAINT uq_outlet_revenue_company_business_period_currency
        UNIQUE (company_id, business_id, period, currency)
);

ALTER TABLE outlet_revenue ENABLE ROW LEVEL SECURITY;
ALTER TABLE outlet_revenue FORCE ROW LEVEL SECURITY;

CREATE POLICY outlet_revenue_tenant_isolation ON outlet_revenue
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The dashboard and /pnl/outlets query scan by period within the bound tenant;
-- index that access path to avoid sequential scans on the hot query.
CREATE INDEX idx_outlet_revenue_company_period ON outlet_revenue (company_id, period);
