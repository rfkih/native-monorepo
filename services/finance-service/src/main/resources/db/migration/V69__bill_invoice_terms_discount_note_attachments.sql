-- ADR 0084 — a bill the books can defend.
--
-- The phone "Tagihan baru" form records an incoming vendor invoice and refuses to save one the
-- books cannot defend. Finance never carried what that takes: the VENDOR's invoice number (the
-- duplicate-detection key), the invoice date and payment terms at draft time (the due date the
-- owner sees before posting), a header discount, the PPN rate the invoice actually carries
-- (0 / 11 / 12 %), an internal note, and the invoice itself as a photo/PDF. Every change here is
-- EXPAND-ONLY: nullable columns or NOT NULL with a DEFAULT, a partial unique index, one new table.
-- Nothing is renamed or dropped, so a rolling rollback to the previous image keeps working
-- (ADR 0057).

-- 1. vendor — the default payment term the form preselects ("termin ikut vendor").
ALTER TABLE vendor
    ADD COLUMN payment_term_days INTEGER NULL
        CONSTRAINT ck_vendor_payment_term_days CHECK (payment_term_days IS NULL OR payment_term_days >= 0);

-- 2. bill — the invoice as the vendor wrote it, and the arithmetic the form shows.
--    vendor_invoice_number : the vendor's own number (the duplicate key); NULL for legacy drafts.
--    term_days             : chosen at draft time; post() uses it when no override is supplied.
--    discount_minor        : header discount in minor units; reduces the net (DPP) before tax.
--    tax_bp                : the PPN rate in basis points applied to the net; 0 / 1100 / 1200.
--    note                  : internal note, never printed.
ALTER TABLE bill
    ADD COLUMN vendor_invoice_number VARCHAR(64) NULL,
    ADD COLUMN term_days INTEGER NULL
        CONSTRAINT ck_bill_term_days CHECK (term_days IS NULL OR term_days >= 0),
    ADD COLUMN discount_minor BIGINT NOT NULL DEFAULT 0
        CONSTRAINT ck_bill_discount_minor CHECK (discount_minor >= 0),
    ADD COLUMN tax_bp INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_bill_tax_bp CHECK (tax_bp IN (0, 1100, 1200)),
    ADD COLUMN note VARCHAR(1000) NULL;

-- Existing taxable bills were computed at the constant 11 % (BillWriter.INPUT_VAT_BP); record
-- that so the rate is readable on every historical row. The migration role is the table OWNER
-- and bill is FORCE RLS: without lifting FORCE for the statement the UPDATE would silently match
-- zero rows (the RLS-backfill trap).
ALTER TABLE bill NO FORCE ROW LEVEL SECURITY;
UPDATE bill SET tax_bp = 1100 WHERE tax_minor > 0;
ALTER TABLE bill FORCE ROW LEVEL SECURITY;

-- The vendor's invoice number is unique per (tenant, vendor) among live bills — a VOIDED bill's
-- number may be entered again (the void was the correction). Case-insensitive, and only when a
-- number was given (partial index, the uq_bill_payment_company_bill_idem idiom).
CREATE UNIQUE INDEX uq_bill_company_vendor_invoice
    ON bill (company_id, vendor_id, lower(vendor_invoice_number))
    WHERE vendor_invoice_number IS NOT NULL AND status <> 'VOID';

-- 3. bill_attachment — the invoice as evidence (photo/PDF), metadata only; the bytes live in the
--    object store under a content-addressed key (ADR 0048), private (ADR 0063's shape).
CREATE TABLE bill_attachment (
    id                UUID         NOT NULL PRIMARY KEY,
    bill_id           UUID         NOT NULL REFERENCES bill (id),
    content_type      VARCHAR(64)  NOT NULL,
    byte_size         INTEGER      NOT NULL,
    sha256            CHAR(64)     NOT NULL,
    object_key        TEXT         NOT NULL,
    original_filename VARCHAR(255) NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_bill_attachment_size CHECK (byte_size > 0 AND byte_size <= 5242880)
);

ALTER TABLE bill_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_attachment FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_attachment_tenant_isolation ON bill_attachment
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bill_attachment_bill ON bill_attachment (company_id, bill_id);
