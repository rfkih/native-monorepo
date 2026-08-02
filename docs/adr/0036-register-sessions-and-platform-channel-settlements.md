# 36. Register sessions (closing kasir) + platform channel settlements

Date: 2026-08-03

## Status

Accepted

## Context

Counted drawer cash at end of day rarely equals recorded cash sales — cashier error, unrecorded
petty movements, miscounted change. Until now Native had no way to book that difference (selisih
kas): CASH_CLEARING (1900) accumulated recorded cash and silently diverged from physical reality.
Separately, online-delivery platforms (GoFood/GrabFood-style) collect from the customer and pay the
merchant later minus a commission — money that must never be booked as drawer cash and whose payout
needs its own settlement flow.

Product decisions (user-approved): a register session is per **outlet per business day** (open with
an optional float, close with a physical recount; at most one OPEN session per outlet, DB-enforced);
online orders are **rung at the POS** with a channel picked at payment (KOT/stock flow preserved);
channels are **company-managed CRUD**.

## Decision

**Revenue stays recognized at sale** (the existing SaleRecorded → GL path is untouched). What
settles at closing is **cash**: the register close trues CASH_CLEARING to the physical drawer by
posting only the **variance**.

1. **`cash_register_session`** (restaurant V21): status OPEN|CLOSED, opening float, close-only
   columns, open/close idempotency keys, partial UNIQUE one-OPEN-per-outlet. Close computes
   `expected = opening_float + cash_sales − cash_refunds` and the SIGNED
   `over_short = counted − expected` server-side (`Math.*Exact`), persists them, and emits
   **`RegisterSessionClosed`** through the outbox in the SAME transaction. The consumer re-asserts
   the reconciliation identities (exact arithmetic); a violation is poison → DLT.
2. **Variance posting** (finance V43): two new `AccountRole`s so an SME can remap freely —
   SHORT: `Dr CASH_SHORT_EXPENSE (5700 illustrative)` / `Cr CASH_CLEARING (1900)`;
   OVER: `Dr CASH_CLEARING` / `Cr CASH_OVER_INCOME (4300 illustrative, other income)`.
   Zero variance → no journal entry (event still marked processed). Sealed period at `closed_at` →
   error-inbox quarantine carrying the over/short amount + currency for manual posting.
3. **Expected-cash inputs are drawer-accurate** (restaurant V22/V23): `sale.cash_collected_minor`
   stores the cash physically collected per CASH sale (grand total − gift-card-redeemed portion;
   pre-V22 rows COALESCE to `amount_minor`), `payment_refund` is an append-only per-refund delta
   ledger (a cumulative `payment.refunded_minor` attributed to one window would double-count
   partial refunds spanning sessions), and cash **gift-card sales** count as a third drawer inflow
   (a gift card sold for cash is a liability, not revenue, so it lives in `gift_card_sale` — but
   its cash is in the drawer; without this term it surfaces as a phantom OVER at close).
4. **Online sales post gross** (PSAK 72 — the merchant is principal, the platform an agent): tender
   `ONLINE` + channel routes the clearing leg to `Dr PLATFORM_RECEIVABLE (1250 illustrative)`;
   revenue/tax legs unchanged; ONLINE is synchronous capture and carries no gift-card/loyalty legs.
   An ONLINE payment refunds **all-or-nothing** (review W4): finance rejects partial refunds, so a
   partial ONLINE refund would leave the per-channel receivable permanently overstated — the
   restaurant edge rejects it (400) instead of letting the two services silently diverge.
   Finance keeps a per-channel `platform_receivable` accumulator (GL account shared), tolerant of
   negative balances (refund clawbacks); the reversal writer decrements it.
5. **Platform settlement** (dashboard, per channel, repeatable): input gross settled + net
   received; `fee = gross − net` (net > gross → 422; platform subsidies deferred):
   `Dr CASH_CLEARING (net) + Dr PLATFORM_FEE_EXPENSE (5710 illustrative) / Cr PLATFORM_RECEIVABLE
   (gross)`. The payout's bank-statement line then reconciles through the existing ADR-0016
   CLEARING sweep — bank reconciliation stays the single Dr-BANK writer. Guards: guarded UPDATE
   `outstanding >= gross`, advisory lock per company+channel, Idempotency-Key required with
   replay-by-key-first (PayrollSettlementWriter idiom).
6. **Deploy-order rule**: finance's `resolveClearingRole` exists in BOTH RevenuePostingWriter and
   ReversalPostingWriter, each defaulting unknown tenders to CASH_CLEARING. Both must learn
   `ONLINE → PLATFORM_RECEIVABLE` (plus a warn on the default branch) and be DEPLOYED BEFORE any
   producer can emit an ONLINE sale — otherwise platform money mis-books as drawer cash.

### Pessimistic lock authorization (ENGINEERING-STANDARDS §2.5)

`RegisterSessionRepository.findWithLockById` takes `PESSIMISTIC_WRITE` (SELECT … FOR UPDATE) on the
single session row during close. Authorized here because optimistic retry is wrong for this shape:
close is a read-compute-write over external state (the cash-window SUMs) followed by an outbox
emit; two concurrent closes racing optimistically would both compute sums, and the loser's retry
would re-run money aggregation against a now-CLOSED session. The lock scope is one row, held for
one short transaction, ordered after the idempotency-replay probe; the double-open guard is the
partial unique index, not a lock.

### Idempotency

Open and close both REQUIRE an `Idempotency-Key` (≤64). Replay-by-key-first returns the original
200; a replayed key with a DIFFERENT payload (other outlet, other float/currency, other counted
amount) is a client bug surfaced as **409 conflict**, never a silent 200 with someone else's
session. The console uses a fresh UUID per open attempt but the STABLE key `close:<sessionId>` for
close — a per-attempt close key would make the server's replay path unreachable after a lost
response; a 409 on close triggers a refetch of the truth.

### Quantified v1 approximations (accepted, documented)

- **Session-window attribution**: cash sales/refunds attach to a session by time window
  `occurred_at ∈ [opened_at, close_instant)` + outlet + tender, sound because one-OPEN-per-outlet
  is DB-enforced. A cash sale rung while NO session is open belongs to no window and appears in no
  close (it still posts to GL normally — only the variance control misses it). Per-payment
  `session_id` stamping is the documented evolution for multi-drawer outlets.
- **MVCC boundary race**: a sale committing in the instant between the close's SUM read and the
  session's `closed_at` write can land just outside the summed window; the discrepancy surfaces as
  over/short on the NEXT close, never as lost GL money. Bounded by one in-flight checkout.
- **Legacy null-tender undercount**: sales recorded before V21 have `tender_type NULL` and are
  excluded from expected cash. Only affects a close whose window spans the V21 deployment moment.
- **Pre-V22 gift-card overstatement**: CASH rows without `cash_collected_minor` COALESCE to the
  grand total, overstating expected cash by the gift-card-redeemed portion for that sale. Only
  affects windows spanning the V22 deployment; no backfill (FORCE-RLS Flyway UPDATE trap).
- **Business-date default**: an open without an explicit `businessDate` stamps `Asia/Jakarta`
  today. Multi-timezone companies must pass the date explicitly (future: outlet-level timezone).
- **Commission PPN**: the whole platform fee books to expense in v1 — not valid for a PKP claiming
  input VAT on the commission; the VAT_INPUT split is additive later. As throughout, ALL account
  codes are ILLUSTRATIVE — an SME must remap via `role_account_map` before statutory use.

## Consequences

- Drawer cash is now a controlled figure: every close leaves an auditable session row and, when
  variance ≠ 0, a balanced journal entry — cashiers see the selisih verdict immediately at close.
- Platform money is visible as a receivable per channel from the moment of sale, and commissions
  become a measured expense instead of silently-missing revenue.
- Two write paths gained strict idempotency + payload-verification semantics; clients must send
  keys and treat 409 as "refetch the truth".
- The close is only as honest as the count: over/short is a human review control, not fraud-proof.
- ONLINE tender cannot ship to producers until the finance clearing-role change is live
  (deploy-order rule above) — enforced by phasing, not by code.
