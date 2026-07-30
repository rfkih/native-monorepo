# 23. Carwash vertical POS — the ticket flow, vertical path prefixing, and PII-free staff attribution

Date: 2026-07-30

## Status

Accepted

## Context

Phase 1 of the POS-parity program (CLAUDE plan `wise-frolicking-muffin`). The org model has carried
`vertical = restaurant | carwash | barbershop` since ADR 0012, and carwash-service has produced
`SaleRecorded` since the entitlement slice — but only as a bare `POST /api/v1/washes` (no catalog,
no pricing, no tenders, not even a gateway route), so the console gated carwash outlets behind a
"coming soon" panel. To sell at a carwash outlet the vertical needs a real POS: priced offerings,
the tax/service-charge pipeline, cash + flagged-pending digital tenders, outlet-assignment
enforcement, and washer commission attribution — restaurant already solved each of these, so the
question is how much of restaurant's shape to reuse and where the carwash domain genuinely differs.

## Decision

1. **Quote → one-shot `ticket` checkout; no park/tabs/bills/KDS.** A carwash sells a package +
   addons per bay; there is no course-firing, no split-by-item, no kitchen. The `ticket` aggregate
   (V6) mirrors restaurant's order+payment shape where concepts overlap and drops the rest. The
   idempotency contract is `WashService`'s, verbatim: create in `REQUIRES_NEW`, conflict on
   `(company_id, idempotency_key)` recovered by a fresh-transaction re-read, exactly one
   `SaleRecorded` per key.

2. **Revenue recognised at capture (ADR 0006), preserved in one-shot form.** CASH authorizes
   synchronously inside checkout: ticket + lines + payment + `SaleRecorded` + metrics commit in one
   transaction. QRIS/CARD (flagged-pending, ADR 0007 still open) persist a PENDING payment with
   `ticket.sale_id NULL` and **no event**; `POST /tickets/{id}/capture` records the sale (idempotent:
   already-captured → 200 no-op). An abandoned digital ticket never produces revenue.

3. **Vertical path prefixing: `/api/v1/carwash/**`.** Restaurant's unprefixed routes (`/orders`,
   `/menu`, …) are grandfathered; every vertical from carwash on prefixes its whole surface (the
   `/api/v1/ap/**` precedent applied at vertical scope) so verticals can never collide with each
   other or with restaurant. One gateway route bean per vertical, POS_ROLES, the standard
   RateLimit → RoleAuthorization → TenantContextHeader chain. Legacy `POST /api/v1/washes` stays
   un-routed and untouched (deprecated in OpenAPI only) — its tests double as the
   backward-compatibility proof.

4. **Washer attribution is a vertical-local `staff_profile`, not HR data.** Events carry no PII
   (rule 6), so the vertical cannot display names from its `staff` read model, and cashiers cannot
   reach `/api/v1/employees` (DASHBOARD_ROLES). A `staff_profile` row is a tenant-entered
   `display_label` + an OPTIONAL `employee_id` link (made on the dashboard, where the employees
   list is reachable). Checkout snapshots the link onto `ticket.washer_employee_id` and emits
   `sales_amount` @ **employee** grain with the washer as subject — feeding the existing
   `PERCENT_OF_METRIC` commission — **skipped when unlinked**. The existing OUTLET-grain
   `wash_count`/`upsell_amount` metrics are kept unchanged. Note the deliberate difference from
   restaurant: there the metric subject is the *cashier* (the bound actor); here it is the *washer
   who did the work*.

5. **Carwash tax regime is SME-gated ILLUSTRATIVE.** V5 mirrors restaurant's `tax_charge_rule`
   column-for-column and seeds one `VAT_CARWASH` row at 1100 bp with
   `provenance = ILLUSTRATIVE_PLACEHOLDER`: whether carwash services fall under national PPN or a
   regional levy is unconfirmed. No SERVICE_CHARGE rule is seeded — the resolver's no-rule
   fall-through yields zero. `uses_illustrative_rules` propagates onto `SaleRecorded` exactly as
   restaurant's does.

6. **Zero event-schema changes.** The carwash ticket populates the already-nullable breakdown +
   `tender_type` fields on `SaleRecorded` (carwash stops being a null-breakdown-only producer);
   attribution rides `MetricPublished.subject_id`. Finance consumes the richer producer with zero
   changes — the tender-routed clearing legs and breakdown legs are already generic.

7. **One reusable console surface (`features/servicepos/`)** configured per vertical (catalog
   labels, location field, attribution requirements) instead of duplicating the 1500-line
   restaurant `Pos.tsx`; a `PosSwitch` at `/pos` picks restaurant `Pos` | `ServicePos(config)` |
   coming-soon by the effective outlet's vertical (the fail-open-to-restaurant null handling is
   preserved). Barbershop (Phase 2) is the second consumer of this surface.

## Consequences

- All three verticals will speak the same event contract with no consumer redeploys; finance and
  employee-service are untouched by this phase.
- A carwash company must create catalog rows before its POS sells anything (empty catalog = empty
  grid); packages/addons/washers are OUTLET-scoped.
- Washer commission attribution is only as good as the tenant's profile→employee links; unlinked
  profiles record tickets with no commission metric (documented, visible in the catalog UI).
- The carwash tax amount is illustrative until an SME confirms the regime; every affected
  `SaleRecorded` carries `uses_illustrative_rules = true` and finance flags the postings, so the
  provisional numbers can never be mistaken for filed figures.
- The dev stack now REQUIRES the carwash + entitlement Debezium connectors (docker/README step 3);
  without the entitlement connector the module gate fails closed and every carwash POS call 403s.
