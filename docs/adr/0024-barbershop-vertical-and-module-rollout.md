# 24. Barbershop vertical — cloning the carwash POS shape, and how a new module rolls out

Date: 2026-07-30

## Status

Accepted

## Context

Phase 2 of the POS-parity program. Barbershop has been a declared vertical since ADR 0012
(`org_unit.vertical`), but nothing existed behind it: no service, no POS, and — unlike carwash — no
`barbershop` entry in the entitlement module catalog, so even the gating fabric had nothing to
gate. Phase 1 (ADR 0023) deliberately built the carwash POS as a reusable shape; this phase is the
proof that a third vertical is a clone, not a rebuild. Two questions needed answers: how a NEW
module reaches EXISTING companies (the default-grant set is applied only at `CompanyCreated`), and
where barbershop legitimately differs from carwash.

## Decision

1. **barbershop-service is a copy-with-rename of carwash-service** — same feature packages
   (catalog/pricing/payment/ticket/outletref/entitlement/staff), same idempotency and
   revenue-at-capture contracts, same guards (entitlement before any side effect; outlet guard at
   checkout after the idempotency fast path; staff-profile writes owner/manager-only), same test
   suite. One baseline migration (V1) folds in every lesson (outbox `traceparent` from day one,
   `price_minor` `@AttributeOverride`, FORCE RLS everywhere). Zero Avro changes — the third
   `SaleRecorded` producer, full breakdown + tender.

2. **Domain differences, and only these:** the catalog is `service_item` (+ `duration_minutes INT
   NULL`, persisted but unused — RESERVED for a future appointments app so adding booking later is
   not a schema change) and `service_addon`; the ticket carries `chair` (optional, replacing `bay`)
   and no vehicle plate; **barber attribution is MANDATORY** — `staffProfileId` is required at
   checkout (400 without): every cut has a barber, unlike a wash. The employee LINK stays optional
   (`barber_employee_id` snapshot; the `sales_amount`@employee metric is skipped when unlinked).
   Declared outlet-grain metrics: `service_count` only (no upsell analog). Tax rule
   `VAT_BARBERSHOP`, 1100 bp, `ILLUSTRATIVE_PLACEHOLDER` — the personal-care indirect-tax regime is
   SME-gated exactly like carwash's.

3. **New-module rollout mechanics (the reusable recipe):** a migration inserts the module into
   `module_catalog` (reference data, no RLS) and MUST land in the same release as — and before —
   the `default-modules` config change (`EntitlementService.validateModulesExist` throws on an
   unknown configured key at the first `CompanyCreated` otherwise). NEW companies then receive it
   in the default grant. EXISTING companies are NOT backfilled — a Flyway write into the FORCE-RLS
   `tenant_entitlement` is impossible by design, and a grant that bypassed the service would never
   emit `EntitlementGranted` through the outbox, leaving every vertical's projection stale. They
   self-serve instead: `POST /api/v1/entitlements {moduleKey}` is now gateway-routed
   (`/api/v1/entitlements/**`, DASHBOARD_ROLES) with a console Modules panel on the org page.
   Fail-closed by default: a pre-Phase-2 company gets 403 at the barbershop POS until its owner
   flips the module on.

4. **Console:** the `servicepos` surface gained the per-vertical knobs this clone actually needed
   (catalog path segment, primary item type, required attribution, optional location) rather than a
   fork; `PosSwitch`/`CatalogSwitch` route the barbershop vertical to the shared surface.

## Consequences

- Three verticals now sell through one event contract, one console surface pattern, and one
  gating/guard stack; a fourth vertical is a measured day of cloning, not a design exercise.
- `duration_minutes` sits dormant until an appointments phase; nothing reads it.
- The self-serve grant surface exposes module control to owners — revoking a module a vertical is
  actively using 403s its POS mid-shift (the gate is the point; the console confirms before
  revoke).
- The dev stack needs the barbershop postgres role/DB (init script runs only on fresh volumes —
  existing dev volumes need a manual psql) and the barbershop Debezium connector.
