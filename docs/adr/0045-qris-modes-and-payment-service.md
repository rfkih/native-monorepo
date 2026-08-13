# 0045. QRIS payment modes (manual / static image / Midtrans gateway) via a central payment-service

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ADR 0006](0006-pos-payment-tenders.md) (the tender port this completes),
  [ADR 0007](0007-real-psp-adapter-webhook.md) (superseded by this ADR),
  [ADR 0016](0016-bank-reconciliation.md) (where the QRIS payout lands),
  [ADR 0029](0029-self-order-qr-and-customer-display.md) (the anonymous-edge recipe the webhook copies),
  [ADR 0034](0034-pos-shell-verticals-are-adapters.md) (the console seam),
  [ADR 0036](0036-register-sessions-and-platform-channel-settlements.md) /
  [ADR 0038](0038-daily-close-all-tender-and-inventory.md) (tender reconciliation),
  docs/EVENT-CATALOG.md (`PaymentChargeSucceeded`), CLAUDE.md HR-2/3/5/6/8

## Context

The QRIS tender has been live fleet-wide since ADR 0006 — two-step PENDING→capture, GL clearing
debit to QRIS_CLEARING 1901, per-tender daily-close variance (ADR 0038), refund reversal — but the
rail is a flagged-pending stub (`DigitalProvider`): the system never shows a QR and never confirms
a payment. Indonesian UMKM merchants fall into two camps: those who already have their own printed
static QRIS (NMID registered with their bank/PJP) and just want the till to display it, and those
who want per-transaction dynamic QRIS with automatic confirmation via a payment gateway.

ADR 0007 (Proposed, never built) sketched the gateway half as an adapter *inside each vertical*
plus an in-service webhook. Three verticals now exist (restaurant, carwash, barbershop): that shape
would triplicate the PSP client, the credential store, and the anonymous webhook surface, and each
new provider would touch three services.

Product decisions taken with the owner (2026-08-07): Midtrans is the first gateway (behind a
provider-agnostic port); each company connects its **own** Midtrans account — Native stores the
merchant's server key (encrypted) and never touches funds, so no aggregator/PJP licensing burden;
all three verticals get the feature; the static image is company-level with optional per-outlet
override.

Constraints: DB-per-service and no synchronous business-service calls (HR-1/2) — but the console
calling two services is client-side orchestration, and an outbound PSP call / inbound webhook is an
*integration edge*, the class ADR 0007 already put alongside gateway→Keycloak. Outbox-only
publishing (HR-3), RLS everywhere (HR-5), credentials are secret-grade (HR-6), money in minor units
(HR-8).

## Decision

A company chooses a **QRIS mode**, resolved per outlet (outlet override → company default →
implicit MANUAL):

1. **MANUAL** — today's behavior, unchanged, the default. Cashier confirms with "Mark as paid".
2. **STATIC** — the merchant uploads their static QRIS image (≤ 2 MiB, jpeg/png/webp verified by
   magic bytes, stored bytea, served only through an authenticated blob endpoint). The till's
   pending panel and the customer display show it; capture stays manual.
3. **GATEWAY** — per-transaction dynamic QRIS through the merchant's own Midtrans account.
   Auto-capture on Midtrans's settlement notification.

**A new `payment-service` owns this bounded context** ("how this company takes digital payments"):
the `payment_settings` aggregate (mode + static image + AES-256-GCM-encrypted Midtrans server/client
key, `server_key_last4` the only readable trace) and the `payment_charge` lifecycle
(INITIATED → QR_ISSUED → SUCCEEDED | EXPIRED | CANCELED | FAILED, one live charge per vertical
payment enforced by partial unique index). The Midtrans client sits behind a `QrisGatewayPort` so
further providers are new adapters, not new architecture.

**Gateway flow** — the verticals stay PSP-ignorant:

- Vertical checkout creates the PENDING payment exactly as today (revenue-at-capture invariant of
  ADR 0006 preserved untouched).
- The console (authenticated, client-side orchestration) asks payment-service to create a charge
  (`Idempotency-Key` required; 422 unless currency is IDR; 409 unless effective mode is GATEWAY);
  payment-service calls Midtrans `/v2/charge` (payment_type `qris`, 15-min expiry) and returns the
  `qr_string`, which the till renders and mirrors to the customer display.
- Midtrans notifies `POST /api/v1/psp-webhooks/midtrans/{companyId}` — the fleet's second
  anonymous edge, a faithful copy of the ADR 0029 recipe: anonymous gateway route with its own
  rate-limit bucket + tenant-header strip; service-side provisional tenant bind from the path
  claim, RLS-scoped load of settings + charge (a forged company id sees nothing), constant-time
  sha512 signature verification against that company's decrypted server key, exact
  `gross_amount == "<amount_minor>.00"` check. The company id reaches Midtrans via the per-charge
  `X-Override-Notification` callback URL, so merchants configure nothing.
- On settlement the transition writer flips the charge and writes a **`PaymentChargeSucceeded`**
  outbox row in the same transaction (HR-3). Each vertical consumes it idempotently (filter on
  `vertical`, verify amount/currency, park anomalies in the error inbox) and calls its **existing**
  capture writer — so `SaleRecorded`, finance GL routing, daily close, and refunds need **zero
  change**. A `/sync` endpoint applies the same transition from a server-side Midtrans status poll
  (the no-webhook fallback); `/cancel` cancels at Midtrans, and if Midtrans reports the money
  already settled, capture proceeds instead.
- Canceling a charge leaves the vertical payment PENDING — exactly the MANUAL posture; a
  settlement arriving after a local cancel/expiry is **parked for a human** (refund via the
  merchant's Midtrans dashboard), never auto-captured.

**Settling 1901 to bank**: bank reconciliation (ADR 0016) gains a `QRIS_CLEARING` category with an
optional fee leg — `Dr BANK (net) + Dr QRIS_FEE_EXPENSE 5720 (MDR) / Cr QRIS_CLEARING (gross)` —
fulfilling the ADR 0038 note that the MDR fee books at reconciliation, not at close.

**Out of scope (recorded residuals):** gateway mode on restaurant *bills* (bills create no Payment
row — ADR 0036 residual — so there is nothing to charge against; bills get MANUAL/STATIC only);
programmatic QRIS refunds (acquirer-dependent at Midtrans; GL reversal already correct, returning
money is manual); customer-display QR for the service verticals and bills; tier-gating GATEWAY
behind ADR 0044 plan tiers; additional providers (Xendit et al. — new `QrisGatewayPort` adapters).

## Amendment (2026-08-07, same day) — the DIVISION scope

Owner decision after first UAT contact: the static QR (and mode) may also come from the
**division** (business unit), not only the company or the outlet. This EXTENDS the original
decision's resolution chain rather than reversing it, so it is recorded here, not in a superseding
ADR: `payment_settings.outlet_id` is generalized to `org_unit_id` (V4 — an outlet OR a division
id; NULL stays the company row), and every facet resolves **outlet → division → company →
implicit MANUAL** (credentials remain company-row-only). payment-service still holds NO org read
model: the division id is CLIENT-SUPPLIED on the read paths (`?businessId=&divisionId=`) and on
charge creation — acceptable because it only selects intra-tenant availability/display data under
RLS, and the money path's protections (consumer-side amount verification, signature-gated
settlement) are untouched by it. The console resolves outlet→division parentage from the
POS-visible `/api/v1/outlets` (which gains an additive `divisionId`).

## Amendment (2026-08-13) — per-environment credentials, verify, honest degrade

First real go-live surfaced a silent human-error trap. Credentials were a SINGLE slot
(`provider_environment` + `server_key_encrypted` + `client_key_encrypted` + `server_key_last4`)
and the key is write-only (blank on save keeps the stored value). Flipping the environment without
re-entering the key left the OTHER environment's key in place (e.g. `environment=SANDBOX` still
bound to a PRODUCTION key) → Midtrans rejected auth at the till, surfaced only as the confusing
"Demo · pending provider" MANUAL fallback. This EXTENDS the credential model rather than reversing
it, so it is recorded here.

- **Two credential slots.** The merchant's SANDBOX and PRODUCTION keys live in independent columns
  (`sandbox_*` / `production_*`, migration **V6** — nullable ADD + an RLS-wrapped backfill from the
  legacy slot into the slot matching each row's current `provider_environment`; the legacy columns
  are kept dead for forward-only/rollback safety and dropped in a later contract migration).
  `provider_environment` becomes purely the **active** selector.
- **Structural guard.** `PaymentSettings.activateEnvironment(env)` refuses (→ 422) to activate an
  environment whose slot has no key, so an environment can never be activated against another
  environment's (or no) key. Switching the active environment needs no key re-entry. The domain's
  `getServerKey()/hasServerKey()/getServerKeyLast4()` resolve the ACTIVE slot, so the charge and
  webhook paths are unchanged (the webhook signature verifies against the active env's key, which is
  exactly the env charges are created with).
- **Verify ("Test connection").** `POST /api/v1/payment-settings/gateway/verify` (owner-only) runs a
  side-effect-free status probe of a throwaway order id: 404 → VALID (key authenticates), 401/403 →
  INVALID (also catches a key pointed at the wrong environment), else UNREACHABLE. No charge is
  created. This catches a bad/mis-environment key at the settings page instead of at the till.
- **Honest till degrade.** When the CONFIGURED mode is GATEWAY but the till resolves to MANUAL
  (effective read erroring/offline/disconnected/non-IDR), the panel now shows a "gateway
  unavailable — confirm manually" badge instead of the demo "pending provider" copy. Fail-open to
  MANUAL is unchanged; only the wording stops misleading.

## Consequences

- Going live with digital tenders is now real: STATIC ships value with no PSP dependency at all,
  and GATEWAY needs only the merchant's own sandbox/production keys. ADR 0007 is **superseded** —
  its revenue-at-capture invariant survives verbatim; its per-vertical adapter shape does not.
- One new deployable (service-template scaffold, own DB + Debezium outbox connector, gateway
  routes, port 8091), one new event in the catalog, three small vertical consumers, one finance
  reconcile category. No vertical schema migrations; no Keycloak or entitlement changes.
- The webhook is a second standing anonymous surface: rate-limited fail-closed at the gateway,
  signature-verified per tenant inside the service, uniform rejection (no oracle), park-don't-drop
  for every anomaly (unknown order, amount drift, late settlement). Requires a publicly reachable
  gateway URL (`native.payment.webhook-base-url`); dev/local uses `/sync`.
- Merchant credentials are tenant data we must protect: AES-256-GCM at rest (loyalty PiiCipher
  pattern), decrypted only inside the charge/webhook writers, never logged, never serialized —
  asserted by tests. `X-Override-Notification` must be re-verified against current Midtrans docs
  at implementation; the fallback is a dashboard-configured URL (onboarding friction only).
- The console fails **open to MANUAL** (settings unreachable, offline, non-IDR base currency, or
  unconnected gateway) — QRIS never blocks a sale; "Mark as paid" remains the universal override,
  and the idempotent capture path makes the manual-vs-webhook race harmless by construction.
