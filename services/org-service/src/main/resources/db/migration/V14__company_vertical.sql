-- org-service V14 (ADR 0070) — company.vertical: the business vertical moves UP to the company.
--
-- ============================================================================================
-- WHY. ADR 0070 flattens the org tree to `company > outlet`. The vertical used to live on the
-- BUSINESS_UNIT ("division") node (V6), which outlets inherited through a parent self-join; with
-- the division level gone there is no node to hold it, so it becomes a company-level attribute —
-- REQUIRED and IMMUTABLE, exactly like base_currency and country ("Settings live at creation").
-- One company = one vertical = N outlets; a mixed-vertical owner creates a second company (ADR
-- 0021), which a separate legal entity would need anyway.
--
-- SCOPE. This migration is EXPAND-ONLY, per the ADR 0057 migration-safety gate: it ADDs a nullable
-- column and backfills it. It does NOT set NOT NULL and does NOT drop org_unit.vertical — an
-- app-tier rollback (redeploy the previous image) must still be able to run against this schema,
-- and the previous image still reads org_unit.vertical. The contract half (dropping
-- org_unit.vertical and the now-dead parent machinery) lands in a LATER release, once no deployed
-- image reads them.
--
-- NOT NULL is enforced in the AGGREGATE, not by the column — the house rule that invariants live
-- in the aggregate (see the V6 comment on org_unit.vertical, and the Vertical whitelist, which has
-- never had a CHECK). Company's constructor rejects a null/unknown vertical with a 400.
--
-- THE DATA FLATTENING IS NOT HERE. Reparenting outlets and retiring the business_unit/team rows
-- happens in org-service's one-shot OrgTreeFlatteningReconciler, NOT in SQL — every one of those
-- state changes must publish an event through the transactional outbox (rule 3), and hand-
-- serialised Avro inside a .sql file would be unmaintainable and untestable. This migration only
-- has to leave the vertical somewhere the reconciler and the app can read it.
--
-- ----------------------------------------------------------------------------------------------
-- THE RLS TRAP (why the NO FORCE / FORCE bracket). Both `company` and `org_unit` are FORCE ROW
-- LEVEL SECURITY, scoped by current_setting('app.current_tenant'). Flyway runs as the service's own
-- table-OWNING role, but FORCE binds the policy even for the owner, and a migration sets NO tenant
-- GUC — so current_setting('app.current_tenant', true) is NULL and the policy filters EVERY row.
-- Without this bracket the UPDATE below would match ZERO rows AND its org_unit sub-select would see
-- ZERO rows: a silently empty backfill, which is the worst outcome because it looks like it ran.
-- Both tables need the bracket: `company` because it is the UPDATE target, `org_unit` because it is
-- read in the sub-select. PostgreSQL DDL is transactional, so if anything here fails the NO FORCE
-- is rolled back with it and neither table is ever left unprotected; FORCE is fully restored before
-- this migration commits.
--
-- OPERATIONAL NOTE. NO FORCE lifts row security table-wide for the owning role for the duration of
-- this migration's transaction, so a LIVE instance querying company/org_unit concurrently in that
-- window would see every tenant's rows. Safe under the standard migrate-before-serve model (the
-- deploy runs migrations before routing traffic), which this deploy must already honour for any
-- expand/contract migration.
-- ============================================================================================

-- The business vertical, as the LOWERCASE module-key-style value ('restaurant' | 'carwash' |
-- 'barbershop') — the same vocabulary org_unit.vertical used (aligned with entitlement-service's
-- module_catalog.module_key), deliberately NOT the UPPERCASE enum-name casing. Nullable in the DB
-- (see above); the aggregate makes it required and immutable.
ALTER TABLE company ADD COLUMN vertical VARCHAR(32);

ALTER TABLE company  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE org_unit NO FORCE ROW LEVEL SECURITY;

-- Backfill from the company's FIRST business unit that carries a vertical (ordered by
-- effective_from then id — the same deterministic order the org-tree read uses, so the choice is
-- stable and reproducible rather than whatever the heap returns).
--
-- MULTI-VERTICAL COMPANIES: a company with two business units of DIFFERENT verticals would lose
-- one here. ADR 0070 gates this migration on a pre-flight that found no such tenant (2026-09-01:
-- one company, one business unit, 'restaurant'); such a tenant must be split into two companies
-- BEFORE this runs. The final COALESCE is the last-resort floor for a company with no business
-- unit at all (or none carrying a vertical) — 'restaurant' is the only vertical with a shipped
-- console, and it matches the V6 backfill's own choice.
UPDATE company c
   SET vertical = COALESCE(
         (SELECT ou.vertical
            FROM org_unit ou
           WHERE ou.company_id = c.company_id
             AND ou.type = 'BUSINESS_UNIT'
             AND ou.vertical IS NOT NULL
           ORDER BY ou.effective_from, ou.id
           LIMIT 1),
         'restaurant')
 WHERE c.vertical IS NULL;

ALTER TABLE org_unit FORCE ROW LEVEL SECURITY;
ALTER TABLE company  FORCE ROW LEVEL SECURITY;
