-- payment-service V4 — payment_settings: DIVISION scope (ADR 0045 amendment, 2026-08-07).
--
-- The scope grows from company + OUTLET to company + DIVISION + OUTLET. The column that used to
-- hold only an outlet id now holds ANY org-unit id — an OUTLET or a DIVISION (business-unit) row
-- from org-service's tree; NULL still means the company default row. Every facet now resolves
-- outlet -> division -> company -> implicit MANUAL (SettingsReader / ChargeWriter); credentials
-- remain COMPANY-ROW-ONLY (org_unit_id IS NULL), unchanged by this migration.
--
-- No data change is needed: every existing row's outlet_id already holds an org-unit id (an
-- outlet), so the rename alone is sufficient — a plain column rename, Postgres updates the index
-- and constraint definitions by attnum, not by text, so they keep working across the rename.

ALTER TABLE payment_settings RENAME COLUMN outlet_id TO org_unit_id;

-- uq_payment_settings_company_default keeps its name (already scope-agnostic; its predicate now
-- reads "WHERE org_unit_id IS NULL"). Only the per-scope index needs a name to match.
ALTER INDEX uq_payment_settings_outlet RENAME TO uq_payment_settings_unit;
