-- finance-service V48 — pending_sale_reversal: the cross-topic reorder parking slot (QA sweep
-- 2026-08-05, HIGH fix; the finance twin of loyalty-service V3).
--
-- WHY: SaleVoided/SaleRefunded and SaleRecorded travel on SEPARATE topics. When a reversal was
-- consumed before its sale had posted, ReversalPostingWriter could not distinguish "legacy sale
-- with no GL entry" from "sale not posted YET" and took the 2-line GROSS fall-back — permanently
-- misbooking any sale with tax/service-charge/discount/gift-card legs (and for a gift-card-settled
-- void even the TOTAL was wrong: SaleVoided.amount is the payment residual, not the grand total).
-- The real per-leg SALE entry then posted on top, unconditionally.
--
-- HOW: a reversal whose sale_id is set but has NO matching journal_entry.sale_aggregate_id parks
-- here (nothing posted at all); RevenuePostingWriter applies the parked reversal in the SAME
-- transaction that posts the sale — per-leg, with the true grand total. Events with sale_id NULL
-- (genuinely id-less legacy producers) keep the old GROSS fall-back.
--
-- UNIQUE (company_id, reversal_event_id): one parked row per reversal EVENT (a sale can park a
-- void AND a refund; each applies exactly once, mirroring live consumption which claims per event).
CREATE TABLE pending_sale_reversal (
    id                   UUID         NOT NULL PRIMARY KEY,
    sale_id              UUID         NOT NULL,
    kind                 VARCHAR(8)   NOT NULL,

    -- The parked event, verbatim — enough to rebuild it at apply time.
    reversal_event_id    UUID         NOT NULL,
    payment_id           UUID         NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    total_refunded_minor BIGINT       NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL,
    tender_type          VARCHAR(32)  NULL,
    channel              VARCHAR(64)  NULL,
    business_id          UUID         NULL,

    -- Resolution: applied_at set when the sale posted and this row was processed; outcome says
    -- how (APPLIED = contra posted; REJECTED_PARTIAL = the live path's partial-refund 400
    -- equivalent — logged, never poisons the sale-posting transaction).
    applied_at           TIMESTAMPTZ  NULL,
    outcome              VARCHAR(16)  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at           TIMESTAMPTZ  NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    version              BIGINT       NOT NULL,
    company_id           VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_pending_sale_reversal_kind CHECK (kind IN ('VOIDED', 'REFUNDED')),
    CONSTRAINT ck_pending_sale_reversal_outcome
        CHECK (outcome IS NULL OR outcome IN ('APPLIED', 'REJECTED_PARTIAL')),
    CONSTRAINT uq_pending_sale_reversal_event UNIQUE (company_id, reversal_event_id)
);

ALTER TABLE pending_sale_reversal ENABLE ROW LEVEL SECURITY;
ALTER TABLE pending_sale_reversal FORCE ROW LEVEL SECURITY;

CREATE POLICY pending_sale_reversal_tenant_isolation ON pending_sale_reversal
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The sale-posting hook's hot path: "any unapplied parked reversals for this sale?"
CREATE INDEX idx_pending_sale_reversal_sale ON pending_sale_reversal (company_id, sale_id);
