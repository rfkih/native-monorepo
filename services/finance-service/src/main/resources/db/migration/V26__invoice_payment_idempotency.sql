-- finance-service V26 — payment idempotency key (code-review C1).
--
-- A retried partial payment (e.g. the client resends after a lost response) must NOT post money
-- twice. This adds an optional client-supplied idempotency key to invoice_payment with a per-tenant
-- UNIQUE index: PaymentWriter replays the existing payment when the key is seen again (no second GL
-- posting), and the UNIQUE index is the database backstop against a concurrent identical retry.
--
-- Nullable + a PARTIAL unique index (WHERE idempotency_key IS NOT NULL) so payments recorded without
-- a key (legacy / server-internal) are unaffected and multiple NULLs coexist.
--
-- The key is scoped to (company_id, invoice_id, idempotency_key): a retry of the SAME payment (same
-- invoice + key) is de-duped, but the same key reused on a DIFFERENT invoice is a distinct payment on
-- that invoice (not a silent no-op) — the replay lookup is likewise scoped by invoice_id.

ALTER TABLE invoice_payment ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX uq_invoice_payment_company_invoice_idem
    ON invoice_payment (company_id, invoice_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
