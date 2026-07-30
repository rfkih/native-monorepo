-- org-service V9 — company.country + optional signup-funnel columns (ADR 0025,
-- country-driven company defaults, Odoo-style signup).
--
-- `country` is ISO 3166-1 alpha-2, write-once in the aggregate (mapped
-- updatable = false, same immutability pattern as base_currency), and is the
-- SOURCE from which base_currency is derived at creation time: ID -> IDR,
-- anything else -> USD (CLAUDE.md "Settings live at creation" — base_currency
-- itself remains the immutable column of record; country is not re-derived
-- from it later).
--
-- `phone`, `company_size`, `primary_interest` are nullable OPTIONAL signup
-- funnel fields captured by the public sign-up flow only; the in-app
-- create-company path (an existing tenant creating another company/business
-- unit) never supplies them.
--
-- Whitelist enforced in the Company aggregate + at the request edge (no CHECK
-- constraint here — invariants live in the aggregate, same as org_unit.vertical
-- in V6); widening company_size/primary_interest/country value sets later
-- needs no migration.
--
-- ADD COLUMN ... NOT NULL DEFAULT rewrites the column in-place from the
-- supplied literal default with no separate UPDATE statement, which
-- deliberately avoids the FORCE-RLS zero-row UPDATE trap documented from V7
-- (a bare UPDATE on a FORCE ROW LEVEL SECURITY table silently matches 0 rows
-- outside a bound tenant session). 'ID' is not a placeholder backfill: every
-- pre-existing company row in this database is genuinely Indonesian, so 'ID'
-- is the correct historical value for country on those rows.
--
-- No RLS policy change: company already has ENABLE + FORCE ROW LEVEL SECURITY
-- and the company_tenant_isolation policy from V1. That policy predicate
-- (company_id = current_setting('app.current_tenant', true)) is row-scoped,
-- not column-scoped, so it applies unchanged to these new columns without
-- any ALTER POLICY.
--
-- No new index: no query path filters on country/phone/company_size/
-- primary_interest yet.
ALTER TABLE company ADD COLUMN country CHAR(2) NOT NULL DEFAULT 'ID';
ALTER TABLE company ADD COLUMN phone VARCHAR(32);
ALTER TABLE company ADD COLUMN company_size VARCHAR(16);
ALTER TABLE company ADD COLUMN primary_interest VARCHAR(32);
