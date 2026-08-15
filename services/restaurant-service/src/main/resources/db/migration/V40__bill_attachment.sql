-- restaurant-service V40 — Bill attachments (ADR 0063): photos/PDF of the "real receipt" or a
-- supporting document for a bill/tagihan. The PAYLOAD lives in MinIO (ADR 0048, content-addressed
-- under restaurant/{company}/bill/{sha256}.{ext}); THIS row is metadata only. A bill may have MANY
-- attachments (unlike an expense claim's single replaceable receipt), so no replace/unique key.
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a
-- <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V11.

CREATE TABLE IF NOT EXISTS bill_attachment (
    id                UUID          NOT NULL PRIMARY KEY,
    bill_id           UUID          NOT NULL REFERENCES bill (id),
    content_type      VARCHAR(64)   NOT NULL,
    byte_size         INTEGER       NOT NULL,
    sha256            CHAR(64)      NOT NULL,
    object_key        TEXT          NOT NULL,
    original_filename VARCHAR(255)  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    -- Server-enforced 5 MiB cap (mirrors BillAttachment.MAX_BYTES + the multipart property).
    CONSTRAINT ck_bill_attachment_size CHECK (byte_size > 0 AND byte_size <= 5242880)
);

ALTER TABLE bill_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_attachment FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'bill_attachment'
           AND policyname = 'bill_attachment_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY bill_attachment_tenant_isolation ON bill_attachment
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bill_attachment_bill
    ON bill_attachment (company_id, bill_id);
