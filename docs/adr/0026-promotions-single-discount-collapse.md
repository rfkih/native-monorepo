# 26. Promotions — per-vertical rules and coupons that collapse into one discount

Date: 2026-07-30

## Status

Accepted

## Context

Phase 3 of the POS-parity program. The only price reduction any vertical supports is a single
manual order-level discount amount typed by the cashier — no automatic rules, no happy hour, no
coupon codes, and (since the cashier types it freely) no control over who discounts what. Odoo-class
POS needs a promotions engine. The finance side, however, already handles a discount perfectly: the
`SALES_DISCOUNT` contra-revenue leg posts `SaleRecorded.discount_minor`, read models net it out, and
the reconciliation identity (`subtotal − discount + service_charge + tax == amount`) is enforced at
the consumer. The design question is where promotion complexity lives.

## Decision

1. **Every promotion collapses into the existing `discount_minor`.** Automatic rules, coupons, and
   the manual cashier discount are composed by a per-vertical `PromotionEngine` whose single output
   feeds `TaxChargeService.resolve` exactly where the raw manual discount fed it before. **Zero
   Avro changes, zero finance changes** — finance keeps posting one aggregate discount and never
   learns promotions exist. Per-rule detail is a *vertical* audit concern: an `applied_promotion`
   row per deduction (rule snapshot — name/type/rate — so later rule edits don't rewrite history),
   written in the same transaction as the order/ticket.

2. **Promotion data is per-vertical-service** (`promo_rule`, `coupon`, `applied_promotion` in each
   of restaurant/carwash/barbershop — DB-per-service; each vertical owns its pricing). A company
   running two verticals configures a campaign twice; a shared promo service would need a
   cross-service catalog, which rule 1 forbids. Accepted, documented.

3. **Deterministic composition order:** (1) line-scope rules (`PERCENT_OFF_LINE` on ITEM/CATEGORY
   scope; deduction clamped ≤ line total; the SUBTOTAL stays gross — line discounts are components
   of the order discount, so finance semantics are unchanged) → (2) order-scope automatics by
   `priority` (an `exclusive` rule stops further automatics; `min_subtotal_minor` gates;
   `requires_coupon` rules never fire automatically) → (3) at most ONE coupon → (4) the manual
   discount last → (5) the total clamped ≤ subtotal. Happy hour is not a rule type: optional
   `dow_mask`/`window_start`/`window_end`/`tz` columns on *any* rule, evaluated against the moment
   money moves. `BUY_X_GET_Y` is schema-reserved but not in the engine (a later slice; the admin
   API cannot create it yet).

4. **Promotions evaluate when money moves, not when a cart is parked.** A parked order re-evaluates
   at pay time (a happy-hour rule that has since ended no longer applies); the quote endpoint
   reports what *would* apply — including a non-throwing `couponStatus` so a mistyped code degrades
   to information, not an error. Coupon redemption is a single atomic
   `UPDATE … SET redeemed_count = redeemed_count + 1 WHERE redeemed_count < max_redemptions AND
   active` inside the checkout transaction — zero rows → 409 and the whole checkout rolls back; an
   idempotent checkout replay short-circuits before the engine and never redeems twice.

5. **The manual discount becomes owner/manager-only** (403 for a cashier role; the headerless
   dev/test pass-through of the staff-profile precedent). Rule/coupon administration is
   owner/manager both at the gateway (`/api/v1/promotions/**`, DASHBOARD_ROLES, restaurant) and
   service-side (the vertical-prefixed admin endpoints).

## Consequences

- Finance, the event catalog, and every consumer are untouched — the phase is POS-local.
- Per-campaign profitability reporting is vertical-local (from `applied_promotion`), not in the
  consolidated P&L (which sees one aggregate discount). A follow-up reporting slice can build on
  the audit rows.
- Guest-tab bills get automatics + manual discount only; coupons on bills are a follow-up.
- Coupon codes are only as strong as merchants make them; the guard is the per-tenant
  authenticated surface + rate limiting (noted for the security review).
- A DIGITAL-tender checkout redeems the coupon at checkout time, before capture: an abandoned
  digital checkout permanently consumes a redemption slot (conservative — it can only
  over-count, never grant more than `max_redemptions`; security review S-2). A follow-up may
  release the slot on void.
- The engine is cloned per vertical like the POS itself — restaurant first, then the ticket
  verticals; divergence risk is handled the same way (clone + adversarial review).
