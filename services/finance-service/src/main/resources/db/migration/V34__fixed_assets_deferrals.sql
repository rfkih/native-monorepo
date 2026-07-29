-- finance-service V34 — Fixed assets & deferrals (Phase 6, ADR 0020).
--
-- ============================================================================================
-- The last accounting pillar: fixed assets with straight-line depreciation, and deferrals (prepaid
-- expense amortized to expense; deferred revenue recognized to revenue). Introduces the system's
-- first TIME-BASED SCHEDULED postings — a monthly AMORTIZATION RUN that posts every due
-- depreciation/amortization for a period, ONCE per (company, period):
--
--   * fixed_asset / deferral — the schedulable items. Acquiring/creating one posts its opening GL
--     entry immediately (Dr 1500 / Cr 1900 capex; Dr 1400 / Cr 1900 prepaid; Dr 1900 / Cr 2400
--     deferred revenue) — source_event_id = the item id (UNIQUE on journal_entry).
--   * amortization_run — the per-period seal. UNIQUE (company_id, period) is the IDEMPOTENCY guard
--     (the tax-filing/within-close pattern): a re-run finds the row and no-ops, posting nothing.
--   * amortization_run_line — one row per item per run: the month index, the exact-sum scheduled
--     amount (cumulative-rounding straight line), and the journal entry it raised (NULL when the
--     month's share rounds to zero — a zero-amount entry cannot exist). source_event_id = line id.
--
-- Conventions (mirror V27/V31/V33): every table extends Auditable (6 cols) + ENABLE/FORCE ROW LEVEL
-- SECURITY, tenant policy on app.current_tenant (rules 4 + 5, fail-closed). Money is BIGINT minor
-- units + ISO-4217 CHAR(3), never a float (rule 8).
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- fixed_asset (a capitalized asset on the straight-line schedule)
-- ---------------------------------------------------------------------------
CREATE TABLE fixed_asset (
    id                   UUID         NOT NULL PRIMARY KEY,
    name                 VARCHAR(255) NOT NULL,
    acquisition_date     DATE         NOT NULL,
    -- The first depreciation month (YYYY-MM) = the month AFTER acquisition (full-month convention,
    -- ILLUSTRATIVE — SME-gated). Computed server-side at acquire time.
    start_period         VARCHAR(7)   NOT NULL,
    cost_minor           BIGINT       NOT NULL,
    salvage_minor        BIGINT       NOT NULL DEFAULT 0,
    useful_life_months   INTEGER      NOT NULL,
    currency             CHAR(3)      NOT NULL,
    -- ACTIVE only in this slice; DISPOSED is reserved for the deferred disposal flow (ADR 0020).
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    -- The capex GL entry raised at acquisition (Dr 1500 / Cr 1900).
    acquisition_entry_id UUID         NOT NULL,
    -- The client's Idempotency-Key: acquiring posts money, so a retried request must replay the
    -- original asset, not post a second capex entry (the AR/AP payment pattern; code-review C-1).
    idempotency_key      VARCHAR(128) NOT NULL,

    -- Auditable (libs/tenant).
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_fixed_asset_status  CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_fixed_asset_cost    CHECK (cost_minor > 0),
    CONSTRAINT ck_fixed_asset_salvage CHECK (salvage_minor >= 0 AND salvage_minor < cost_minor),
    CONSTRAINT ck_fixed_asset_life    CHECK (useful_life_months > 0),
    -- IDEMPOTENCY: a raced duplicate of the same client attempt loses here (DB backstop behind the
    -- writer's replay probe).
    CONSTRAINT uq_fixed_asset_idem UNIQUE (company_id, idempotency_key)
);

ALTER TABLE fixed_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixed_asset FORCE ROW LEVEL SECURITY;

CREATE POLICY fixed_asset_tenant_isolation ON fixed_asset
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_fixed_asset_company ON fixed_asset (company_id, status);

-- ---------------------------------------------------------------------------
-- deferral (a prepaid expense or deferred revenue on the schedule)
-- ---------------------------------------------------------------------------
CREATE TABLE deferral (
    id                UUID         NOT NULL PRIMARY KEY,
    kind              VARCHAR(20)  NOT NULL,
    description       VARCHAR(255) NOT NULL,
    total_minor       BIGINT       NOT NULL,
    months            INTEGER      NOT NULL,
    -- The first amortization month (YYYY-MM), inclusive.
    start_period      VARCHAR(7)   NOT NULL,
    currency          CHAR(3)      NOT NULL,
    -- The opening GL entry (Dr 1400 / Cr 1900 prepaid; Dr 1900 / Cr 2400 deferred revenue).
    creation_entry_id UUID         NOT NULL,
    -- The client's Idempotency-Key (money posts on create — same rationale as fixed_asset).
    idempotency_key   VARCHAR(128) NOT NULL,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_deferral_kind   CHECK (kind IN ('PREPAID_EXPENSE', 'DEFERRED_REVENUE')),
    CONSTRAINT ck_deferral_total  CHECK (total_minor > 0),
    CONSTRAINT ck_deferral_months CHECK (months > 0),
    -- IDEMPOTENCY: DB backstop behind the writer's replay probe.
    CONSTRAINT uq_deferral_idem UNIQUE (company_id, idempotency_key)
);

ALTER TABLE deferral ENABLE ROW LEVEL SECURITY;
ALTER TABLE deferral FORCE ROW LEVEL SECURITY;

CREATE POLICY deferral_tenant_isolation ON deferral
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_deferral_company ON deferral (company_id, kind);

-- ---------------------------------------------------------------------------
-- amortization_run (the per-period idempotency seal — one run per company per period)
-- ---------------------------------------------------------------------------
CREATE TABLE amortization_run (
    id          UUID        NOT NULL PRIMARY KEY,
    period      VARCHAR(7)  NOT NULL,
    line_count  INTEGER     NOT NULL,
    total_minor BIGINT      NOT NULL,
    run_at      TIMESTAMPTZ NOT NULL,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- IDEMPOTENCY: at most one run per (company, period). A re-run finds the row and no-ops.
    CONSTRAINT uq_amortization_run UNIQUE (company_id, period)
);

ALTER TABLE amortization_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE amortization_run FORCE ROW LEVEL SECURITY;

CREATE POLICY amortization_run_tenant_isolation ON amortization_run
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- amortization_run_line (one item's posted share within a run — the sub-ledger detail)
-- ---------------------------------------------------------------------------
CREATE TABLE amortization_run_line (
    id               UUID        NOT NULL PRIMARY KEY,
    run_id           UUID        NOT NULL REFERENCES amortization_run (id),
    item_type        VARCHAR(10) NOT NULL,
    item_id          UUID        NOT NULL,
    month_index      INTEGER     NOT NULL,
    amount_minor     BIGINT      NOT NULL,
    -- The 2-leg GL entry this line raised; NULL when the month's share rounds to zero.
    journal_entry_id UUID,
    currency         CHAR(3)     NOT NULL,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_run_line_item_type CHECK (item_type IN ('ASSET', 'DEFERRAL')),
    CONSTRAINT ck_run_line_amount    CHECK (amount_minor >= 0),
    CONSTRAINT ck_run_line_month     CHECK (month_index > 0),
    -- One line per item per run.
    CONSTRAINT uq_run_line_item UNIQUE (run_id, item_type, item_id)
);

ALTER TABLE amortization_run_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE amortization_run_line FORCE ROW LEVEL SECURITY;

CREATE POLICY amortization_run_line_tenant_isolation ON amortization_run_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_run_line_run  ON amortization_run_line (run_id);
-- The book-value / remaining-balance SUM scans by item.
CREATE INDEX idx_run_line_item ON amortization_run_line (item_type, item_id);
