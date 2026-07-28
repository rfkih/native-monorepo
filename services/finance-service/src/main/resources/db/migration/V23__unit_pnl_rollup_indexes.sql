-- V23 — access-path indexes for the per-org-unit P&L rollup (GET /api/v1/pnl/org-units/{id}).
--
-- The rollup query joins org_unit_ref (the unit itself + its child outlets via parent_id)
-- to ledger_posting on (business_id, period). Both access paths were unindexed:
--   * ledger_posting had indexes on (period), (gl_account_code), (payroll_run_id) only —
--     nothing touching business_id;
--   * org_unit_ref.parent_id was captured in V22 but never indexed (never queried until now).
-- RLS injects company_id = current_setting('app.current_tenant', true) into every query,
-- so company_id leads each index (same convention as idx_org_unit_ref_company_type).
-- Additive only — applied migrations are never edited.

CREATE INDEX idx_ledger_posting_company_period_business
    ON ledger_posting (company_id, period, business_id);

CREATE INDEX idx_org_unit_ref_company_parent
    ON org_unit_ref (company_id, parent_id);
