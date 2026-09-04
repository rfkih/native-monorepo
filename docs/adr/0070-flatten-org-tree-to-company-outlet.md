# 0070. Flatten the org tree to `company > outlet`; the vertical moves to the company

- **Status:** Accepted
- **Date:** 2026-09-01
- **Deciders:** product owner (rifki) + tech-lead review
- **Related:** supersedes [0012](0012-flatten-org-tree-remove-branch.md); amends [0045](0045-qris-modes-and-payment-service.md) (DIVISION scope removed) and [0018](0018-org-unit-hard-delete-empty-only.md); builds on [0021](0021-multi-company-ownership.md) (one login, N companies); [EVENT-CATALOG](../EVENT-CATALOG.md) `CompanyCreated` / `OrgUnitCreated` / `OrgUnitChanged` / `OrgUnitDeleted`; ARCHITECTURE.md §2 (org-service)

## Context

[ADR 0012](0012-flatten-org-tree-remove-branch.md) removed `BRANCH` and left
`business_unit > outlet > team`. One layer of that survives only as ceremony:

- A **`BUSINESS_UNIT`** ("Division" in the console — `org.type.BUSINESS_UNIT`) exists so outlets have
  a parent and so the `vertical` has somewhere to live. Nothing operational binds to it. Sales, menus,
  tables, bills, register sessions, labor allocation and per-outlet revenue all key on the OUTLET id
  (ADR 0012 guaranteed this). Every read that needs a vertical resolves it by a **parent self-join**
  (`OrgUnitRepository#findActiveOutlets`).
- **`TEAM`** is near-dead: finance's `UnitPnlReader` skips it, org-service's `OrgUnitUsersReader`
  rejects it, and it appears nowhere but the org-tree UI.
- Signup and the onboarding wizard ask for a **company name AND a "first business" name** — two names
  for one thing — and the "Add" wizard makes the owner choose *separate company vs division of X*
  before it will do anything.
- [ADR 0045](0045-qris-modes-and-payment-service.md)'s amendment added a **DIVISION** payment-settings
  scope (outlet → division → company), a third resolution rung whose only justification was that the
  layer existed.

The layer therefore costs a hierarchy level, a self-join on the POS's hottest read, a `divisionId`
threaded from `/api/v1/outlets` through the console session into three payment modals, a
company-vs-division decision at the worst possible moment (signup), and per-BU fan-out branches in
Dashboard / HR / Payroll / Expenses — and buys nothing.

Meanwhile [ADR 0021](0021-multi-company-ownership.md) already delivers the thing a division layer was
standing in for: **one login owns N companies**, each with isolated books, switchable in two clicks,
with group consolidation for the rollup.

Constraints: event schemas are backward-compatible only (rule 7); all publishing goes through the
transactional outbox (rule 3); production is live. A pre-flight against prod (2026-09-01) found
**one tenant** — `Bara Kebab`, one `BUSINESS_UNIT` (`restaurant`), one `OUTLET`, **zero** `TEAM` rows,
**zero** DIVISION-scoped `payment_settings` rows, and an empty `user_outlet_assignment`.

## Decision

We will flatten the org tree to **`company > outlet`** — one level, no nesting.

1. **`OrgUnitType` collapses to `OUTLET`.** `BUSINESS_UNIT` and `TEAM` are removed. `parent_id`
   survives as a column but is **always `NULL`**; the parent→child rule machinery
   (`allowedParentTypes` / `canBeChildOf` / `moveTo`) goes away. The column and the Avro `type` field
   are KEPT so downstream read models (`finance.org_unit_ref`, `employee.org_unit_projection`) need no
   schema change.

2. **The vertical moves to the company** — `company.vertical`, REQUIRED and **IMMUTABLE**, exactly
   like `base_currency` and `country` ("Settings live at creation"). One company = one vertical = N
   outlets. The POS outlet read stops self-joining. A mixed-vertical owner creates a second company
   (ADR 0021), which is what a separate legal entity would need anyway. The column is **nullable in
   the DB and non-null in the aggregate** — the house rule that invariants live in the aggregate, not
   in a CHECK (see the `V6` `org_unit.vertical` comment) — which also keeps `V14` expand-only.

3. **Registration asks for one name.** `firstBusinessName` is removed from `POST /api/v1/signup` and
   `POST /api/v1/companies`; `vertical` moves to the top level of both bodies. The bootstrap seeds
   **one outlet named after the company**, renameable on the Outlets page. Old bodies still carrying
   `firstBusiness` / `firstBusinessName` are **accepted and ignored**, the established pattern here
   (`firstBusinessType`, `baseCurrency`). The onboarding wizard's company-vs-division chooser is
   deleted — "add" always means add a company.

4. **A new `OrgUnitDeleted` event** closes a gap this change would otherwise widen: today a hard
   delete (ADR 0018) leaves inert rows in finance's and employee's cached org trees forever
   (`OrgUnitWriter#delete` documents it as a follow-up). finance and employee consume it idempotently
   and purge the ref row.

5. **The flattening runs as a one-shot idempotent reconciler in org-service, not in Flyway.** Flyway
   (`V14`) only adds and backfills `company.vertical`. The reconciler then, per tenant in ONE
   transaction, reparents each outlet to `NULL` (emitting `OrgUnitChanged` `MOVED`), deletes the
   `BUSINESS_UNIT` and `TEAM` rows (emitting `OrgUnitDeleted`), and is guarded by a marker row so a
   restart is a no-op. Every state change is published through the outbox in the same transaction
   (rule 3) — hand-serialised Avro inside a `.sql` file would not be.

6. **Payment settings resolve outlet → company** (two rungs). The DIVISION rung and the `divisionId`
   wire field are removed end-to-end. Prod carries zero DIVISION-scoped rows, so there is no data
   migration; the `org_unit_id` column keeps its scope-agnostic V4 shape.

Out of scope: grouping outlets for reporting (multi-company + the existing consolidation group serve
it), per-outlet verticals, and any change to ADR 0021's multi-company mechanics — the multi-valued
`company_id` claim, `X-Company-Id` selection, and `/companies/mine` are unchanged.

## Consequences

- **One concept per physical location, and one name at signup.** The signup form loses a field, the
  add-flow loses a decision, and the console's Organization page becomes a flat Outlets list — no
  tree, no parent picker, no type picker, no move.
- **The POS outlet read loses its self-join**; the vertical is read from the company the session
  already holds. `session.divisionId` and `OutletSummary.divisionId` are deleted.
- **Event contracts stay backward compatible (rule 7).** `OrgUnitCreated`/`OrgUnitChanged` change only
  their `doc` prose — `type` is a free string that is now always `"OUTLET"`, and `parent_id`/`vertical`
  are already nullable-with-default. `CompanyCreated` gains `vertical` as a **nullable, defaulted,
  LAST** field (positional-decode safety, the same rule `OrgUnit.vertical` follows).
- **Deploy order is load-bearing.** The `OrgUnitDeleted` consumers (finance, employee) must be live
  BEFORE org-service runs the reconciler, or its events land in the DLT. Ship P1 alone first.
- **The `V14` backfill must defeat FORCE RLS.** `company` and `org_unit` are `FORCE ROW LEVEL
  SECURITY`, so a bare Flyway `UPDATE` matches zero rows silently; the migration wraps the backfill in
  `NO FORCE` … `FORCE` (the trap this repo has hit before).
- **`V14` is expand-only, per the ADR 0057 migration-safety gate.** It only ADDs the nullable
  `company.vertical` and backfills it — no `SET NOT NULL`, no `DROP COLUMN`. `org_unit.vertical` and
  the now-unused parent machinery are contracted away in a LATER release, once no deployed image
  reads them. The gate would otherwise fail the build, and rightly: an app-tier rollback must still
  be able to run against the new schema.
- **What is lost:** a company can no longer mix verticals, and outlets can no longer be grouped under
  an intermediate node for reporting. Both are recoverable — as separate companies under one login,
  rolled up by group consolidation.
- **Irreversible on prod data.** The `BUSINESS_UNIT` row is deleted, not deactivated. A DB backup
  precedes the deploy; the reconciler itself is re-runnable and idempotent.
- Follow-up: `restaurant-service` still trusts the client's `businessId` (ADR 0012's open item). With
  a flat tree "is this an OUTLET" is no longer a question — every org unit is one — so the hardening
  reduces to "does this org unit exist in my tenant", still needing an org-unit read model there.
