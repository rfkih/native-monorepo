# 0006. POS payment tenders — provider-agnostic port, cash live, digital flagged-pending

- **Status:** Accepted
- **Date:** 2026-06-20
- **Deciders:** rifki + Claude (pairing)
- **Related:** CLAUDE.md HR-3 (outbox), HR-4 (Auditable), HR-5 (RLS), HR-8 (Money), the flagged-data
  pattern (DEVLOG "Flagged-illustrative domain data"); [ADR 0003](0003-single-source-event-schemas-libs-contracts.md)
  (single-source event schemas); [ADR 0007](0007-real-psp-adapter-webhook.md) (the deferred real-PSP switch)

## Context
The POS records a sale but has no concept of **how it was paid**. A real restaurant POS takes cash
(with change), QRIS, and card; the books must stay correct regardless of tender, and revenue must
never be recognised for money that never arrived. **QRIS and card move real money**, so a live
integration needs a payment-service-provider (Midtrans / Xendit / DOKU / a bank PJSP) account,
credentials, and settlement webhooks — none of which exist yet, and which must not be invented as
production (same class as the deferred DJP/BPJS statutory figures and the notification transport).

Constraints: **Money is integer minor units + a currency** (HR-8 — IDR has 0 fraction digits, so
whole-rupiah change is exact); **revenue flows only via the outbox** as `SaleRecorded` (HR-3); every
new table is **Auditable + RLS-scoped** (HR-4/5); the producer is idempotent on
`(company_id, idempotency_key)`.

## Decision
Introduce a `payment` feature in restaurant-service with a **provider-agnostic tender port**, and
make the **recognition of revenue gate on payment capture**.

- **One `payment` row per tender** (`V3`), keyed `(company_id, idempotency_key)` UNIQUE, Auditable +
  `FORCE ROW LEVEL SECURITY`. Carries `tender_type` (CASH | QRIS | CARD), `status`, the `amount`
  (Money), cash-only `tendered_minor`/`change_minor`, a `provider_ref`, a **`provider_pending`** flag
  (the flagged-data sticky bit), and the linked `sale_id`. Shaped so split/multi-tender is a later
  **additive** `payment_seq` column — no breaking change.
- **`PaymentProvider` port** selected by tender via a registry (a real provider is one new bean, no
  `switch`):
  - **`CashProvider` (live):** validates `tendered ≥ due`, computes whole-rupiah `change` with
    `Money`, returns **CAPTURED** synchronously. No external call — cash settles instantly.
  - **`DigitalProvider` (QRIS/card, flagged-pending):** returns **PENDING** with a placeholder
    `provider_ref` and `provider_pending = true`, and **never moves money**. This is the
    machinery-real / integration-flagged posture — the `provider_pending` bit rides onto the payment
    row (and, on capture, is observable to ops) exactly like `uses_illustrative_rules`.
- **Revenue-recognised-at-capture (the load-bearing invariant).** `SaleRecorded` is emitted **once,
  at capture**, never on a PENDING tender:
  - **Cash** captures synchronously, so order + lines + payment + `Sale` + the `SaleRecorded` outbox
    row commit in the one existing atomic checkout transaction (HR-3 atomicity preserved).
  - **Digital** checkout persists a PENDING payment with **no `Sale` and no `SaleRecorded`** — a
    `POST /payments/{id}/capture` records the sale. A QRIS/card tender that is abandoned therefore
    produces **zero revenue, by construction**.
- **Event shape:** add one **optional, backward-compatible** field `tender_type` (`["null","string"]`,
  default `null`) to `SaleRecorded` (rule 7 / ADR 0003) rather than a new `PaymentCaptured` event —
  the minimal change that lets finance route the GL **clearing account** by tender (CASH_CLEARING /
  QRIS_CLEARING / CARD_CLEARING; **null → CASH_CLEARING**, so carwash and legacy producers are
  unaffected). A dedicated `PaymentCaptured` event is the documented upgrade path if payment
  analytics later need their own stream.
- **Void / refund:** new `SaleVoided` / `SaleRefunded` events drive a **balanced REVERSAL** journal
  entry in finance (negated, `posting_role='REVERSAL'`, a deterministic synthetic `source_event_id`
  mirroring `ReversalEventIds`), decrementing the dimensional ledger + consolidated revenue + P&L,
  idempotently (`processOnce` + `source_event_id UNIQUE`). Change and rupiah-rounding deltas are
  **never revenue**.

## Consequences
- The POS gains real tenders and correct cash handling today; QRIS/card have the full UX + persistence
  + event machinery, with only the live rail stubbed — wiring a real provider is one adapter + a
  webhook behind [ADR 0007](0007-real-psp-adapter-webhook.md), with no schema or revenue-logic change.
- **Correctness:** no revenue is ever recognised for an uncaptured pending tender; reversals are
  balanced and idempotent; all money is Money (HR-8).
- **Enforcement:** Testcontainers tests prove cash atomicity, that a PENDING digital tender records
  **no** `SaleRecorded`, capture/void/refund idempotency + concurrency, tenancy isolation, and the
  `tender_type` schema stays backward-compatible; finance tests prove tender→clearing routing and
  balanced idempotent reversals.
- **Debt / flagged:** the `DigitalProvider` is illustrative-pending; a `provider_pending` payment must
  never be presented to a user as a settled payment, and the QRIS/CARD clearing accounts are seeded
  illustrative until a tax/accounting SME confirms the chart-of-accounts mapping.
