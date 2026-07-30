-- finance-service V36 — Fixed-asset disposal/sale: gain/loss accounts + disposal columns (ADR 0022).
--
-- ============================================================================================================================
-- Enables the disposal flow V35 note E reserved: selling/scrapping a fixed asset derecognizes cost
-- (Cr 1500) and accumulated depreciation (Dr 1590), records proceeds (Dr 1900 CASH_CLEARING — the
-- same clearing account every cash movement uses; a bank reconciliation sweeps it), and posts the
-- plug as GAIN (Cr 4200, other income) or LOSS (Dr 5600). The posting is ad-hoc (writer-built,
-- role-resolved — the V30/V32/V35 approach); the entry posts into the CURRENT period (the
-- acquisition precedent — `disposal_date` is metadata, never a posting period; backdating would
-- restate closed/filed periods).
--
-- The disposal amounts are FROZEN onto the fixed_asset row (accumulated_disposed_minor et al):
-- the cash-flow reader reclassifies from these frozen columns keyed on disposal_period (= the
-- posting period), never by re-summing run lines — so the reader can never disagree with the legs
-- the disposal entry actually posted.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- fixed_asset: allow DISPOSED + the disposal columns.
-- ---------------------------------------------------------------------------
ALTER TABLE fixed_asset DROP CONSTRAINT ck_fixed_asset_status;
ALTER TABLE fixed_asset
    ADD CONSTRAINT ck_fixed_asset_status CHECK (status IN ('ACTIVE', 'DISPOSED'));

ALTER TABLE fixed_asset
    ADD COLUMN disposal_date              DATE,
    ADD COLUMN disposal_period            VARCHAR(7),  -- the POSTING period of the disposal entry
    ADD COLUMN proceeds_minor             BIGINT,
    ADD COLUMN accumulated_disposed_minor BIGINT,      -- depreciation derecognized, frozen at disposal
    ADD COLUMN disposal_entry_id          UUID,
    ADD COLUMN disposal_idempotency_key   VARCHAR(128);

-- Proceeds are never negative (0 = scrap/write-off).
ALTER TABLE fixed_asset
    ADD CONSTRAINT ck_fixed_asset_proceeds CHECK (proceeds_minor IS NULL OR proceeds_minor >= 0);

-- All-or-nothing: a DISPOSED row carries every disposal fact; an ACTIVE row carries none.
ALTER TABLE fixed_asset
    ADD CONSTRAINT ck_fixed_asset_disposal_state CHECK (
        (status = 'DISPOSED'
             AND disposal_date IS NOT NULL
             AND disposal_period IS NOT NULL
             AND proceeds_minor IS NOT NULL
             AND accumulated_disposed_minor IS NOT NULL
             AND disposal_entry_id IS NOT NULL
             AND disposal_idempotency_key IS NOT NULL)
        OR
        (status = 'ACTIVE'
             AND disposal_date IS NULL
             AND disposal_period IS NULL
             AND proceeds_minor IS NULL
             AND accumulated_disposed_minor IS NULL
             AND disposal_entry_id IS NULL
             AND disposal_idempotency_key IS NULL)
    );

-- Replay dedupe for the dispose POST (V26 partial-unique precedent — key present only once disposed).
CREATE UNIQUE INDEX uq_fixed_asset_disposal_idem
    ON fixed_asset (company_id, disposal_idempotency_key)
    WHERE disposal_idempotency_key IS NOT NULL;

-- Cash-flow reclassification reads disposals per (company, posting period).
CREATE INDEX idx_fixed_asset_disposal_period
    ON fixed_asset (company_id, disposal_period)
    WHERE disposal_period IS NOT NULL;

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts (4200 / 5600 verified collision-free across V2–V35).
-- ---------------------------------------------------------------------------
-- 4200 — Gain on Asset Disposal (REVENUE — "other income"; the P&L reader has no operating-vs-other
--         sectioning yet, so it lists beside 4000/4100 like interest income does).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4200', 'Gain on Asset Disposal (ILLUSTRATIVE — SME-gated)', 'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- 5600 — Loss on Asset Disposal (EXPENSE).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5600', 'Loss on Asset Disposal (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'GAIN_ON_DISPOSAL', '4200', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'LOSS_ON_DISPOSAL', '5600', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. The disposal-month convention is NO depreciation in the month of disposal (the asset must be
--    fully depreciated through the PRIOR month before disposing; the writer enforces it). Some
--    policies depreciate the disposal month — confirm.
-- B. 4200/5600 are illustrative placeholders for the client's real "other income/expense" codes.
-- C. VAT on the sale of a business asset (PPN atas penyerahan aktiva, UU PPN 16D) is NOT computed
--    here — the proceeds post gross to clearing. Confirm treatment before production use.
-- ============================================================================================================================
