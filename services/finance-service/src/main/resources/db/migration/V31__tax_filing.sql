-- finance-service V31 — Tax filing seal (Phase 4 Tax / PPN, ADR 0017).
--
-- ============================================================================================
-- One row per (company_id, period, tax_type) that the company has FILED. It is the tax analogue of
-- within_company_close (V10): it records that the company finalised a period's PPN (VAT) return —
-- the output VAT, the input VAT, the net, its direction, and the GL journal entries raised — and is
-- the IDEMPOTENCY guard (UNIQUE (company_id, period, tax_type)): a second file of an already-filed
-- (company, period, tax_type) finds the row and no-ops, re-posting nothing.
--
-- The PPN amounts themselves are DERIVED from the double-entry GL (Σ over journal_line for accounts
-- 2200 VAT_OUTPUT / 1300 VAT_INPUT in the period) — this table snapshots them at file time; it is
-- NOT the source of truth for the ledger. Filing posts an ad-hoc balanced netting JournalEntry
-- (Dr 2200 / Cr 1300 / net to 2300 or 1310); settlement posts Dr 2300 / Cr 1900. See ADR 0017.
--
-- Auditable + FORCE RLS scoped to company_id (rules 4 + 5), the finance tenant-table precedent
-- (within_company_close V10 / invoice V24 / bank_account V29). Money is BIGINT minor units + ISO-4217
-- CHAR(3); NEVER a float (rule 8).
-- ============================================================================================

CREATE TABLE tax_filing (
    id                  UUID         NOT NULL PRIMARY KEY,

    -- The accounting period the return covers (YYYY-MM), and the tax type. tax_type is 'PPN' (VAT)
    -- for this slice; the column leaves room for PPh (income tax) and others without a schema change.
    period              VARCHAR(7)   NOT NULL,
    tax_type            VARCHAR(16)  NOT NULL DEFAULT 'PPN',

    -- FILED on creation; SETTLED once a PAYABLE return's net has been paid (Dr 2300 / Cr 1900). A
    -- CREDITABLE or zero-net return is terminal at FILED (nothing to settle).
    status              VARCHAR(16)  NOT NULL DEFAULT 'FILED',

    -- The period's GL currency (ISO-4217); all VAT postings in the period share it (single-currency
    -- GL guard, mirrored on the filing post).
    currency            CHAR(3)      NOT NULL,

    -- The snapshot at file time: output VAT (credit-net of 2200), input VAT (debit-net of 1300), and
    -- the net = output - input (may be negative for a CREDITABLE return). net_direction records the
    -- sign so the settlement rule ('PAYABLE' only) needs no re-derivation.
    output_vat_minor    BIGINT       NOT NULL,
    input_vat_minor     BIGINT       NOT NULL,
    net_minor           BIGINT       NOT NULL,
    net_direction       VARCHAR(12)  NOT NULL,

    -- The GL journal entries raised: the netting entry on file (always), the settlement entry on
    -- settle (NULL until then). Both are UNIQUE on journal_entry.source_event_id (V13) — the DB-level
    -- idempotency backstop behind the status + advisory-lock guards.
    filing_entry_id     UUID         NOT NULL,
    settlement_entry_id UUID,

    filed_at            TIMESTAMPTZ  NOT NULL,
    settled_at          TIMESTAMPTZ,

    -- Auditable (libs/tenant) — company_id is the filing company (VARCHAR(64), the finance tenant
    -- convention: within_company_close V10, invoice V24).
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(255) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          VARCHAR(255) NOT NULL,
    version             BIGINT       NOT NULL,
    company_id          VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_tax_filing_status        CHECK (status IN ('FILED', 'SETTLED')),
    CONSTRAINT ck_tax_filing_net_direction CHECK (net_direction IN ('PAYABLE', 'CREDITABLE')),
    -- IDEMPOTENCY: at most one filing per (company, period, tax_type). A re-file finds the row and
    -- no-ops, posting nothing.
    CONSTRAINT uq_tax_filing UNIQUE (company_id, period, tax_type)
);

ALTER TABLE tax_filing ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_filing FORCE ROW LEVEL SECURITY;

CREATE POLICY tax_filing_tenant_isolation ON tax_filing
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- History list scans by company; the filing detail is by id (PK). Index the list access path.
CREATE INDEX idx_tax_filing_company_period ON tax_filing (company_id, period);
