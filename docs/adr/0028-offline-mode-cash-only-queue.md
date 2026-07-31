# 28. Offline mode — cash-only client queue replayed through the online checkout

Date: 2026-07-31

## Status

Accepted

## Context

Phase 5 of the POS-parity program. Indonesian SME connectivity is intermittent; a POS that stops
selling when the network drops is unusable in exactly the moments it is needed. Odoo-class POS
keeps selling offline and synchronizes later. Native's checkout endpoints are already idempotent
(`(company_id, idempotency_key)` UNIQUE, REQUIRES_NEW create, conflict re-read), every price is
re-resolved server-side, and the GL period derives from the sale's `occurred_at`. The design
question is how much of the online feature set follows the POS offline, and who owns pricing truth
while the server is unreachable.

## Decision

1. **Offline replay is just a retry of the existing checkout.** Sales made offline are queued in
   the browser (IndexedDB) with an idempotency key **minted and persisted at enqueue time**, then
   replayed serially FIFO through the *unchanged* checkout endpoints. Exactly-once is the
   server's existing idempotency uniqueness — a crash mid-replay retries with the same key; a 409
   on replay means the sale already landed and is treated as synced. No new endpoint, no new
   event, no finance change.
2. **Offline is CASH-only, quick-sale-only.** No open bills, parking, voids, refunds, coupons,
   points redemption, or gift cards offline — each of those consumes shared server state that
   cannot be checked from a disconnected device (coupon slots, cached balances, bill state).
   Loyalty **earn** attribution is allowed (`loyaltyMemberId` rides the queued payload — memo-only
   downstream, harmless to defer). The server enforces the same matrix: an offline-replay checkout
   carrying a non-cash tender, coupon, redemption, or gift card is rejected 422.
3. **Two request fields, strictly bounded.** Checkout DTOs (all three verticals) gain
   `offlineReplay: Boolean` and `clientOccurredAt: Instant`. `clientOccurredAt` is accepted ONLY
   with `offlineReplay=true`, bounded ≤48h in the past and ≤5min in the future, and becomes the
   sale's `occurred_at` — the GL period reflects the day the sale happened, not the day it synced.
   Beyond the bounds the replay is rejected (422) and stays visible in the device's sync center
   for manual review; money is never silently re-dated.
4. **Provisional client pricing, authoritative server repricing.** The device prices offline sales
   from a TTL-cached catalog plus a new lightweight per-vertical
   `GET …/pricing/effective-rules` snapshot (tax/service-charge basis points + provenance),
   mirroring the server formula **including HALF_EVEN rounding**; a committed JSON parity fixture
   is asserted by BOTH a server-side JUnit test and a client-side vitest test, so formula drift
   breaks a build instead of a drawer count. Screens and receipts label offline totals
   *provisional*. On replay the server reprices as always; a grand-total mismatch is recorded as
   `SYNCED_WITH_MISMATCH` and surfaced in an end-of-day report — visibility, never a silent
   overwrite in either direction.
5. **Stock on replay: accept and flag, never reject.** A replayed sale that would oversell stock
   is recorded anyway (the cash is already in the drawer); the shortfall is written to the
   vertical's error-log as a discrepancy for repair by count. Online checkout keeps rejecting.
6. **The service worker never caches `/api`.** `vite-plugin-pwa` precaches the app shell only;
   API traffic is network-only by construction. Offline data lives exclusively in the explicit
   IndexedDB queue/catalog cache, guarded by `navigator.storage.persist()` and a Web Locks mutex
   so multiple tabs cannot double-replay.

## Consequences

- Connectivity loss no longer stops cash sales; the queue survives restarts and replays without
  double-posting, and every anomaly (price mismatch, oversell, out-of-bounds date, rejection) is
  visible rather than silently absorbed.
- Provisional receipts can differ from the authoritative repriced total (rule changes inside the
  offline window). Accepted: labeled on receipt, reported end-of-day.
- A lost/wiped device before sync loses unrecorded cash sales. Mitigated (persistent storage,
  visible queue badge), not eliminated — documented residual.
- `clientOccurredAt` trusts the device clock within 48h/5min bounds; skew inside the window can
  shift a sale's day. Accepted for SME scale.
- Any terminal signed into the company can replay its own queue only; queues are device-scoped
  and never shared. A cashier's replayed sale still passes the same server-side guards (outlet
  assignment, roles) as an online sale — reviewed by security alongside this phase.
