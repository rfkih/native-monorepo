# 0007. Real payment-service-provider adapter + settlement webhook (the digital-tender switch)

- **Status:** Superseded by [0045](0045-qris-modes-and-payment-service.md) — the revenue-at-capture
  invariant survives; the per-vertical adapter + in-service webhook shape is replaced by a central
  payment-service
- **Date:** 2026-06-20
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ADR 0006](0006-pos-payment-tenders.md) (the payment tender port this completes); CLAUDE.md
  HR-2 (no sync calls between business services — an outbound POST to an external PSP/an inbound
  webhook is an *integration*, the same class as gateway→Keycloak, not a business-service call), HR-3
  (outbox), HR-5 (RLS), HR-6 (PII — card data never stored)

## Context
ADR 0006 ships the digital tenders (QRIS/card) behind a **flagged-pending** `DigitalProvider` that
never moves money: the persistence, capture flow, GL routing, and events are all real, only the live
rail is stubbed. Going live needs a payment-service-provider **account + credentials**, a chosen
provider, and a **settlement webhook** — a product/commercial decision, not something to invent.

## Decision (proposed — to be Accepted once a provider is chosen)
Replace the `DigitalProvider` for a given `TenderType` with a real adapter, without touching the
revenue/event machinery:

- A `QrisProviderAdapter` / `CardProviderAdapter` registered in the `PaymentProviderRegistry` for its
  tender type, calling the chosen PSP and returning **PENDING** with a real `provider_ref`. No change
  to `Payment`, `SaleRecorded`, or finance.
- An inbound **`PaymentWebhookController`** receives the PSP's settlement callback and calls the **same**
  `capture` path the manual/dev capture uses — so revenue is still recognised only at capture.
  Idempotent via `(company_id, idempotency_key)` + `provider_ref` uniqueness + the existing
  `processOnce`/`source_event_id UNIQUE` backstops.
- **Secrets** (PSP API keys, webhook signing secret) come from Vault/env, never the repo; the webhook
  verifies the provider's signature before acting.
- **PII:** no PAN / card data is ever persisted (HR-6); only the provider's opaque reference.

## Consequences
- Going live is one adapter + one webhook controller + secrets — the "machinery already real" promise
  of ADR 0006. Until then digital tenders remain flagged-pending and this ADR stays **Proposed**;
  the open decision is which PSP (Midtrans / Xendit / DOKU / bank PJSP), driven by commercials.
