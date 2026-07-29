# 0018. Permit hard-deleting an empty org unit; accept the owner-rung-sales orphan risk

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** rifki (owner), Claude (agent)
- **Related:** [ADR 0012](0012-flatten-org-tree-remove-branch.md) (flat tree, default outlet per BU);
  hard rules 1 (database-per-service), 2 (no synchronous calls between business services), 5 (RLS);
  `OrgUnitWriter.delete`, `OutletAccessGuard` (restaurant-service).

## Context
The org tree only supported soft removal (`deactivate`, which cascades and emits `OrgUnitChanged`).
Owners asked to permanently remove a **unit created by mistake** (a stray business unit — which
auto-seeds one outlet, ADR 0012 — or an extra outlet). Deactivate leaves the clutter in the tree
forever; a true delete is wanted for genuine mistakes only.

The safety requirement is "only delete a unit with no real data" — no assigned login, no employees,
no sales/revenue. But org-service **cannot** authoritatively check the last two:

- **Employees** live in employee-service and **sales/revenue** in restaurant-service + finance-service.
  Rule 2 forbids a synchronous cross-service check, so at delete time org-service can only read data
  it owns locally.
- The only sales-linked signal org-service owns is `user_outlet_assignment` — a login assigned to an
  outlet is one a cashier could have rung sales at. But `OutletAccessGuard` lets **owner/manager ring
  sales with no assignment row** (unconditional bypass) and grandfathers tenants with zero assignment
  rows. So an outlet can have real `SaleRecorded` → finance postings yet **no** assignment row.
- Finance exposes P&L only per `YYYY-MM` period, so even the console cannot cheaply prove "never had
  any revenue" across all time.

Options considered: (a) ship with the local login guard + honest docs and accept the residual gap;
(b) add an all-time "has-any-revenue" finance endpoint the console calls before offering delete;
(c) emit an `OrgUnitDeleted` event and have finance/HR/restaurant purge their read models.

## Decision
We will allow a hard `DELETE /api/v1/org-units/{id}` that removes the unit and its **entire subtree**
in one RLS-scoped transaction, guarded by a **best-effort local check**: reject (409
`org-unit-has-data`) any unit or descendant that has — or ever had — an assigned login (closed rows
count; an unassigned login keeps its row). An unknown/cross-tenant id is a 404 (anti-enumeration).
The console additionally blocks on employees (scoped to the whole subtree) and active logins before
offering the action, failing **closed** if those cross-service reads error.

We explicitly **accept** the residual risk that an outlet with owner/manager-rung sales and no
assignment row can be deleted, orphaning finance's per-outlet dimension rows. This is chosen over
options (b)/(c) for now: the money itself (ledger, hash-chained log, company-level totals) is
untouched — only a per-outlet label goes stale — the scenario is rare (a mistaken unit the owner
nonetheless rang sales at), and **deactivate remains the fully-safe path** the UI steers toward.
Out of scope: no `OrgUnitDeleted` event, no finance revenue endpoint, no delete of a non-empty unit.

## Consequences
- **Easier:** owners can clean up mistaken units; the tree stays tidy. Delete cascades the whole
  subtree so org-service leaves no dangling `parent_id` (there are no intra-schema FKs — the
  invariant is enforced in `OrgUnitWriter.delete`, not the DB).
- **Harder / cost:** the guard is not a guarantee, and the code/exception/console copy say so
  plainly (no overclaim). Deleting a subtree that downstream services still cache leaves **inert**
  `org_unit_ref` / per-outlet read-model rows in finance/HR/restaurant; combined with the accepted
  owner-rung-sales case those refs can be non-inert (a revenue row whose outlet label is gone).
- **Enforcement:** the login guard is a native `existsByOrgUnitId` inside the delete transaction
  (RLS-scoped, rule 5); the 409/404 shapes are RFC-7807 (`OrgUnitExceptionAdvice` +
  `UserExceptionAdvice`); web-slice + RLS-backed tests cover 204/409/404, the closed-row guard, the
  subtree cascade, and cross-tenant invisibility.
- **Follow-ups (deferred, tracked here):** emit an `OrgUnitDeleted` event so finance/HR/restaurant
  purge/reconcile their read models (option c) — the real fix for both the inert-ref cleanup and the
  accepted orphan risk; and/or an all-time finance "has-any-revenue" check for a stronger console
  guard (option b). Revisit if hard-delete sees real use beyond mistake cleanup.
