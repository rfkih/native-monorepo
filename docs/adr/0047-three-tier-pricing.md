# 0047 — Three-tier pricing: Gratis / Basic / Premium + usage add-ons (display & soft-enforce phase)

Status: Accepted (2026-08-08)

## Context

[ADR 0044](0044-plan-tier-simple-mode.md) shipped `company.plan_tier` as a rankable tier
(`FREE | FULL`) with an owner-only setter and console-side feature curation, deliberately
leaving billing for later. The owner now wants a sellable pricing architecture: a free tier,
two paid tiers, and usage-based add-ons — priced against the Indonesian UMKM market where
POS-only products charge Rp 249–299rb/outlet/month (Moka, majoo, Pawoon) and merchants
assemble accounting (Mekari Jurnal ~Rp 249–699rb) and payroll (Gadjian ~Rp 12,5rb/employee)
separately. Native bundles all three.

There is still no SaaS-billing infrastructure (payment-service handles CUSTOMER QRIS
payments, not subscriptions; entitlement-service's `billing_line` table is modeled but has
no rating engine). Owner decisions: **show + soft-enforce** — compute and display the real
monthly price from actual usage, warn when usage exceeds the tier, never hard-lock;
collection stays manual while merchant count is small.

## Decision

**The price sheet** (single adjustable config, `frontend/console/src/lib/pricing.ts`,
integer IDR minor units):

| | Gratis (`FREE`) | Basic (`BASIC`) | Premium (stored `FULL`) |
|---|---|---|---|
| Base / month | Rp 0 | Rp 149.000 | Rp 299.000 |
| Outlets included | 1 | 2 | 2 |
| Additional outlet | — (upgrade) | +Rp 49.000/outlet/mo | +Rp 49.000/outlet/mo |
| Employees included | 10 | 10 | 10 |
| Additional employees | — | +Rp 50.000 per started 20-pack | +Rp 50.000 per started 20-pack |

`total = base + extraOutlet×max(0, outlets−included) + pack×ceil(max(0, employees−10)/20)`

**Feature split — "jalankan toko" vs "jalankan perusahaan"** (existing `FeatureKey`s, no new
keys): `FREE` keeps ADR 0044's curated set (pos, products, kitchen, printer, dashboard,
expenses, team); `BASIC` adds the remaining operational-POS surface (promotions, channels,
customerDisplay, orgStructure); `FULL` adds everything financial (statements, accounting,
hr).

**Naming**: the stored third-tier value stays the string `FULL` — grandfathered rows and the
console's fail-open `toPlanTier` (unknown → FULL) already produce it; only its display name
becomes **Premium** (i18n). `BASIC` is a new stored value in `Company.PLAN_TIERS` and the
console ladder (`TIER_RANK FREE:0 < BASIC:1 < FULL:2`). No migration — V10's VARCHAR +
whitelist-in-aggregate was designed for this.

**Signup default becomes FREE** (delivers ADR 0044 D4/P2): every company-create path starts
the company at `FREE` explicitly; pre-existing rows keep the V10 grandfather default `FULL`.

**Surfaces**: `/settings/features` becomes the plan page — three derived plan cards (from
`FEATURE_MIN_TIER`, never hand lists), owner-only tier selection via the unchanged
`PUT /api/v1/companies/current/plan-tier`, and a live price panel computing the breakdown
from actual outlet count (`GET /api/v1/outlets`) and distinct employee count
(`GET /api/v1/employees`). Over-limit usage shows an amber callout (and an upgrade prompt on
FREE) — nothing blocks. The public landing gains a `#harga` pricing section rendered from
the same config.

## Consequences

- Prices change in one file; the formula is pure and unit-tested. When real billing arrives,
  the price sheet moves server-side and the setter is replaced by the billing webhook/event —
  the reader path (ADR 0044's design) never changes.
- Soft enforcement means a FREE company can operationally exceed its included counts — the
  plan page says so and prompts an upgrade; acceptable while collection is manual.
- Existing paying-intent tenants (grandfathered FULL) see a price on the plan page for the
  first time; no behavior change until they flip tiers.
- Employee count is derived client-side by de-duplicating the employee×assignment list rows;
  a cheap server count endpoint is future work if the list grows heavy.

## Out of scope (future, in order)

Rating engine on entitlement-service `billing_line`; invoice generation + payment
collection; proration; `PlanTierChanged` event + server-side entitlement gate (ADR 0044 P3);
hard caps; annual-billing discounts (sales-side for now).
