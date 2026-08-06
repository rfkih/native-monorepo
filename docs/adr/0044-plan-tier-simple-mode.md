# 44. Plan tier & Simple mode — UMKM feature curation

Date: 2026-08-06

## Status

Proposed

Composes with [ADR 0013](0013-per-login-page-grants-subtractive-ui.md) (per-login subtractive page
grants). Precedes the Android till app (ADR 0043, reserved), which renders the live console and
therefore inherits this simplification as its first impression.

## Context

Native's primary audience is Indonesian UMKM. The full console — a complete accounting suite,
HR/payroll, promotions/loyalty, org trees, platform settlements — is overwhelming for a warung
owner who needs a till, a product list, receipts, and today's sales. Competitors on-board small
merchants into a deliberately small surface and sell the back-office as an upgrade. We want the
same shape: a **FREE tier showing only the essentials, a paid tier showing everything** — but
today, before billing exists, everything stays free and an owner simply toggles the full view on.

## Decision

1. **The tier lives on the org-service `company` row**: `plan_tier VARCHAR(16) NOT NULL DEFAULT
   'FULL'` (V10), domain `FREE | FULL` whitelisted in the `Company` aggregate (no CHECK — the V9
   `country` / V6 `vertical` pattern). It is a **tier, not a boolean**, so `PRO`/`ENTERPRISE` rank
   in later without migration. The console receives it on the session read it already makes
   (`/companies/mine` → `CompanyCurrentView` → `CompanyResponse`) — zero new round-trips, no new
   service edge, no event (nothing for the catalog).
2. **Setter**: `PUT /api/v1/companies/current/plan-tier`, **owner-only, enforced server-side**
   (422 on a non-whitelisted value, 403 for non-owners). Today the setter is the settings toggle;
   when real billing arrives it replaces the *setter* (webhook/event → org-service) while the
   *reader never changes* — the rework-avoidance property this design buys.
3. **Gating is UI curation, composed with page grants**: one console map
   (`lib/featureTier.ts`: `FeatureKey` → minimum tier) and one predicate; a surface is visible iff
   `role ∧ page-grant ∧ tier`. An owner bypasses page grants (ADR 0013) but **not** tier — the
   escape hatch is the always-visible owner-only `/settings/features` toggle. The Dashboard is
   never tier-hidden (it simplifies in FREE) so `home` always resolves. Missing/unknown tier
   **fails open to FULL** — a read gap must never hide features.
4. **Defaults**: existing companies grandfather to `FULL` (column default — no regression); new
   signups start `FREE` (create paths write it explicitly — lands in P2 with the friendly
   locked-page screens so a deep link never dead-ends).
5. **FREE set (initial)**: POS (incl. daily register close), products/menu, kitchen display,
   printer settings, simplified dashboard, expenses-lite, team basics, `/me`, add-business, the
   Features toggle. Everything else (`statements`, `accounting`, `promotions`, `channels`,
   `orgStructure`, `customerDisplay`, `hr`) requires FULL. Judgment lines the owner may flip by
   moving one map entry: `expenses` (default FREE), `channels`, income statement,
   `customerDisplay`.

## Consequences

- **Not a security boundary** — stated deliberately: tier gating hides UI for endpoints the
  login's role already reaches; roles at the gateway remain the only API authorization. A future
  *paid* denial is a new server-side gate at the entitlement/gateway layer driven by a
  `PlanTierChanged` event (P3, own contract + sign-off per rule 7); it is not an extension of the
  UI map. Same posture ADR 0013 took for page grants.
- A FREE company's sidebar collapses to the essentials; switching companies re-derives the nav
  (tier is per active company). The toggling owner sees the flip immediately (companies query
  invalidated); other logins pick it up on the next session refetch.
- CDC captures tier flips (rule 4); not money, so no hash-chain entry.
- The Android till app inherits Simple mode for free — a new UMKM's first app experience is the
  small surface.
