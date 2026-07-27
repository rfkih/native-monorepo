# 0012. Flatten the org tree: remove BRANCH and seed a default outlet per business unit

- **Status:** Accepted
- **Date:** 2026-07-27
- **Deciders:** product owner (rifki) + tech-lead review
- **Related:** [EVENT-CATALOG](../EVENT-CATALOG.md) `OrgUnitCreated`/`OrgUnitChanged`, [0003](0003-single-source-event-schemas-libs-contracts.md), ARCHITECTURE.md §2 (org-service), outlet-scoping increment (phases 1–5, DEVLOG 2026-07-27)

## Context

The org tree was `business_unit > branch > outlet > team`, with one sanctioned skip (an outlet
directly under a business unit). For Native's target customers a "branch" and an "outlet" are the
same physical concept — a selling location — and nothing operational ever bound to a BRANCH: the POS
picker lists only OUTLET-type units, cashier assignments require OUTLET, labor cost allocates per
outlet, finance's per-outlet revenue keys on the outlet id. BRANCH existed only as an optional
grouping layer, yet the org-tree UI and the signup flow both offered it, forcing users to choose
between two names for one thing.

Worse, company bootstrap created **zero outlets** (only the root business unit), so the console
silently fell back to keying POS sales — and menus, tables, orders, bills — on the **business-unit
id**. Those sales landed in the per-outlet revenue read model under a non-outlet key, permanently
polluting outlet-level reporting (accumulator rows cannot be re-attributed).

Constraints: event schemas are backward-compatible only (rule 7), but the events' `type` field is a
free Avro string — removing a value changes no schema structure. The product is pre-GA: all commits
are local, all data is disposable local-dev data.

## Decision

We will flatten the tree to **`business_unit > outlet > team`**: delete `BRANCH` from `OrgUnitType`
(OUTLET's only legal parent becomes BUSINESS_UNIT). Every new business unit — at company bootstrap
AND via add-business — atomically seeds **one default OUTLET named after the business unit**,
emitting its own `OrgUnitCreated` through the outbox in the same transaction (rule 3). The console
gates the POS/Menu/Kitchen surfaces on outlet presence and **never falls back to the business-unit
id**. The ignored `firstBusinessType` field is removed from the signup/create-company APIs (unknown
JSON fields remain accepted and ignored). UI terminology stays "Outlet" (EN) / "Gerai" (ID).

Out of scope: a regional grouping level (multiple business units serve that need for now), and
server-side validation in restaurant-service that a client-supplied `businessId` is an OUTLET (it
has no org-unit read model — noted as a hardening follow-up).

## Consequences

- One concept per physical location; the org UI and signup stop offering a dead choice.
- The POS always binds a real outlet, so `SaleRecorded.business_id` — and therefore finance's
  `outlet_revenue` and `/pnl/outlets` keys — are genuine outlet ids from a tenant's first sale.
- No event-schema migration: `type` stays a free string; only Avro `doc` strings and catalog prose
  change. Consumers (employee `org_unit_projection`, finance `org_unit_ref`) store `type` opaquely
  and are unaffected.
- Existing local-dev tenants must be reset (or given an outlet via one `POST /api/v1/org-units`
  call per business unit) — documented in RUNBOOK; historical business-unit-keyed rows remain as
  cosmetic artifacts, acceptable pre-GA.
- Follow-up: restaurant-service still trusts the client's `businessId`; enforcing type=OUTLET
  server-side needs an org-unit ref read model there.
