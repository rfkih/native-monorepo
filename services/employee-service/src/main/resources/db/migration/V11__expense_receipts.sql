-- Phase E3 EXPENSE RECEIPTS (ADR 0030 §8) — receipt photo storage: bytea in the employee-service
-- database (no object store in v1). RLS + Auditable exactly like every other table (rule 4/5);
-- Debezium is unaffected (outbox-only include list, ADR 0030 §8).
--
-- N receipts per claim are structurally allowed (no uniqueness constraint on claim_id); the v1 UI
-- and ReceiptWriter use exactly ONE per claim, replacing it (delete-then-insert) on re-upload
-- pre-submit. Cancel/refuse leave any receipt rows in place (v1 retention — no cascade delete);
-- a hard purge is a future follow-up if storage pressure ever demands it.
--
-- content_type is the DECLARED header from the multipart part; ReceiptContentTypeValidator
-- (magic-byte sniffing, application layer) rejects any upload whose actual bytes disagree BEFORE
-- this row is ever written, so a persisted content_type is trustworthy for the Content-Type
-- response header on serve. byte_size mirrors data's actual length (also DB-capped at 5 MiB,
-- matching the multipart max-file-size); sha256 is a lowercase hex digest of the stored bytes,
-- proving an exact upload/serve round trip.
--
-- Receipts are business documents, not rule-6 PII (ADR 0030 §8): stored UNENCRYPTED, never
-- logged, served only via authenticated tenant-scoped GETs (own claim on /me, any tenant claim on
-- the manager route) — unlike nik/bank_account/npwp_enc, this column carries no
-- PiiAttributeConverter.

CREATE TABLE expense_receipt (
    id            UUID         NOT NULL PRIMARY KEY,
    claim_id      UUID         NOT NULL,
    content_type  VARCHAR(64)  NOT NULL,
    byte_size     INT          NOT NULL CHECK (byte_size > 0 AND byte_size <= 5242880),
    sha256        CHAR(64)     NOT NULL,
    data          BYTEA        NOT NULL,

    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255) NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    VARCHAR(64)  NOT NULL
);

ALTER TABLE expense_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY expense_receipt_tenant_isolation ON expense_receipt
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_expense_receipt_claim ON expense_receipt (claim_id);
