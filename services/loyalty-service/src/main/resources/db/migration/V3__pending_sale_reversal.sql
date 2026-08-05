-- loyalty-service V3 — pending_sale_reversal: the cross-topic reorder parking slot (QA sweep
-- 2026-08-05, CRITICAL fix).
--
-- WHY: SaleVoided/SaleRefunded and SaleRecorded travel on SEPARATE topics, so a reversal can be
-- consumed BEFORE its sale has been ingested. The old behavior found no ledger rows, silently
-- returned, and still let ProcessedEventStore claim the reversal event — the reversal was lost
-- forever and the sale later EARNED full points/gift-card credit. Retrying instead of parking is
-- worse: throwing blocks the whole partition behind one missing sale, and a DLT needs manual ops.
--
-- HOW: when a reversal finds NO reversible ledger rows for its sale (either the sale carried no
-- loyalty facts, or it simply has not arrived yet — indistinguishable at that moment), it parks
-- ONE durable row here and completes. SaleIngestWriter checks this table at the end of every
-- ingest and applies the parked reversal in the SAME transaction, stamping applied_at. A row that
-- never gets applied marks a sale the ledger never saw (ops-visible, harmless).
--
-- UNIQUE (company_id, sale_id): full-reversal-only (review S2) — the FIRST reversal event for a
-- sale wins; a second (void then refund) is a no-op, exactly like the REVERSE-row guard on the
-- ingested path.
CREATE TABLE pending_sale_reversal (
    id                   UUID         NOT NULL PRIMARY KEY,
    sale_id              UUID         NOT NULL,

    -- The parked reversal event's durable id + occurred_at: replayed verbatim when ingest applies
    -- it, so the REVERSE ledger rows carry the ORIGINAL causing event's provenance and stamp.
    reversal_event_id    UUID         NOT NULL,
    reversal_occurred_at TIMESTAMPTZ  NOT NULL,

    -- Set when ingest resolved this row (reversal applied, or nothing to reverse). NULL = the
    -- sale has not been ingested yet.
    applied_at           TIMESTAMPTZ  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at           TIMESTAMPTZ  NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    version              BIGINT       NOT NULL,
    company_id           VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_pending_sale_reversal_sale UNIQUE (company_id, sale_id)
);

ALTER TABLE pending_sale_reversal ENABLE ROW LEVEL SECURITY;
ALTER TABLE pending_sale_reversal FORCE ROW LEVEL SECURITY;

CREATE POLICY pending_sale_reversal_tenant_isolation ON pending_sale_reversal
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Ingest's hot path: "is there a parked reversal for this sale?"
CREATE INDEX idx_pending_sale_reversal_sale ON pending_sale_reversal (company_id, sale_id);
