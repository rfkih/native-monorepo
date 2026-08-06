-- restaurant-service V26 — daily close v2, part 2: per-tender reconciliation lines (ADR 0038 phase 2).
--
-- One row per NON-CASH tender (CARD/QRIS/ONLINE) the cashier counted at a session close: the
-- server-computed expected (Σ sales − Σ refunds in the session window), the counted/settled figure
-- the cashier entered, and the SIGNED over_short = counted − expected. Cash stays on
-- cash_register_session (its drawer-accurate columns). finance posts each tender's variance truing
-- that tender's clearing account (CARD→1902, QRIS→1901, ONLINE→1250); this table is the local,
-- auditable record of what was counted per tender.
--
-- Money is minor units (BIGINT) + an ISO-4217 currency (rule 8). Every table: 6 Auditable columns +
-- ENABLE/FORCE ROW LEVEL SECURITY + a tenant-isolation policy (rules 4 + 5), as V21.

CREATE TABLE register_session_tender (
    id                UUID         NOT NULL PRIMARY KEY,

    -- The closed session this reconciliation line belongs to.
    session_id        UUID         NOT NULL REFERENCES cash_register_session (id),

    -- The outlet (denormalised from the session for tenant-scoped reads/indexes).
    business_id       UUID         NOT NULL,

    -- CARD | QRIS | ONLINE (never CASH — cash lives on cash_register_session).
    tender_type       VARCHAR(16)  NOT NULL,

    -- Server-computed net charged amount for this tender in [opened_at, closed_at): Σ sales − Σ refunds.
    expected_minor    BIGINT       NOT NULL,
    -- The cashier's counted/settled figure (EDC batch total, QRIS app, platform report).
    counted_minor     BIGINT       NOT NULL,
    -- SIGNED counted − expected (negative = short, positive = over, zero = no journal line).
    over_short_minor  BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_rst_tender_type CHECK (tender_type IN ('CARD', 'QRIS', 'ONLINE')),
    CONSTRAINT ck_rst_counted_nonneg CHECK (counted_minor >= 0),

    -- One reconciliation line per tender per session.
    CONSTRAINT uq_rst_session_tender UNIQUE (session_id, tender_type)
);

ALTER TABLE register_session_tender ENABLE ROW LEVEL SECURITY;
ALTER TABLE register_session_tender FORCE ROW LEVEL SECURITY;

CREATE POLICY register_session_tender_tenant_isolation ON register_session_tender
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access path: a session's tender lines.
CREATE INDEX idx_rst_session ON register_session_tender (company_id, session_id);
