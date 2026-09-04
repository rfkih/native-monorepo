-- org-service V15 (ADR 0070) — the work queue the org-tree flattening reconciler drains.
--
-- ============================================================================================
-- WHY A QUEUE AND NOT PLAIN SQL. Flattening is a STATE CHANGE (outlets reparented to top level,
-- business_unit/team rows retired), and every state change must publish its event through the
-- transactional outbox (rule 3) so finance's and employee's cached org trees converge. Avro payloads
-- cannot sensibly be hand-serialised inside a .sql file, so the actual work runs in Java —
-- OrgTreeFlatteningReconciler, one transaction per tenant, events and row changes committing
-- together.
--
-- WHY THE RECONCILER CANNOT FIND THE TENANTS ITSELF. company and org_unit are FORCE ROW LEVEL
-- SECURITY. The reconciler runs at boot with NO tenant bound, so current_setting('app.current_tenant')
-- is NULL and every row is filtered — it cannot enumerate which companies need flattening. Flyway
-- CAN see everything (it may briefly drop FORCE inside its own transaction), so the migration does
-- the discovery and leaves the answer here, in a table that is NOT under RLS. The reconciler then
-- reads this queue, and for each company_id binds a TenantContext scope and does the work through
-- the normal RLS-scoped repositories.
--
-- NOT UNDER RLS, deliberately — the same posture as `outbox`: infrastructure that spans tenants by
-- construction, whose rows carry company_id for downstream scoping. It holds no business data: just
-- "this tenant still needs flattening".
--
-- SELF-RETIRING. Rows are marked done_at as they are processed; once every row is done the
-- reconciler is a no-op on every subsequent boot. It is also idempotent independently of this table:
-- flattening a company with no business_unit/team rows and no parented outlets changes nothing.
--
-- WHICH DEPLOY MODELS ARE SAFE. Prod runs `docker compose up -d` (scripts/prod-deploy.sh), which
-- RECREATES each service container — the old one stops before the new one runs Flyway, so nothing
-- is serving during the window. The k8s overlay in deploy/ (replicas: 2, RollingUpdate) is NOT
-- safe for this migration as written: the old pods keep serving while the new pod migrates, and a
-- read served in that window would see every tenant's rows. If that overlay is ever adopted, this
-- migration needs a maintenance window or a pre-serve migration job.
-- ============================================================================================

-- company_id is VARCHAR(64), matching org_unit.company_id / the Auditable tenant column — NOT uuid.
-- Casting to uuid here would hard-fail the whole migration on any tenant id that is not a canonical
-- UUID (test fixtures and legacy rows), and a migration that cannot run is far worse than a queue
-- row the reconciler later skips.
CREATE TABLE org_tree_flattening_work (
    company_id VARCHAR(64) NOT NULL PRIMARY KEY,
    queued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at    TIMESTAMPTZ NULL
);

-- The reconciler polls for PENDING rows only; a partial index keeps that lookup trivial and stops
-- the index growing with every already-processed tenant.
CREATE INDEX idx_org_tree_flattening_pending
    ON org_tree_flattening_work (queued_at) WHERE done_at IS NULL;

-- Discovery. See the RLS note above: without the NO FORCE bracket this SELECT sees ZERO rows and
-- silently queues nothing — the failure mode that looks exactly like success. PostgreSQL DDL is
-- transactional, so a failure anywhere here rolls the NO FORCE back with it and org_unit is never
-- left unprotected.
ALTER TABLE org_unit NO FORCE ROW LEVEL SECURITY;

-- A company needs flattening if it has ANY node that the flat model cannot represent: a node that
-- is not an OUTLET (a business_unit or a team), or an outlet that still hangs under a parent.
INSERT INTO org_tree_flattening_work (company_id)
SELECT DISTINCT ou.company_id
  FROM org_unit ou
 WHERE ou.type <> 'OUTLET'
    OR ou.parent_id IS NOT NULL
ON CONFLICT (company_id) DO NOTHING;

ALTER TABLE org_unit FORCE ROW LEVEL SECURITY;
