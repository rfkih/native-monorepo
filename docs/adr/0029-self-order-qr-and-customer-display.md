# 29. Self-order QR — an anonymous surface whose blast radius is a parked row

Date: 2026-07-31

## Status

Accepted

## Context

Phase 6, the POS-parity program's final phase. Odoo-class POS offers a customer-facing display
and QR self-ordering. Everything Native has built so far sits behind Keycloak; self-order is
necessarily ANONYMOUS — a diner scans a table tent and orders from their phone. The design
question is how to admit an unauthenticated caller into a strictly-tenanted system without
creating a money- or data-shaped attack surface.

## Decision

1. **The anonymous surface can do exactly two things:** read the outlet's menu, and create a
   **PARKED order tagged `source=SELF_ORDER`**. Parking writes no sale, no payment, no outbox
   event, and no stock movement — the worst an abuser can achieve is junk parked rows. The
   cashier confirms the order in the ParkedTray (badged, with the table label) and takes payment
   through the normal, fully-guarded flow. Anonymous *payment* is explicitly deferred (needs a
   PSP-hosted page — ADR 0007 residual).
2. **Per-outlet HMAC token, revoked by rotation.** The QR carries a compact token:
   `base64url(payload).base64url(HMAC-SHA256(outletSecret, payload))` with claims
   `{v, kid, companyId, businessId, outletId, tableLabel}` (`tableLabel` null = kiosk). **No
   expiry** — a table tent cannot refresh itself; revocation is rotating the outlet's secret
   (new `kid`, old row RETIRED), which kills every printed QR at once. Secrets are AES-256-GCM
   encrypted at rest (`self_order_access`, one ACTIVE row per outlet, RLS like every table);
   signature checks are constant-time. A `SelfOrderTokenFilter` validates the token and binds
   the TenantContext from the VERIFIED row — actor `self-order:{table}` — so RLS and every
   existing tenant guard apply unchanged downstream.
3. **The gateway treats it like signup, not like POS.** `/api/v1/self-order/**` is the fleet's
   second anonymous route: no JWT, `AnonymousRateLimitFilter` (fail-closed, the signup
   precedent), client-supplied tenant headers stripped, the self-order token forwarded opaquely.
   Entitlement key `self_order` gates the feature per company (module catalog + default grant).
4. **Bounded junk.** Unconfirmed self-order rows are capped (50 per outlet → 429) and swept
   after 30 minutes. A photographed QR works until rotation — accepted: rate limits + cap +
   sweep + zero-money radius bound the damage, and rotation is one button.
5. **Customer display is a zero-backend feature.** A second authenticated console route
   (`/pos/customer-display`) mirrors the cashier's cart/payment state over a typed
   `BroadcastChannel` (`pos-display:{outletId}`) — same machine, second screen, no network, no
   new API. Display locale defaults to Bahasa Indonesia.

## Consequences

- Ordering works from any phone with no app install and no account; the kitchen/cashier flow is
  unchanged (a self-order is just a parked order with a badge).
- The menu is intentionally readable by anyone holding a valid token — that is what a menu is.
  Nothing else is: cross-outlet/rotated/garbage tokens are 401, and the tenant context comes
  only from the verified secret row, never from the caller.
- A stolen *secret* (not token) would allow minting valid tokens for that outlet until rotation;
  secrets live encrypted, are never returned by any API after mint, and rotate in one action.
- Self-order rows dilute the ParkedTray under abuse (bounded by cap + sweep); the cashier's
  confirm step is the human firewall before anything touches money.
- The customer display trusts the same browser profile; it shows only what the cashier already
  sees. No PII crosses the channel (cart lines and totals only).
