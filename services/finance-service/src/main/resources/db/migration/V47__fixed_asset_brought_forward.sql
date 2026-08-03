-- finance-service V47 — BROUGHT-FORWARD fixed assets (ADR 0037). Lets an EXISTING business register
-- a pre-owned asset with its depreciation-to-date so future monthly runs continue on the REMAINING
-- base over the REMAINING life — WITHOUT the acquisition crediting cash (the value is established by
-- the opening balance sheet, not a purchase).
--
-- Accumulated depreciation is DERIVED (Σ amortization_run_line.amount_minor), not stored, and the run
-- always spreads depreciableBase = cost − salvage over useful_life_months from start_period. Two new
-- columns carry the pre-go-live state:
--   * opening_accumulated_minor — depreciation already taken before registration. FixedAsset
--     .depreciableBase() becomes (cost − salvage − opening_accumulated) so the run depreciates only
--     the remaining base; the register's book value and the disposal's derecognized accumulated both
--     add it to the summed run lines. For an ACQUIRED asset it is always 0 (the run/register are
--     unchanged).
--   * origin — ACQUIRED (the ADR-0020 capex-to-cash path) | BROUGHT_FORWARD (the ADR-0037 cash-free
--     path: Dr FIXED_ASSET_COST gross / Cr ACCUMULATED_DEPRECIATION opening / Cr OPENING_BALANCE_EQUITY
--     net book value).
--
-- RLS note: fixed_asset is FORCE ROW LEVEL SECURITY (V34). ADD COLUMN ... DEFAULT populates every
-- existing row at DDL time WITHOUT an UPDATE, so the FORCE-RLS "an UPDATE matches 0 rows" backfill
-- gotcha does not apply here — no NO FORCE / FORCE dance is needed.
--
-- Money (rule 8): *_minor columns are integer minor units in the asset's existing `currency`. Never
-- a float.

ALTER TABLE fixed_asset
    ADD COLUMN opening_accumulated_minor BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN origin                    VARCHAR(16) NOT NULL DEFAULT 'ACQUIRED';

ALTER TABLE fixed_asset
    -- Only the two known origins.
    ADD CONSTRAINT ck_fixed_asset_origin
        CHECK (origin IN ('ACQUIRED', 'BROUGHT_FORWARD')),
    -- Opening accumulated is non-negative and cannot exceed the depreciable base (you cannot have
    -- depreciated more than cost − salvage). Holds for ACQUIRED too (0 <= cost − salvage).
    ADD CONSTRAINT ck_fixed_asset_opening_accum_bound
        CHECK (opening_accumulated_minor >= 0
               AND opening_accumulated_minor <= cost_minor - salvage_minor),
    -- An ACQUIRED asset (the capex-to-cash path) never carries opening accumulated depreciation.
    ADD CONSTRAINT ck_fixed_asset_acquired_zero_opening
        CHECK (origin <> 'ACQUIRED' OR opening_accumulated_minor = 0);
