-- restaurant-service V22 — register close accuracy: gift-card-split cash + exact refund windows
-- (money-review fixes C1 + C3 of the closing-kasir feature, ADR 0036).
--
-- C1 — gift-card-split cash sales: a CASH-tender sale part-paid by a redeemed gift card puts less
-- physical cash in the drawer than `sale.amount_minor` (the grand total). `cash_collected_minor`
-- (part 1 below) is that physical-cash amount — grand total minus any gift-card-redeemed portion —
-- set by the writer from V22 onward. NULL means legacy/unknown (pre-V22, or a tender written before
-- this column existed) and the expected-cash query falls back to `amount_minor` via COALESCE, exactly
-- as V21's `tender_type` falls back to "excluded" for legacy rows. No backfill, for the same reason
-- V21 backfilled nothing: `sale` is FORCE-RLS, and a Flyway `UPDATE ... SET cash_collected_minor = ...`
-- against a FORCE-RLS table runs unbound (no `app.current_tenant` GUC in a migration session) and
-- silently matches zero rows — the RLS-migration-backfill trap. `idx_sale_cash_collected_window`
-- (part 1 below) is a covering variant of V21's `idx_sale_cash_window` for the expected-cash query,
-- which now needs both `cash_collected_minor` and `amount_minor` (for the COALESCE) without a heap
-- fetch; V21's original index remains in place (it still serves any reader that only needs
-- `occurred_at`) and is left as an additive-only cleanup candidate for a later migration, not dropped
-- here.
--
-- C3 — exact per-refund window attribution: `payment.refunded_minor` (V3) is cumulative per payment,
-- not per refund, so a session close that attributes "the refund" to whichever window closed last
-- double-counts or misattributes a payment refunded partially across two different register sessions.
-- `payment_refund` (part 2 below) is an append-only per-refund ledger: VoidRefundWriter inserts one
-- row per refund carrying that refund's DELTA (not the running cumulative total). The register close
-- now sums `payment_refund.amount_minor` for CASH-tender refunds with `refunded_at` in
-- [opened_at, closed_at) — an exact ledger sum, not the V21 approximation. `payment.last_refund_at`
-- (V21) is left in place (additive-only; no column is dropped or retyped) but is no longer the
-- attribution source for a new session close.
--
-- Money throughout is minor units (BIGINT) + an ISO-4217 `currency` column — never a float (rule 8).
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy (USING + WITH CHECK) keyed to app.current_tenant, exactly as V1/V3/V21.

-- ---------------------------------------------------------------------------
-- 1. sale — cash_collected_minor, additive/nullable (see header for why no backfill)
-- ---------------------------------------------------------------------------
-- The physical cash a CASH-tender sale actually put in the drawer: grand total minus any
-- gift-card-redeemed portion. NULL means legacy/unknown; the expected-cash query falls back to
-- amount_minor via COALESCE(cash_collected_minor, amount_minor). No CHECK constraint here (mirrors
-- V21's tender_type: the writer enforces the invariant that this can never exceed amount_minor,
-- avoiding a constraint drop/recreate as gift-card-split logic evolves).
ALTER TABLE sale
    ADD COLUMN cash_collected_minor BIGINT NULL;

-- Covering variant of V21's idx_sale_cash_window for the expected-cash query, which now reads both
-- cash_collected_minor and amount_minor (for the COALESCE fallback) — INCLUDE avoids a heap fetch for
-- either column. Partial on tender_type = 'CASH' for the same reason as V21: keep the index small and
-- exclude every legacy/non-cash row for free. V21's idx_sale_cash_window remains untouched (additive
-- only); it could be dropped in a later cleanup once every reader is confirmed migrated to this one.
CREATE INDEX idx_sale_cash_collected_window
    ON sale (company_id, business_id, occurred_at)
    INCLUDE (cash_collected_minor, amount_minor)
    WHERE tender_type = 'CASH';

-- ---------------------------------------------------------------------------
-- 2. payment_refund — append-only per-refund ledger (exact window attribution)
-- ---------------------------------------------------------------------------
CREATE TABLE payment_refund (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The payment this refund was applied against.
    payment_id      UUID         NOT NULL REFERENCES payment (id),

    -- Denormalized from the payment, so the register-close window query never has to join back to
    -- payment (or restaurant_order) to find the outlet.
    business_id     UUID         NOT NULL,

    -- Snapshot of payment.tender_type at refund time, so the window query can filter CASH directly
    -- without a join.
    tender_type     VARCHAR(16)  NOT NULL,

    -- The DELTA of this refund only (not payment.refunded_minor's running cumulative total). Money
    -- (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    refunded_at     TIMESTAMPTZ  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_payment_refund_amount CHECK (amount_minor > 0)
);

ALTER TABLE payment_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_refund FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_refund_tenant_isolation ON payment_refund
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access path: a payment's refund history (receipt / dispute review).
CREATE INDEX idx_payment_refund_payment_id ON payment_refund (payment_id);

-- Serves the session close's exact cash-refund sum: CASH-tender refund deltas for this outlet within
-- [opened_at, closed_at). Partial on tender_type = 'CASH', mirroring idx_sale_cash_window /
-- idx_sale_cash_collected_window above, so the index stays small and every non-cash refund is
-- excluded for free.
CREATE INDEX idx_payment_refund_window
    ON payment_refund (company_id, business_id, refunded_at)
    WHERE tender_type = 'CASH';
