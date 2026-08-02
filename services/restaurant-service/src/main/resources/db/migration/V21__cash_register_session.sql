-- restaurant-service V21 — cash register session / "closing kasir" (ADR 0036).
--
-- One `cash_register_session` row per cash-drawer shift at an outlet: a cashier OPENs the drawer
-- with a counted opening float, trades through the shift, then CLOSEs it by counting the drawer
-- again. At close, the server (never the client) computes the expected cash position from the
-- ledger — sum of CASH-tender `sale` rows in [opened_at, closed_at) minus cash refunds recorded in
-- that same window — and diffs it against the cashier's physical count to produce `over_short_minor`.
-- A `RegisterSessionClosed` event then carries that variance to finance-service (event-driven
-- consolidation — rule 2, no sync call). At most one OPEN session per outlet at a time: the partial
-- unique index below is the actual invariant; the service-layer check-then-insert is best-effort and
-- the index is the backstop against the double-open race.
--
-- Why `sale.tender_type` is an additive, nullable, unbacked column (part 2 below) rather than a
-- NOT NULL DEFAULT backfill: `sale` is FORCE-RLS, and a Flyway `UPDATE ... SET tender_type = ...`
-- against a FORCE-RLS table runs unbound (no `app.current_tenant` GUC in a migration session) and
-- silently matches zero rows — the RLS-migration-backfill trap already worked around in V19/V20 by
-- never issuing a backfilling UPDATE at all. Since legacy pre-V21 sales have no recorded tender and
-- expected-cash at close only ever needs to sum CASH sales that occurred *after* this column exists,
-- there is nothing to backfill: NULL correctly means "legacy/unknown, outside every session window"
-- and every post-V21 write supplies the real value going forward. No `DEFAULT` clause is used either,
-- since Postgres already defaults an added nullable column to NULL for every existing row without a
-- rewrite.
--
-- Money throughout is minor units (BIGINT) + an ISO-4217 `currency` column — never a float (rule 8).
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy (USING + WITH CHECK) keyed to app.current_tenant, exactly as V1/V3/V19.

-- ---------------------------------------------------------------------------
-- 1. cash_register_session — one open drawer per outlet at a time
-- ---------------------------------------------------------------------------
CREATE TABLE cash_register_session (
    id                     UUID         NOT NULL PRIMARY KEY,

    -- The outlet whose drawer this session tracks.
    business_id            UUID         NOT NULL,

    -- OPEN | CLOSED.
    status                 VARCHAR(16)  NOT NULL,

    -- The outlet-local calendar day this session belongs to (may not equal opened_at's UTC date
    -- near a day boundary; set by the caller from outlet-local time).
    business_date          DATE         NOT NULL,

    opened_at              TIMESTAMPTZ  NOT NULL,

    -- The cash counted into the drawer at open. Money (libs/money) = integer minor units + ISO-4217
    -- code. NEVER a float.
    opening_float_minor    BIGINT       NOT NULL DEFAULT 0,
    currency               CHAR(3)      NOT NULL,

    -- Set only at close; NULL while OPEN.
    closed_at              TIMESTAMPTZ  NULL,

    -- Server-computed at close from the ledger: CASH-tender `sale` rows for this outlet in
    -- [opened_at, closed_at), and cash refunds attributed to that same window (see `payment`
    -- part 3 below). expected_cash = opening_float + cash_sales - cash_refunds. counted_cash is the
    -- cashier's physical recount; over_short = counted_cash - expected_cash (positive = over,
    -- negative = short). All five are NULL until close, and all five are populated together
    -- (ck_crs_closed_shape below).
    cash_sales_minor       BIGINT       NULL,
    cash_refunds_minor     BIGINT       NULL,
    expected_cash_minor    BIGINT       NULL,
    counted_cash_minor     BIGINT       NULL,
    over_short_minor       BIGINT       NULL,

    -- Producer idempotency: a retried open/close resolves to the existing row instead of double-
    -- opening/closing the drawer. close_idempotency_key is NULL until the close request arrives.
    open_idempotency_key   VARCHAR(64)  NOT NULL,
    close_idempotency_key  VARCHAR(64)  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    version                BIGINT       NOT NULL,
    company_id             VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_crs_status CHECK (status IN ('OPEN', 'CLOSED')),

    CONSTRAINT ck_crs_opening_float CHECK (opening_float_minor >= 0),

    CONSTRAINT ck_crs_counted_cash_nonneg CHECK (counted_cash_minor IS NULL OR counted_cash_minor >= 0),

    CONSTRAINT ck_crs_cash_refunds_nonneg CHECK (cash_refunds_minor IS NULL OR cash_refunds_minor >= 0),

    -- A session is either still OPEN (every close-only column NULL), or CLOSED with every close-only
    -- column — including the close idempotency key — populated. No half-closed row is possible.
    CONSTRAINT ck_crs_closed_shape CHECK (
        status = 'OPEN'
        OR (closed_at IS NOT NULL
            AND cash_sales_minor IS NOT NULL
            AND cash_refunds_minor IS NOT NULL
            AND expected_cash_minor IS NOT NULL
            AND counted_cash_minor IS NOT NULL
            AND over_short_minor IS NOT NULL
            AND close_idempotency_key IS NOT NULL)),

    CONSTRAINT uq_crs_company_open_idempotency UNIQUE (company_id, open_idempotency_key)
);

ALTER TABLE cash_register_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE cash_register_session FORCE ROW LEVEL SECURITY;

CREATE POLICY cash_register_session_tenant_isolation ON cash_register_session
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- THE one-drawer-per-outlet invariant: at most one OPEN session per (company, outlet). Also the
-- backstop against a concurrent double-open race (the service-layer check-then-insert is best
-- effort; this index is what actually enforces it).
CREATE UNIQUE INDEX uq_crs_one_open_per_outlet
    ON cash_register_session (company_id, business_id)
    WHERE status = 'OPEN';

-- Close replay: a retried close request for the same idempotency key resolves to the same row.
CREATE UNIQUE INDEX uq_crs_close_idem
    ON cash_register_session (company_id, close_idempotency_key)
    WHERE close_idempotency_key IS NOT NULL;

-- Access path: an outlet's session history, most recent first.
CREATE INDEX idx_crs_outlet_history
    ON cash_register_session (company_id, business_id, opened_at DESC);

-- ---------------------------------------------------------------------------
-- 2. sale — tender_type, additive/nullable (see header for why no backfill)
-- ---------------------------------------------------------------------------
-- CASH | QRIS | CARD, mirroring payment.tender_type — but deliberately NOT CHECK-constrained here.
-- NULL means legacy/unknown (pre-V21) and is simply excluded from every CASH-window sum. No CHECK
-- on values: the writer (SaleRecorder, mirroring payment's tender type) enforces the enum, which
-- avoids a drop/recreate of this constraint when the ONLINE tender type is expected to arrive in
-- V22.
ALTER TABLE sale
    ADD COLUMN tender_type VARCHAR(16) NULL;

-- Serves the session close's expected-cash query: sum CASH-tender sales for this outlet within
-- [opened_at, closed_at). Partial on tender_type = 'CASH' so the index stays small and every
-- legacy/non-cash row is excluded for free.
CREATE INDEX idx_sale_cash_window
    ON sale (company_id, business_id, occurred_at)
    WHERE tender_type = 'CASH';

-- ---------------------------------------------------------------------------
-- 3. payment — last_refund_at, additive/nullable
-- ---------------------------------------------------------------------------
-- Stamped by VoidRefundWriter at refund time. The v1 approximation for attributing a cash refund to
-- a session window (a refund is attributed to whichever session was OPEN at last_refund_at) —
-- documented as an approximation in ADR 0036, not an exact ledger join, since a refund does not
-- carry its own session_id.
ALTER TABLE payment
    ADD COLUMN last_refund_at TIMESTAMPTZ NULL;
