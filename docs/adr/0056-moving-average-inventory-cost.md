# 0056. Moving weighted-average inventory cost (operational scope)

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** Owner (rifki); domain-specialist (PSAK/tax); code + security review
- **Related:** [0046](0046-ingredient-inventory-phase1.md) (ingredient catalog), [0050](0050-recipes-bom-costing.md) (recipes/BOM/HPP — this refines its phase B costing pin), CLAUDE.md rule 8 (Money), `libs/money` `Money.mulDiv`

## Context

The same ingredient is bought at different prices over time (flour @ Rp 12.000/kg, then @ Rp 13.500/kg).
Until now `ingredient.unit_cost_minor` (ADR 0046) held a single **manually-typed, overwrite-only**
cost, and the receive endpoint carried a bare quantity with no price — so purchase price was never
captured, prices never blended, and HPP used whatever was last typed.

Indonesian standards permit only **FIFO or weighted average** (PSAK 14 / SAK EMKM; UU PPh Art 10(6));
**LIFO is prohibited**. For a UMKM BOM restaurant, moving weighted-average is the pragmatic default: one
stable HPP per ingredient, no lot ledger, and it fits the never-block-a-sale depletion of ADR 0050.
ADR 0050 phase B already pinned "moving-average update of `unit_cost_minor`" — but a **rounded per-unit
cost as the source of truth drifts** and re-triggers the sub-rupiah-per-gram distortion ADR 0046 flagged.

## Decision

We adopt **perpetual moving weighted-average** costing for `ingredient`, with the source of truth being
`(stock_qty, stock_value_minor, cost_currency)` — both integers (rule 8; honors the ArchUnit decimal
ban). `unit_cost_minor` is **demoted to a derived display cache** = `round(value/qty)` (HALF_EVEN via
`Money.mulDiv`), recomputed on every mutation, retaining its last value as the "last known unit cost"
when stock is zero. A receive captures the **exact total paid** (never a per-unit price); all
proportional steps (consumption, re-count, revalue) go through `Money.mulDiv` so total value over any
window = opening − closing, exact. Method is a **company-level election** (no per-item toggle).

Costing is a company-wide method, applied consistently (taat asas). **Explicitly OUT of scope:** any GL
posting — receipts do not capitalize to Inventory and sales post no COGS (ADR 0050 phases B/C remain
deferred); FIFO/lot tracking; multi-currency inventory + FX (a receipt currency ≠ the ingredient's cost
currency is rejected); batch/expiry; landed cost; exact value-derived HPP (the recipe read keeps using
the cache, which now equals the moving average — sub-rupiah rounding only).

## Consequences

- **Storage:** restaurant-service `V36` adds `stock_value_minor` + three CHECK invariants (`value >= 0`;
  `value = 0` when uncosted; `value = 0` when `stock_qty = 0`) and backfills existing costed rows
  (`qty × unit_cost`) inside the FORCE-RLS `NO FORCE → UPDATE → FORCE` bracket ([[rls-migration-backfill]]).
- **Behavior:** receive-with-price blends exactly; a sale/re-count/opname scales value with quantity so
  the average never moves; deplete-to-zero books all value out. The depletion hot-path stays one
  single-row `UPDATE` (deadlock-safe ascending-UUID order preserved). HPP/margin now reflect the true
  blended cost automatically.
- **Enforced by:** the `Money`/`mulDiv` primitive (no float), `Math.*Exact` (no wrap), the DB CHECKs, and
  the aggregate owning every value mutation. Bad input (currency mismatch, both-or-neither price, invalid
  ISO code, non-positive qty) → 400 via `ApiExceptionHandler`. Covered by unit + Testcontainers tests;
  code + security review PASS.
- **Accepted limitations (revisit with phase B):**
  1. **No idempotency on the priced receive** — a duplicated network submit double-adds qty + value,
     over-weighting the receipt against pre-existing stock (the average can then be wrong until a stockout
     or manual revalue). Robust fix belongs with phase B's `goods_receipt` table (its natural idempotency
     anchor); until then the receive is a manual, low-frequency action and the UI guards double-submit.
  2. **Opname value bucket vs shrinkage GL** no longer tie out to the minor unit — shrinkage stays
     cache-based (`varianceQty × round(value/qty)`, GL unchanged) while the value bucket scales exactly;
     they diverge by sub-rupiah. Conscious trade-off (GL logic untouched this scope).
  3. **`revalue` (PATCH unit cost) does not reject a currency change** on already-costed stock (unlike
     `receive`); harmless given base-currency immutability, documented not guarded.
- **Accountant sign-off still required** before treating figures as book-truth: the elected method for
  tax, whether the tenant is on final UMKM PPh 0,5% (COGS then tax-irrelevant), and the real COA codes.
