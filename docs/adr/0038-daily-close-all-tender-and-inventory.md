# 38. Daily close v2 — once-per-day, all-tender reconciliation, inventory stocktake

Date: 2026-08-06

## Status

Accepted (phased; supersedes the cash-only close of [ADR 0036](0036-register-sessions-and-platform-channel-settlements.md) for restaurant-service)

## Context

ADR 0036 shipped a register close that reconciles **cash only**: it trues `CASH_CLEARING` to the
counted drawer via an over/short variance. Three gaps make it insufficient for a real end-of-day:

1. **Only cash is reconciled.** Every sale's non-cash clearing leg accumulates — `QRIS_CLEARING`
   (1901), `CARD_CLEARING` (1902), `PLATFORM_RECEIVABLE` (1250) — but nothing ever squares those
   accounts at close. Online trues only through a *separate* manual platform-settlement dashboard;
   card and QRIS are never reconciled anywhere.
2. **No once-per-day rule.** `business_date` is stored but the only uniqueness is one-OPEN-per-
   outlet, so an outlet can open→close many times a day; there is no single, day-final close.
3. **Inventory is invisible to the ledger.** `menu_item.stock_quantity` is a lone integer deducted
   on sale with no COGS/inventory posting, and there is no physical-count / stocktake concept.

Product decisions (user-approved): the daily close becomes a single **once-per-outlet-per-business-
day** operation that (a) reconciles **every tender** by comparing an entered count/actual against
the expected recorded amount and truing that tender's clearing account, and (c) runs an **inventory
stocktake** that adjusts stock to the physical count and books valued shrinkage. There is no real
PSP, so "actual" for card/QRIS/online is a number the operator enters (EDC batch total, QRIS app
total, platform report), exactly as cash is counted.

## Decision

Revenue stays recognized at sale (the `SaleRecorded → GL` path is untouched). What the close does is
**true each tender's clearing account to reality** and **book inventory shrinkage** — never re-post
revenue. Delivered in three independently shippable, money-reviewed phases.

### Cross-cutting model

- **One session per outlet per business day.** A new unique `(company_id, business_id,
  business_date)` makes the session day-final: a second open (or a close reopen) for a day that
  already has a session is a `409`. The existing one-OPEN-per-outlet partial unique stays (guards a
  stale cross-day open). `business_date` still defaults to `Asia/Jakarta` today when omitted.
- **Per-tender expected = the clearing amount.** For tender `T`, `expected_T = Σ(sale clearing leg
  for T) − Σ(refund clearing leg for T)` in the session window `[opened_at, close)` — i.e. exactly
  what accrued in `T`'s clearing account. Cash keeps its drawer-accurate terms (ADR 0036 §3:
  `cash_collected_minor` + cash gift-card sales − cash refunds); card/QRIS/online use the charged
  amount per tender. The window/attribution approximations of ADR 0036 §"Quantified v1
  approximations" carry over unchanged.
- **Per-tender variance, entered count optional.** `variance_T = counted_T − expected_T`. A tender
  is only settled when a count is supplied, so "online settles later via the platform payout" still
  works — skip online at close and let the platform-settlement flow true `PLATFORM_RECEIVABLE`.
  Short → `Dr <tender>_SHORT_EXPENSE / Cr <clearing_T>`; over → `Dr <clearing_T> / Cr
  <tender>_OVER_INCOME`; zero → no entry. v1 reuses the existing `CASH_SHORT_EXPENSE (5700)` /
  `CASH_OVER_INCOME (4300)` accounts for every tender (a settlement variance is a settlement
  variance); an SME can split per tender via `role_account_map` later.
- **Clearing → real cash is unchanged.** After close, `CARD_CLEARING`/`QRIS_CLEARING` square to bank
  via ADR 0016 **bank reconciliation** (the deposit is net of the MDR fee — the fee is booked
  there, not at close); `PLATFORM_RECEIVABLE` squares via the ADR 0036 **platform settlement** (fee
  + payout). The close reconciles *volume/discrepancy*; bank/platform settlement reconciles *fees +
  actual receipt*. No double-posting.
- **Inventory stocktake.** At close the operator submits a physical count per tracked item;
  `variance_qty = counted − system`, stock is adjusted to the count, and where the item carries a
  `unit_cost` the valued net variance books `Dr INVENTORY_SHRINKAGE (COGS) / Cr INVENTORY (1100)`
  (a positive count difference reverses). Items without a unit cost are counted operationally but
  post no journal. This is a periodic stocktake, **not** perpetual COGS-on-every-sale (that needs
  cost + recipe/BOM data that does not exist — a deliberately deferred, separate program).

### Phasing

- **Phase 1 — once-per-day + per-tender *expected* preview (restaurant + console only).** The
  `(company_id, business_id, business_date)` unique; per-tender expected SUM queries; a read that
  returns the live per-tender expected for the OPEN session so the close screen can show the
  breakdown. No event, Avro, or finance change — lowest risk.
- **Phase 2 — per-tender *counted* + variance postings.** Close accepts a count per tender and
  persists counted/variance in a `register_session_tender` child table (extensible to per-channel
  online); `RegisterSessionClosed` gains an **additive** per-tender `tenders[]` array (rule 7,
  default empty — old consumers still read cash); the finance consumer posts each tender's variance
  truing its clearing account. Per-tender `expected` is the NET-tender clearing leg (`amount −
  gift_card_redeemed`), matching how cash nets via `cash_collected_minor` and how finance debits the
  clearing — summing the gross grand total would post a phantom short on a card/QRIS sale with a
  gift-card split. **Deploy order: finance BEFORE restaurant emits `tenders`** — Avro silently drops
  a writer field the reader's schema lacks, so an un-upgraded finance would ignore the non-cash
  variances (cash still posts correctly; the non-cash reconciliation just doesn't happen until
  finance upgrades — graceful, but a day-final close won't reopen, so it needs a manual adjusting
  entry). Mirrors the ADR-0036 ONLINE consumer-first rule.
- **Phase 3 — inventory stocktake + shrinkage.** `menu_item.unit_cost_minor`; a stocktake submitted
  with the close (counts → variance → stock adjust → event); new finance `INVENTORY` +
  `INVENTORY_SHRINKAGE` roles + the shrinkage journal; console stocktake UI.

Each phase: failing acceptance test first → tests + sonar green → mandatory money/tenancy code-
review → commit. As throughout, **all account codes are ILLUSTRATIVE**; an SME remaps via
`role_account_map` before statutory use.

## Consequences

- End-of-day becomes one honest, day-final screen: every tender's expected-vs-counted is visible
  and its clearing account is trued, and inventory shrinkage is measured instead of silently lost.
- The separate platform-settlement and bank-reconciliation flows are unchanged and remain the place
  fees + actual bank receipt are booked — the close does not attempt to know the bank payout.
- The close is only as honest as the counts entered (cash drawer, EDC batch, QRIS/platform report,
  physical stock) — a human review control, not fraud-proof, same posture as ADR 0036.
- Once-per-day is day-final: a mistake needs an adjusting entry, not a reopen. Multi-cashier /
  multi-drawer outlets (several shift sessions aggregated by a day-level close) is the documented
  evolution, not built here.
- Inventory enters the ledger only at stocktake (periodic), so between counts the books still don't
  reflect COGS-on-sale; perpetual inventory + recipe/BOM is a separate future program.
