-- finance-service V41 — Payroll liability SETTLEMENT: the one-shot per-(run, kind) clearing of a
-- payroll liability bucket recognised by V40 (ADR 0032, Track P phase P5).
--
-- ============================================================================================================================
-- payroll_settlement — ONE row per (company, payroll_run_ledger, kind) that has been settled: a
-- one-shot Dr <bucket role account> / Cr CASH_CLEARING (1900) posting, recorded here as the settle-
-- once guard AND the settlement history (amount/currency/journal_entry/settled_at). Mirrors
-- invoice_payment's idempotency-key shape (V26) — settlement is a genuinely repeatable client
-- action (unlike TaxSettlementWriter's one-shot filing-status transition), so a fresh
-- Idempotency-Key is REQUIRED per attempt and stored for replay (finance.labor.service
-- PayrollSettlementWriter, mirroring the AR/assets Idempotency-Key idiom: keyless -> 400,
-- same-key replay -> 200, a DIFFERENT key against an already-settled (run, kind) -> 409).
-- ============================================================================================================================

CREATE TABLE payroll_settlement (
    id                     UUID         NOT NULL PRIMARY KEY,
    payroll_run_ledger_id  UUID         NOT NULL REFERENCES payroll_run_ledger (id),
    kind                   VARCHAR(16)  NOT NULL,
    amount_minor           BIGINT       NOT NULL,
    amount_currency        CHAR(3)      NOT NULL,
    journal_entry_id       UUID         NOT NULL,
    settled_at             TIMESTAMPTZ  NOT NULL,

    -- The Idempotency-Key that settled this (run, kind) bucket — the replay lookup key (see the
    -- writer javadoc). NOT part of the spec's original column list; added because the task's
    -- required semantics (same-key replay -> 200, different-key-on-a-settled-kind -> 409) need a
    -- durable per-attempt key, exactly like invoice_payment.idempotency_key (V26).
    idempotency_key        VARCHAR(64)  NOT NULL,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    version                BIGINT       NOT NULL,
    company_id             VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_payroll_settlement_kind
        CHECK (kind IN ('NET_WAGES', 'PPH21', 'BPJS_KES', 'BPJS_TK', 'OTHER'))
);

ALTER TABLE payroll_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_settlement FORCE ROW LEVEL SECURITY;

CREATE POLICY payroll_settlement_tenant_isolation ON payroll_settlement
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The one-shot guard: at most one settlement per (tenant, run, kind) — a double-settle of the same
-- bucket is rejected 409 (PayrollSettlementWriter checks this up front; the index is the DB
-- backstop for a genuine race).
CREATE UNIQUE INDEX uq_payroll_settlement_once
    ON payroll_settlement (company_id, payroll_run_ledger_id, kind);

-- The replay lookup: a retried request with the SAME Idempotency-Key returns the original
-- settlement (200), never posting twice. Per-tenant, mirroring uq_invoice_payment_company_invoice_
-- idem's shape (V26) but keyed on the settlement's own natural identity instead of an invoice id.
CREATE UNIQUE INDEX uq_payroll_settlement_idempotency_key
    ON payroll_settlement (company_id, idempotency_key);
