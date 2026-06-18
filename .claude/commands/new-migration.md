---
description: Add the next Flyway migration for a service, with Auditable columns + RLS policy.
argument-hint: <service-name> <snake_case_description> (e.g. finance-service add_settlement_table)
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

Add the next Flyway migration to **$1** under `services/$1/src/main/resources/db/migration/`.

This is high-risk — for anything beyond a trivial column add, hand the schema design to the
**data-engineer** agent.

Do:

1. Find the highest existing `V{n}__*.sql` in that folder and create `V{n+1}__$2.sql`.
2. Every NEW table MUST carry the six `Auditable` columns (`created_at`/`created_by`,
   `updated_at`/`updated_by`, `version`, `company_id`) and `ENABLE` + `FORCE ROW LEVEL SECURITY`
   with a `<table>_tenant_isolation` policy:
   `USING (company_id = current_setting('app.current_tenant', true))`
   `WITH CHECK (company_id = current_setting('app.current_tenant', true))` (rules 4–5). Mirror an
   existing baseline — e.g. `services/restaurant-service/src/main/resources/db/migration/V1__baseline.sql`.
3. Money columns: `amount_minor BIGINT` + `currency CHAR(3)`, never a float (rule 8). PII columns
   (salary, NIK, bank account) are column-level encrypted, never plaintext (rule 6).
4. Effective-dated rows: far-future sentinel `9999-12-31` for an open `effective_to`, not NULL.
5. Reference data shared in the same DB (e.g. a chart of accounts) may be global/un-scoped — match
   the existing pattern; do not add a tenant predicate to genuinely global tables.
6. Update the service's migration range in the `docs/PROJECT-MAP.md` "Services" table.

Then run `/native-check $1` (it will compile; `ddl-auto=validate` against the new schema only runs
under the Testcontainers DB tests, which need Docker).
