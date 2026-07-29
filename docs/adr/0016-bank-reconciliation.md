# 16. Bank accounts & reconciliation — settling the clearing account

Date: 2026-07-29

## Status

Accepted

## Context

Phase 3 of the Odoo accounting-parity program (Phases 1–2 = AR + AP, [ADR 0014](0014-accounts-receivable-subledger.md)/[0015](0015-accounts-payable-subledger.md)).
AR receipts, AP payments, **and POS sales all post to one CASH_CLEARING account (`1900`)** — a bridge
representing "cash received/paid but not yet in a real bank account." Nothing ever *settled* that
clearing balance, and the books had **no real bank accounts**. Phase 3 adds bank accounts and a
reconciliation flow that sweeps the clearing balance into the bank (and records bank-only fees /
interest), so the balance sheet's cash reflects the actual bank statement.

## Decision

1. **Bank accounts are a finance-local sub-ledger** (`bank_account` + `bank_statement_line`, V29),
   RLS-scoped, mirroring the AR/AP structure. Non-invasive: AR/AP/POS posting is untouched.

2. **One shared `BANK` control account** (`1000`, V30) that ALL bank accounts post to — exactly as AR
   uses one `1200` and AP one `2000`. Per-account balances live in the sub-ledger (Σ reconciled line
   amounts); the GL knows only the aggregate. Per-bank-account GL accounts are deferred.

3. **Reconcile-by-category, not a matching engine (this slice).** A `bank_statement_line` carries a
   signed `amount_minor` (+ deposit / − withdrawal). Reconciling it posts an **ad-hoc balanced 2-line
   `JournalEntry`** (built directly in `ReconciliationWriter`, resolving accounts via the existing
   `RoleAccountResolver` — no posting_template, no new EventKind) against a chosen contra:
   - **CLEARING** (settles POS/AR/AP activity): deposit → Dr `BANK` / Cr `CASH_CLEARING`; withdrawal →
     Dr `CASH_CLEARING` / Cr `BANK`. This is the sweep that draws the clearing balance down.
   - **BANK_FEE** (withdrawal only): Dr `BANK_CHARGES` (5400) / Cr `BANK`.
   - **INTEREST** (deposit only): Dr `BANK` / Cr `INTEREST_INCOME` (4100).
   The **auto/line-item matching engine** (matching a statement line to specific unreconciled AR/AP
   payment entries, partial matches, suggestions) is deferred — this slice does the accounting
   settlement without it.

4. **Reconcile is idempotent via a status guard + a UNIQUE `source_event_id`** (= the statement-line
   id). A re-reconcile is a 409; no `Idempotency-Key` is needed because reconcile is a one-shot
   UNRECONCILED→RECONCILED state transition, not a repeatable payment.

5. **The AR/AP review fixes are baked in:** the single-base-currency guard on the reconcile post
   (→422); DTO-only controllers; `Location` on 201s; `LIMIT` on the list queries; all new tables
   `FORCE ROW LEVEL SECURITY`; native + projection reads.

6. **The bank account number is stored MASKED (last-4 only), never in full.** CLAUDE.md rule 6 names
   "bank account" as column-encrypted PII, and finance-service has no column-encryption
   infrastructure, so rather than persist the full number in the clear (a rule-6 violation) or stand
   up encryption for one field, the full number is masked server-side to `****1234` on write and
   never persisted. Last-4 is not the protected PII (the same convention as a card's last-4 on a POS
   receipt). Full column-level encryption for finance-service PII (if ever needed for AR/AP contact
   fields too) remains a separate fleet-wide decision.

## Consequences

- The balance sheet gains `1000 Bank` (asset); `1900 CASH_CLEARING` **draws down** as lines are
  reconciled, so its residual becomes true, auditable cash-in-transit. The income statement gains
  `4100 Interest Income` / `5400 Bank Charges` — all via the existing GL-derived statement readers.
- With AR + AP + Bank, the cash cycle is closed end-to-end: a sale/receipt/payment hits clearing, and
  reconciliation moves it to the real bank when the statement confirms it. Accounting reaches ~70% of
  Odoo.
- **SME gates:** the bank/interest/charges COA (`1000`/`4100`/`5400`) are illustrative placeholders.
- **Deliberate deferrals:** the line-item matching engine; CSV / bank-feed import formats (this slice
  imports plain line rows); per-bank-account GL accounts; multi-currency bank accounts; statement
  opening/closing-balance validation; the unified single-currency-GL guard across all producers (the
  AR/AP residual — still tracked).
