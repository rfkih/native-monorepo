-- org-service V6 — org_unit.vertical (the BUSINESS_UNIT business vertical).
--
-- LOWERCASE module-key-style values ('restaurant' | 'carwash' | 'barbershop'), aligned with
-- entitlement-service's module_catalog.module_key vocabulary — deliberately NOT the UPPERCASE
-- enum-name casing the 'type' column uses (that divergence is intentional and documented in
-- the Vertical domain enum; per-company entitlement remains entitlement-service's separate,
-- company-level concept).
--
-- Whitelist enforced in the OrgUnit aggregate (no CHECK — invariants live in the aggregate,
-- same as the parent->child type rules; widening the whitelist later needs no migration).
-- NULLABLE: only BUSINESS_UNIT rows carry it; outlets/teams inherit via their parent
-- (resolved by join where needed). IMMUTABLE after creation (like company.base_currency) —
-- the column is mapped updatable = false and no PATCH path exists.
ALTER TABLE org_unit ADD COLUMN vertical VARCHAR(32);

-- Backfill: every pre-existing business unit is a restaurant — the only vertical shipped
-- so far (the same V2 add-column+backfill shape; the Flyway role updates under FORCE RLS).
UPDATE org_unit SET vertical = 'restaurant' WHERE type = 'BUSINESS_UNIT';
