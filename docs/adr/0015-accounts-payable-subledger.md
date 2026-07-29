# 15. Accounts Payable sub-ledger — the vendor-facing mirror of AR

Date: 2026-07-29

## Status

Accepted

## Context

Phase 2 of the Odoo accounting-parity program (Phase 1 = Accounts Receivable, [ADR 0014](0014-accounts-receivable-subledger.md)).
**Accounts Payable** is the second missing accounting pillar: recording what the company owes its
vendors. It is a near-exact structural mirror of AR — a vendor party, a bill sub-ledger (draft → posted
→ (partially) paid | void), bill payments, and an AP aging report — with two deliberate inversions.

## Decision

1. **AP mirrors AR** in structure and lives in `finance-service` (`ap/` feature), finance-local (no
   cross-service sync). `vendor` ↔ `customer`, `bill` ↔ `invoice`, `bill_payment` ↔ `invoice_payment`,
   AP-aging ↔ AR-aging. Migrations **V27** (AP tables) + **V28** (GL config).

2. **The GL sides are reversed.** A bill is a liability + an expense, not an asset + revenue. Reusing
   the data-driven `JournalPostingService.buildEntryFromBreakdown` (no new posting code; V28 adds a
   `NET` amount_basis):
   - **Post:** Dr `EXPENSE` (net) / Dr `VAT_INPUT` (tax) / Cr `AP` (total).
   - **Payment:** Dr `AP` / Cr `CASH_CLEARING`.
   - **Void:** the contra of post.
   New COA `2000 Accounts Payable` (LIABILITY) + `1300 VAT Input Recoverable` (ASSET); new
   `AccountRole.AP`/`VAT_INPUT`; new `EventKind.BILL_POSTED`/`BILL_PAYMENT_MADE`/`BILL_VOID`.

3. **Input VAT is a recoverable ASSET** (`1300`), not a liability — the mirror of AR's output-VAT
   liability. It represents VAT receivable from the tax authority on a purchase. Rate/regime are
   ILLUSTRATIVE (PPN 11% placeholder, `uses_illustrative_rules`), SME-gated exactly like AR output VAT
   — including whether it is recoverable at all.

4. **The bill net posts to a single expense account** (`5000`) in Phase 2, mirroring AR's single
   revenue account. Per-line expense-account / cost-centre selection is deferred.

5. **The AR code-review fixes are baked in from the start** (AR discovered these over two review
   rounds; AP gets them in one): payment `Idempotency-Key` is **required** and its UNIQUE index is
   scoped to `(company_id, bill_id, idempotency_key)` in V27 (AR needed a follow-up V26); the
   single-base-currency guard runs on **post, payment, and void**; the aging report rejects mixed
   currencies; controllers return DTOs only, 201s carry `Location`, list queries are `LIMIT`-bounded.

## Consequences

- The balance sheet gains `2000 AP` (liability) and `1300 VAT Input` (asset); the income statement
  gains the bill expense — all via the existing GL-derived statement readers, no change.
- Combined with AR, the double-entry books now capture both sides of trade (customers owe us / we owe
  vendors), moving Accounting toward ~60% of Odoo.
- **SME gates carried forward:** the AP account, the input-VAT regime/rate/recoverability, and the
  single-expense-account simplification are illustrative until an accounting/tax SME confirms them.
- **Deliberate Phase-2 exclusions:** purchase orders (a Purchase module), 3-way match, multi-currency
  bills, credit/debit memos, recurring bills, per-line cost centres. Events (`BillPosted`/
  `BillPaymentMade`) deferred to a Phase-2b (no consumer yet).
- The AR residual carries forward: the sale/expense currency guard reads `consolidated_pnl` while
  AR/AP read `journal_entry` — a unified single-currency-GL guard across all producers is still a
  tracked follow-up (unreachable while the base currency is immutable + single).
