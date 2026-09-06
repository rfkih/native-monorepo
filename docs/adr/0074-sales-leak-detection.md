# ADR 0074 — Sales-leak detection: the daily stock ledger, and estimating revenue that was never rung

- **Status:** Accepted (2026-09-06)
- **Deciders:** owner (scope, visibility and waste-log sequencing decisions, 2026-09-05) + tech lead
- **Plan of record:** `~/.claude/plans/floating-launching-nautilus.md`
- **Relates to:** [0046](0046-ingredient-inventory-phase1.md) (ingredient opname),
  [0050](0050-recipes-bom-costing.md) (recipes + per-sale depletion),
  [0036](0036-register-sessions-and-platform-channel-settlements.md) (register sessions),
  [0038](0038-daily-close-all-tender-and-inventory.md) (daily close, stocktake shrinkage GL),
  [0056](0056-moving-average-inventory-cost.md) (moving-average ingredient cost)

## Context

The most damaging fraud in an F&B SME is not a bad journal entry. It is the sale that **never
reaches the POS at all**: the food is served, the cash is taken (or the customer is pointed at a
personal QRIS), and nothing is rung. Afterwards the books are perfectly consistent, because the
missing revenue was never an input. Every report Native has — the Z-report, Laba-Rugi, the
GL-derived dashboard P&L (ADR 0065) — is derived from what *was* recorded, so all of them are blind
to this by construction.

What betrays an unrecorded sale is **physical**: the ingredients still left the kitchen, the bottle
still left the fridge. restaurant-service already stores every piece of that evidence in its own
database. Nothing correlates it, and one input was missing outright: `ingredient_usage_day` (V42)
recorded only recipe-driven consumption, so there was no way to tell shrinkage that had already been
explained (a delivery, a hand-correction, an opname) from shrinkage that had not.

## Decision

### 1. `ingredient_usage_day` becomes the full daily movement ledger (V47)

The table keeps its V42 name and gains columns. One row per (ingredient, outlet-local day) with
**every** stock movement bucketed by kind:
`qty_used` (recipe depletion at sale), `received_qty` (+`receipt_count`), `adjustment_qty` (SIGNED,
+`adjustment_count` — opname variance *and* manual "set stok", because from the ledger's point of
view both are a human overriding the system), `waste_qty` (reserved, always 0 until the waste log
lands), and `closing_qty`.

Every writer that moves stock now books to it in the SAME transaction as the movement:
`IngredientDepletionWriter` (usage), `PricedReceiveWriter` (both the HTTP receive and the ADR 0072
`InventoryPurchaseRecorded` consumer, via the one shared implementation), `IngredientWriter`
(create/set/add) and `IngredientStocktakeWriter` (per counted line).

Three sub-decisions worth pinning:

- **A daily aggregate, not a per-movement ledger.** A row per sale × per ingredient is the natural
  audit shape but explodes with volume — which is exactly why V42 chose the (ingredient, day) UPSERT.
  V47 keeps that and its concurrency discipline: writers UPSERT one ingredient at a time in
  ascending ingredient-UUID order, so the cross-sale deadlock stays impossible.
- **`closing_qty` is sourced from `ingredient.stock_qty` inside the UPSERT**, under
  `@Modifying(flushAutomatically = true)` — an entity-path caller has a dirty, unflushed persistence
  context and a native query would otherwise read the PRE-movement figure. The flush is what makes
  the mirror honest.
- **A movement is booked to the day it is APPLIED, never back-dated.** The ADR 0072 consumer can
  carry a back-dated purchase; back-dating the ledger row would overwrite an already-closed day's
  `closing_qty` with today's figure and silently corrupt that day's balance. The ledger records when
  the *figure* moved — the same rule per-sale depletion already follows for an offline sale replayed
  the next day. `received_at` remains the business fact, on `goods_receipt` and `StockReceived`.

**The table is NOT renamed, though the name now undersells it.** The first draft renamed it to
`ingredient_stock_day` for accuracy and reasoned carefully about Debezium — and not at all about
app-tier rollback. A rename is non-backward-compatible DDL: the fleet deploys by rolling update with
an automatic rollback on a failed health gate (ADR 0057), so old and new app versions run against
this schema simultaneously and a rollback puts the previous image in front of a name it has never
heard of. migration-safety: clean (1 new migration(s) checked against origin/master) refused it, correctly. The ledger's whole value is in
the added columns, so the migration is purely additive; the Java side keeps the precise names
(`IngredientStockDay`, `stockDate`) mapped onto the historical column names, and reads alias back
to them. A better noun is not worth a broken rollback.

### 2. Leak detection is a READ-ONLY report over data restaurant-service already owns

A new `integrity` feature in restaurant-service. No new service, no new event, no cross-service
call — every signal is restaurant-local, native query + projection, RLS-scoped (rules 1/2/5). The
detectors: tracked-item shrinkage → unrecorded units; ingredient shrinkage → portion equivalent via
`recipe_line`; dark periods (sales outside any register session, trading days with no session, an
hour whose own historical baseline says it should not be empty); closing hygiene; and the per-person
signals (void, refund, discount and cash-tender rates; bills cancelled with items still on them).
Attribution is `COALESCE(sold_by_user_id, created_by)` — the verified operator on an outlet-terminal
device, else the logged-in user.

A per-person rate is measured over revenue-bearing payments only (PENDING / ABANDONED / FAILED
tenders moved no money and belong in neither side of a fraction), and is compared against **the rest
of the outlet, never an average that includes the person being judged**: at three cashiers, the one voiding a third of their sales lifts the outlet
average enough to look ordinary, so self-inclusion is precisely how a real pattern hides itself where
this feature is used. The comparison is cross-multiplied (`a*d >= factor*c*b`), so no rate is ever
materialised as a fraction and the verdict cannot turn on a rounding step. Three guards keep it from
naming people it should not: a minimum EVENT count — counted in times-it-happened, never in the
numerator, because a money-valued measure like discounting would otherwise have a one-rupiah floor —
somebody to compare against (a lone operator is the entire baseline, not an outlier), and a real
denominator. Operators who did none of the thing being measured stay in the baseline: dropping them
would compare two refunders against each other instead of against the whole roster.

### 2b. Absence is only evidence once the time has passed

Every detector here reasons about something NOT happening — no sale in that hour, no Z-report that
day, no close on that session. The console's default period is the current month, so the requested
window routinely ends in the FUTURE, and a naive reading turns the unelapsed remainder of the month
into findings: tonight's dinner service as a dark hour, today as a day that never closed, a session
opened this morning as abandoned, and this morning's stock count as three weeks old.

So the report carries two upper bounds. The requested `to` bounds every QUERY, so nothing outside
the asked-for period is ever included. A derived `observedTo = min(to, now)` bounds every CONCLUSION
about absence, and the "day that never closed" check is capped tighter still, at the last COMPLETE
outlet-local day — a day in progress has not failed to close, it has not finished. A dark hour must
have fully elapsed before its silence counts.

The same rule governs the baseline: an hour's median is taken over every baseline day including the
ones that sold nothing at that hour. Taking it only over days that did sell turns "busy whenever it
was busy at all" into "normally busy" and manufactures holes out of ordinary quiet.

### 3. Three constraints on what this feature is allowed to be

- **The estimate NEVER enters the ledger.** No outbox write, no event, no posting. Real shrinkage
  already posts to the GL through the existing stocktake flow (ADR 0038/0046); this feature only
  *reinterprets* it as lost revenue, for the owner's eyes. A revenue estimate is an inference, and
  inferences do not belong in a general ledger.
- **Owner-only** (gateway `OWNER_ROLES`). Some signals name an individual, and a manager can be the
  subject of a finding, so a manager must not hold their own scorecard.
- **An indication, never an accusation.** Nothing is auto-notified; the UI leads with the estimate,
  presents it as a *range*, and says plainly that it is a signal to investigate. It also states its
  own blind spots (recipe coverage %, days since the last opname) — hiding those would let a zero
  read as a clean bill of health.

## Consequences

- Shrinkage that was already explained can finally be subtracted from shrinkage that was not, which
  is what makes the estimate defensible rather than alarmist.
- "Rata-rata pemakaian per hari" and "berapa kali stok dikoreksi manual" fall out of the same ledger
  for free — useful for reorder decisions independently of any fraud question.
- The ledger only speaks from V47 forward. Pre-V47 rows carry `closing_qty = NULL`, which readers
  must treat as *unknown*, never as 0.
- Until the waste log lands (the planned next phase), `waste_qty` is always 0 and R2 will
  over-report — hence the range, and hence the sequencing.
- `latest_closing_qty` is where the *window* left the stock, not the ingredient's current stock.
- Static/manual QRIS mode stays undetectable directly: there is no per-transaction charge to compare
  against, so tender-mix drift is the only handle on personal-QR substitution.
