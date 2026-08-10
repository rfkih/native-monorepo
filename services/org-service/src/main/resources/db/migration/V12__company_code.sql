-- org-service V12 — company.company_code: a short, immutable, per-tenant login-namespace code
-- (ADR 0054).
--
-- Invited-employee Keycloak usernames become <company_code>.<local>, so a short human login name
-- (e.g. "budi") is unique PER COMPANY inside the single shared `native` realm. This namespaces the
-- login, closes the cross-tenant username-enumeration leak on invite (a 409 becomes effectively
-- per-company), and adds enumeration friction (defence-in-depth — not the primary brute-force
-- control, which is the realm lockout + password policy).
--
-- 6 chars from the Crockford-base32-minus-ambiguous alphabet [23456789abcdefghjkmnpqrstvwxyz]
-- (no 0/1/i/l/o/u) — unambiguous when an owner reads it aloud to staff. Immutable like
-- base_currency/country (mapped updatable = false in the Company aggregate; no setter).
--
-- Unlike V9's single-literal DEFAULT backfill ('ID'), each row needs a DISTINCT value, so the code
-- cannot come from a column DEFAULT — it is generated per row in the DO block below. That block is
-- a bare UPDATE on a FORCE ROW LEVEL SECURITY table, which silently matches ZERO rows with no
-- tenant GUC bound (the V6 -> V7 trap), so it is wrapped in NO FORCE / FORCE (V7 precedent). The
-- table ends in the same FORCE state it started in. Prod has zero company rows at this point; the
-- backfill is defensive (UAT is throwaway).
--
-- No RLS policy change: company already has ENABLE + FORCE ROW LEVEL SECURITY and the row-scoped
-- company_tenant_isolation policy from V1, which applies unchanged to the new column (the predicate
-- is row-scoped, not column-scoped — same reasoning as V9).
--
-- Uniqueness is enforced by uq_company_company_code. A UNIQUE index is NOT subject to RLS, so it is
-- the fleet-wide arbiter of company_code uniqueness across ALL tenants — an in-app pre-SELECT under
-- RLS can only ever see the caller's own company row, so the application mints-and-retries against
-- this index instead of pre-checking.

ALTER TABLE company ADD COLUMN company_code VARCHAR(6);

ALTER TABLE company NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    alphabet  CONSTANT TEXT := '23456789abcdefghjkmnpqrstvwxyz';
    r         RECORD;
    candidate TEXT;
    i         INT;
BEGIN
    FOR r IN SELECT id FROM company WHERE company_code IS NULL LOOP
        LOOP
            candidate := '';
            FOR i IN 1..6 LOOP
                candidate := candidate
                    || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM company WHERE company_code = candidate);
        END LOOP;
        UPDATE company SET company_code = candidate WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE company FORCE ROW LEVEL SECURITY;

ALTER TABLE company ALTER COLUMN company_code SET NOT NULL;

CREATE UNIQUE INDEX uq_company_company_code ON company (company_code);
