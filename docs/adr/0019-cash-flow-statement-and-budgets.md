# 19. Cash Flow Statement + Budgets

Date: 2026-07-29

## Status

Accepted

## Context

Phase 5 of the Odoo accounting-parity program (Phases 1–4 = AR, AP, Bank, Tax — ADR 0014–0017);
accounting is ~80% of Odoo. Two pieces remain. **(A)** The **Cash Flow Statement** — the third core
financial statement (income statement + balance sheet already exist as GL-derived readers). **(B)**
**Budgets** — per-month planned amounts per account, with a **budget-vs-actual** variance report.

**Neither posts to the GL:** cash flow is a pure GL-derived read; budgets are planning data compared
against GL actuals. So Phase 5 adds **no money-critical journal posting** — lower-risk than the prior
phases — though budgets are new tenant tables that need strict RLS.

## Decision

1. **Cash flow = indirect method, GL-derived, no new tables.** It lives in the existing `statements/`
   package under the already-routed `/api/v1/statements/cash-flow`. `glTrialBalance(period)` already IS
   the per-account net movement for the period, so for each line `m = debit − credit`:
   - **Cash & equivalents** = the accounts resolved from the `BANK` / `CASH_CLEARING` / `QRIS_CLEARING`
     / `CARD_CLEARING` roles (via `RoleAccountResolver` — SME-pluggable, not hard-coded);
     `actualCashMovement = Σ m` over them.
   - **Net income** = Σ REVENUE (credit−debit) − Σ EXPENSE (debit−credit).
   - Each non-cash ASSET/LIABILITY/EQUITY account contributes an adjustment `= credit − debit` (an
     asset increase uses cash; a liability/equity increase provides it), classified **operating**
     (current — all working capital today) / **investing** (non-current assets — none yet) / **financing**
     (equity, long-term debt — none seeded).
   - `netChangeInCash = (netIncome + operating) + investing + financing`.

   **Exact reconciliation.** Because `GlTrialBalanceReader.read` asserts Σdebit==Σcredit (so `Σ m = 0`
   over all accounts), `netChangeInCash ≡ actualCashMovement` identically. The reader **asserts** this
   (a mismatch means an account was left unclassified — a bug → 500), exactly like `BalanceSheetReader`'s
   balance check, and surfaces the cash movement in the response as a confirming reconciliation line.

2. **Budgets = a per-month `budget` + `budget_line` sub-ledger (V33), never posted.** A budget is a
   named set of planned amounts for one month; each line is `account_code → amount_minor` (FK to
   `chart_of_account`, `UNIQUE(budget_id, account_code)`, `amount ≥ 0`), the AR/AP parent+child shape.
   Both tables are Auditable + `FORCE ROW LEVEL SECURITY`. The **budget-vs-actual** report joins each
   line's planned amount against the account's GL actual for the budget's period (its type-normal net
   from `glTrialBalance`), computing `variance = actual − planned` — no new "actual" store, the same
   GL-derived numbers the statements use.

3. **Endpoints + access.** Cash flow is a report (`page: 'reports'` in the console, like income /
   balance sheet). Budgets are a write feature at `/api/v1/budgets/**` (new gateway `budgetsRoute`,
   `DASHBOARD_ROLES`), plain `canDashboard` in the console (like invoices/bills/tax), with a
   chart-of-account picker endpoint (`GET /api/v1/budgets/accounts`) for the create form.

## Consequences

- The console gains a Cash Flow statement page and a Budgets feature (list + create + variance). With
  the three statements + AR + AP + Bank + Tax + budgets, accounting reaches ~85% of Odoo. The cash-flow
  reconciliation is a strong built-in correctness check.
- **SME gates (illustrative):** the cash-flow **activity classification** — the current-vs-non-current
  split (non-current assets → investing) and operating-vs-financing (long-term debt → financing) — is a
  placeholder: today the COA has only current assets/liabilities + synthetic retained earnings, so
  everything nets to operating. A real classification needs a COA taxonomy (reserved for Phase 6 fixed
  assets, which introduce the first non-current/investing accounts).
- **Deliberate deferrals:** multi-month / annual budgets (per-month this slice); budget line editing
  (create + delete only — edit = delete + recreate); the direct-method cash flow (needs a contra
  self-join the GL doesn't index; indirect is exact here); comparative / prior-period columns; budgeting
  balance-sheet accounts beyond simple targets.
