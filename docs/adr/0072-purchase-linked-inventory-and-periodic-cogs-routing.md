# ADR 0072 — One-submit purchases: expense input linked to inventory, and periodic HPP routing

- **Status:** Accepted (2026-09-03)
- **Deciders:** owner (entry-point, bill-inclusion and 5100 decisions, 2026-09-03) + tech lead
- **Plan of record:** `~/.claude/plans/parallel-petting-starlight.md`
- **Relates to:** [0068](0068-periodic-default-and-stocktake-controls.md) (periodic-safe default),
  [0067](0067-perpetual-inventory-accounting.md) (perpetual, still Proposed),
  [0056](0056-moving-average-inventory-cost.md) (moving-average HPP),
  [0050](0050-recipes-bom-costing.md) (GRNI before COGS)

## Context

An ingredient purchase today is two disconnected manual actions. The money side is an AP bill —
or nothing, because **no company-expense input exists** (the "Pengeluaran" page is employee
expense claims only, and `ExpenseRecorded` has a finance consumer but no producer anywhere). The
stock side is the inventory "Terima" dialog, whose *optional* price feeds the moving-average HPP
and a `goods_receipt`/`StockReceived` that finance deliberately ignores under the accepted
periodic default (ADR 0068). Skip either half and the books or the stock silently drift; do both
and nothing links them.

## Decision

### 1. A company-expense input, born linked to inventory

A new finance-service feature (`companyexpense`): `POST /api/v1/company-expenses` with two kinds —
`GENERAL` (a category expense via `gl_hint`) and `INVENTORY` (ingredient lines: ingredient, qty in
base units, amount paid per line). One submit records the money in the GL **and** adds the stock.
Owner/accountant-gated (FINANCE_ROLES), same as AP. Money posts **in-process at input** through
`GeneralLedgerWriter.post` (the ADR 0071 door — every entry auto-emits `JournalEntryPosted`);
`ExpenseRecorded` stays a consumer-only contract, untouched.

### 2. The seam: `InventoryPurchaseRecorded`, finance → restaurant

Finance emits **`InventoryPurchaseRecorded`** (outbox, same transaction as the GL posting) for any
purchase carrying ingredient lines — from the company-expense writer and from `BillWriter.post`
when a posted bill has ingredient-linked `is_inventory` lines. restaurant-service consumes it and
applies each line as a **priced goods receipt** through the existing ADR 0056 machinery
(`Ingredient.receive` moving-average + `goods_receipt` + `StockReceived`), with
`goods_receipt.idempotency_key = line_id` so no redelivery or duplicate can double-add stock.

This is the fleet's first finance→vertical event, a deliberate direction reversal: the money
document is the input where authorization and the accounting decision live; the stock ledger
derives from it. Still events + outbox only (rule 2/3). The event carries **no `business_id`** —
the stock-side outlet truth is `ingredient.business_id` (bills have no outlet column at all).

Consumer failure classes (unknown/deleted ingredient, currency mismatch vs the ingredient's cost
currency, qty overflow, same-key-different-payload) **park in the error inbox** — money is already
safely in the books; stock is fixed operationally. Business anomalies never DLT (redelivery cannot
fix them); only undecodable payloads / missing id headers do.

### 3. Periodic ingredient purchases post 5100 HPP, not 5000 (owner decision)

Under the periodic default, the inventory portion of a purchase posts **`Dr AccountRole.COGS
(→5100) / Cr CASH_CLEARING (1900)`** (expense form) and AP bills with inventory lines split
**`Dr 5000 (expenseNet) · Dr 5100 (inventoryNet) · Dr 1300 / Cr 2000`** — the perpetual split
shape with COGS in GRNI's place. This is a behavior change for `is_inventory` bill lines (GL-inert
today) and it makes "HPP" meaningful in the periodic P&L (purchases-as-COGS, un-adjusted by
opname — the classic periodic reading). Bills without inventory lines keep the template path
**byte-identical**. `AccountRole.COGS` (accountant-remappable, the same knob
`SaleCogsRecordedWriter` uses) is deliberately chosen over `resolveExpense("cogs")`.

Under perpetual-active (ADR 0067, nobody today), the inventory portion posts **`Dr 2050 GRNI`**
instead — mirroring the existing bill split — and the downstream `StockReceived` posts
`Dr 1100 / Cr 2050`, clearing GRNI. The expense form therefore behaves correctly under both
policies from day one, with the policy decided by `PerpetualInventoryReader` exactly like every
other inventory writer.

### 4. Void is money-side only (fix-forward)

Voiding a company expense posts the exact contra (accounts resolved at the original
`occurred_at`) and negates the P&L legs into the void's own period. **Stock is not auto-reversed**
— it may already be consumed; the UI directs the operator to "Atur jumlah"/stock opname (the ADR
0068 fix-forward posture). Under perpetual, a void after the receipt leaves 2050 in credit
("goods on hand, money reversed") until opname trues it — surfaced in UI guidance.

### 5. The priced "Terima" path is demoted in the console

To prevent the same purchase being entered twice (Terima with a price + the new form), the Terima
dialog loses its price inputs (costless stock adjust stays) and links to the expense form — the
form becomes the **only priced entry surface**. The backend endpoint keeps accepting priced
receives (backward compatibility; the consumer now feeds the same path).

## Consequences

- One submit keeps money, HPP moving-average, stock quantity and (under perpetual) the inventory
  asset in sync by construction; the periodic→perpetual migration story is unchanged (ADR 0067
  activation just flips which accounts the same inputs hit).
- Finance stores `ingredient_id`/`ingredient_name` as opaque snapshots — it cannot validate them
  (rule 1); the console picker keeps garbage out and the consumer parks the rest. Accepted.
- Stock updates are eventually consistent (seconds, via CDC) — the form says so.
- `goods_receipt` history remains write-only unless the deferred history phase ships.
- New event in the catalog with contract tests both sides; additive-only evolution (rule 7).
