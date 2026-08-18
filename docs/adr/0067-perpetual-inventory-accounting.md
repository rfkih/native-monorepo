# 0067. Perpetual inventory accounting — capitalize purchases, COGS on consumption, stocktake trues the asset

- **Status:** Proposed
- **Date:** 2026-08-17
- **Deciders:** rifki (owner) — PENDING; Claude (tech lead, Native). **Accountant / tax SME sign-off on the chart of accounts is REQUIRED before any prod activation** (§Consequences).
- **Related:** [0046](0046-ingredient-inventory-phase1.md) (ingredient catalog), [0050](0050-recipes-bom-costing.md) (recipes/BOM — **this ADR builds its pinned phases B + C**), [0056](0056-moving-average-inventory-cost.md) (moving weighted-average cost — the value source of truth), [0038](0038-daily-close-all-tender-and-inventory.md) (stocktake shrinkage GL), [0037](0037-opening-balances-and-business-migration.md) (opening balances — the activation seed), [0065](0065-gl-derived-dashboard-pnl.md) (GL-derived P&L — why COGS reaches the dashboard for free), [0015](0015-accounts-payable-subledger.md) (AP bills), [0030](0030-employee-expense-claims.md) (expense claims), `docs/EVENT-CATALOG.md`, hard rules **1** (db-per-service), **2** (no sync business-to-business calls), **3** (outbox), **7** (backward-compatible events), **8** (Money).

## Context

**A confirmed expense double-count and a broken balance sheet.** Native today expenses inventory on *purchase* and expenses part of it *again* at stocktake, while the inventory asset account is only ever credited — so it goes negative.

Traced in code (verified 2026-08-17):

- **Purchase → `Dr 5000 General Expense`, never capitalized.** `finance-service` `ap/BillWriter.post` posts `Dr EXPENSE(net) / Dr VAT_INPUT(tax) / Cr AP(gross)` (`BILL_POSTED` template, V28); bill lines (`CreateBillRequest.LineRequest`, `BillLineInput`) carry only `{description, qty, unitPrice}` — **no account/category**, hardcoded to the generic `EXPENSE` role → `5000`. Employee expense claims (`empexpense/ExpenseClaimPostingWriter`) also debit the generic `EXPENSE` role → `5000` (V39; the claim's `gl_hint` only steers the *dimensional* `ledger_posting`, never the double-entry leg). `restaurant-service` `inventory/IngredientWriter.addStock` (a priced goods receipt) posts **no GL at all** — moving-average value moves operationally only (ADR 0056, GL explicitly out of scope).
- **Consumption → no GL.** `restaurant-service` `recipe/IngredientDepletionWriter` floors stock and records per-day usage but posts **nothing** to finance. `5100 Cost of Goods Sold` exists in the chart (V2) but **no double-entry role or writer ever posts to it** (the `cogs` `gl_hint` only maps a *dimensional* posting; there is no `AccountRole.COGS`).
- **Stocktake → `Dr 5800 Inventory Shrinkage / Cr 1100 Inventory`** (`finance-service` `stocktake/StocktakeWriter.postShrinkage`, V50) *and* adds the loss to the P&L. Because the purchase already hit `5000` and `1100` was **never debited**, the counted-variance portion of used stock is **expensed twice** (`5000` at purchase, `5800` at count) and every credit drives asset `1100` **monotonically negative** — a structurally wrong balance sheet.

Net effect for a restaurant tenant: roughly cash-basis expensing (expense ≈ what you bought), **plus** a double-count on genuine waste, **plus** a negative inventory asset. ADR 0050 anticipated exactly this and pinned the fix as its phases B (capitalize / GRNI) and C (perpetual COGS), with the load-bearing ordering rule **"B MUST precede C"** — perpetual COGS credits `1100` on every sale, so until purchases capitalize (debit) `1100`, the account goes monotonically negative *and* food cost double-counts. That rule is honoured here.

**The target (perpetual):**

| Event | Posting |
|---|---|
| Purchase / goods receipt | `Dr 1100 Inventory / Cr 2050 GRNI` (receipt) + `Dr 2050 GRNI / Cr 2000 AP` (bill) |
| Consumption (per sale) | `Dr 5100 COGS / Cr 1100 Inventory` |
| Stocktake | true-up the **real** `1100` — only the genuine count variance hits `5800` |

Inventory becomes an expense **exactly once** (as COGS when sold, or as `5800` shrinkage for the residual that never sells). `1100` stays a correct, non-negative asset.

**Constraints that shape the design:**

- **Rule 2 — no synchronous business-to-business calls.** Inventory *quantity + value* is owned by `restaurant-service` (moving-average, ADR 0056); the *GL* is owned by `finance-service`. The value must reach finance **only via events** (rule 3, transactional outbox) + finance's own cached state — never a sync call. Finance stays purely downstream (ARCHITECTURE §1).
- **Rule 1 — db-per-service.** Finance cannot read the restaurant stock ledger; restaurant cannot read the GL. Each posts/reads only its own tables.
- **Rule 8 — Money.** Every amount on every new event is integer minor units + an ISO-4217 currency code.
- **Rule 7 — events backward-compatible only**, registered in `EVENT-CATALOG.md` with an Avro schema in `libs/contracts` (ADR 0003).
- **Illustrative CoA.** Every account named here (`1100/2050/5100/5800/2000`) is an `uses_illustrative=TRUE` placeholder — an accountant must confirm the real codes before book-truth (ADR 0042 posture).

## Decision

Adopt **perpetual inventory accounting** as a **finance-owned, company-level election with a cutover date** (fix-forward — sealed history is never rewritten). Restaurant-service reports *physical facts* as valued events; **finance decides the accounting treatment** based on whether the company is perpetual-active as of the event's period. Three seams, all event-driven:

### 1. Cross-service seam — valued events over the outbox (rule 2 + 3)

**Restaurant emits two NEW valued events unconditionally** (they are facts, not accounting choices — a company that has not elected perpetual simply has finance ignore them for the GL). Both are written through `libs/events OutboxWriter` in the **same transaction** as the state change (rule 3), decoded by finance as raw Avro bytes via `AvroSerde`, deduped by event UUID (`ProcessedEventStore`).

#### `StockReceived` — capitalize a priced goods receipt

- **Purpose:** carry the *exact landed value* of a priced receive (the only place inventory value is known — moving-average, ADR 0056) so finance can debit `1100`.
- **Producer:** `restaurant-service` `inventory/IngredientWriter.addStock` (the priced branch — `amountPaidMinor` present), in the same tx as `Ingredient.receive`. A new `goods_receipt` row (restaurant migration) is the durable per-event anchor (its id is the finance idempotency key). It *sets up* closing ADR 0056 accepted-limitation #1 (a duplicated priced receive double-adds value) — but the receive endpoint carries **no caller idempotency key today**, so a retried receive still writes two rows / two events. Closing #1 (and preventing an active tenant's double `Dr 1100/Cr 2050`) requires threading a receive idempotency key into `goods_receipt.idempotency_key` (partial-unique index already in V43) — a **HARD prerequisite before Phase D activation** (see §Consequences / Phase D).
- **Consumer:** `finance-service` — when the company is perpetual-active for the receipt's period: `Dr INVENTORY (1100, value_minor) / Cr GRNI_CLEARING (2050, value_minor)`; otherwise a **no-op that still claims the event id** (idempotency preserved; nothing posts). Ad-hoc 2-line entry via `RoleAccountResolver` (the V50 stocktake / bank / asset-disposal precedent — no template needed).
- **Idempotency:** `receipt_id` UNIQUE (`ProcessedEventStore` + `journal_entry.source_event_id`).

| Field | Avro type | Meaning |
|---|---|---|
| `receipt_id` | `string` | `goods_receipt` UUID; partition key + idempotency key |
| `company_id` | `string` | Owning tenant |
| `business_id` | `string` | Originating outlet |
| `ingredient_id` | `string` | The received ingredient (traceability; finance posts one aggregate 1100/GRNI pair, does not key GL on it) |
| `qty` | `long` | Units received, in the ingredient's base unit |
| `value_minor` | `long` | **Exact total paid** for the receipt, minor units — the value added to the moving-average bucket. Never a float |
| `currency` | `string` | ISO-4217 code |
| `received_at` | `long` (`timestamp-millis`) | When received (drives the accounting period) |

```json
{
  "type": "record",
  "name": "StockReceived",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by restaurant-service when a PRICED goods receipt is recorded (a moving-average receive, ADR 0056). Consumed by finance-service to capitalize the landed value to Inventory under the GRNI clearing idiom (Dr 1100 / Cr 2050) WHEN the company is perpetual-active for received_at's period; otherwise a claimed no-op. Money is integer minor units + an ISO-4217 code, never a float (rule 8). ALL fields required (a brand-new contract); future additive fields append LAST with a default (rule 7).",
  "fields": [
    {"name": "receipt_id", "type": "string", "doc": "The goods_receipt aggregate id (UUID as string); partition key AND finance idempotency key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating outlet (UUID as string)."},
    {"name": "ingredient_id", "type": "string", "doc": "The received ingredient (UUID as string) — traceability only; finance posts one aggregate Dr 1100 / Cr 2050 pair and does not dimension the GL on it."},
    {"name": "qty", "type": "long", "doc": "Units received, in the ingredient's own base unit (integer; ADR 0046 decimal ban)."},
    {"name": "value_minor", "type": "long", "doc": "The EXACT total paid for this receipt, in the currency's minor units — the value added to the moving-average bucket. Never a float (rule 8)."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code (e.g. IDR, USD)."},
    {"name": "received_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the goods were received, epoch millis UTC — drives the accounting period."}
  ]
}
```

#### `SaleCogsRecorded` — perpetual COGS on consumption (ADR 0050 phase-C pinned name)

- **Purpose:** carry the cost of goods consumed by a sale so finance expenses it against inventory.
- **Producer:** `restaurant-service` `recipe/IngredientDepletionWriter` folds COGS from the **same** depletion query (Σ depleted qty × moving-average unit cost at sale time), persists `sale.cogs_minor` + `cogs_currency` as an audit anchor (recipes mutate under full-replace — ADR 0050), and writes the outbox row in the **same transaction** as the sale + depletion. Called from every sale-recording site behind the existing derived-key idempotency (checkout, payParked, bill checks, digital capture, offline replay) — one behaviour, matching ADR 0050 §"split checks deplete per check at payment".
- **Consumer:** `finance-service` — when perpetual-active: `Dr COGS (5100) / Cr INVENTORY (1100)`; otherwise a claimed no-op. Ad-hoc 2-line entry via `RoleAccountResolver`. **Because the dashboard P&L is GL-derived (ADR 0065), the `5100` leg reaches the beranda AND the income statement automatically** — no `consolidated_pnl` writer to remember (the anti-pattern ADR 0065 closed).
- **Idempotency:** `sale_id` UNIQUE.

| Field | Avro type | Meaning |
|---|---|---|
| `sale_id` | `string` | Sale aggregate id; partition key + idempotency key |
| `company_id` | `string` | Owning tenant |
| `business_id` | `string` | Originating outlet (dimensional `business_id`) |
| `occurred_at` | `long` (`timestamp-millis`) | When the sale occurred (drives the period) |
| `cogs_minor` | `long` | Σ depleted qty × moving-average unit cost, minor units. Never a float |
| `currency` | `string` | ISO-4217 code |

```json
{
  "type": "record",
  "name": "SaleCogsRecorded",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by restaurant-service when a sale depletes recipe ingredients (ADR 0050 phase C). Consumed by finance-service to post perpetual COGS (Dr 5100 / Cr 1100) WHEN the company is perpetual-active for occurred_at's period; otherwise a claimed no-op. cogs_minor = Σ depleted qty × moving-average unit cost at sale time, snapshotted into sale.cogs_minor. Money is integer minor units + an ISO-4217 code, never a float (rule 8). A separate event from SaleRecorded (NOT a field on it) to avoid the SALE posting-template deployment hazard (ADR 0050 phase-C pin, V37 note). ALL fields required; future additive fields append LAST with a default (rule 7).",
  "fields": [
    {"name": "sale_id", "type": "string", "doc": "The sale aggregate id (UUID as string); partition key AND finance idempotency key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating outlet (UUID as string) — the dimensional business_id on the COGS ledger_posting."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the sale occurred, epoch millis UTC — drives the accounting period (same period as the sale's revenue)."},
    {"name": "cogs_minor", "type": "long", "doc": "Cost of goods sold for this sale: Σ (depleted qty × moving-average unit cost at sale time), in the currency's minor units. Never a float (rule 8)."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code (e.g. IDR, USD)."}
  ]
}
```

#### Stocktake true-up — reuse `StocktakeCompleted` (no schema change)

The existing `StocktakeCompleted` already carries a **signed** `shrinkage_minor` and finance already posts `Dr 5800 / Cr 1100` (loss) or `Dr 1100 / Cr 5800` (gain). Under perpetual this **becomes the true-up** with **no contract change**: the variance is `counted − book`, valued, and crediting/debiting the *now-real* `1100` down/up to the counted value is exactly right (§4). No new event, no schema evolution — only the finance-side *meaning* changes, gated on perpetual-active.

### 2. GRNI (goods-received-not-invoiced) — the 3-way flow, no per-line matching (rule 1)

A receipt (restaurant fact) and its vendor bill (finance AP) arrive at different times and **carry no shared line identifier** — the bill line is free text, the receipt is per-ingredient. Per-line auto-matching would require a cross-service key that couples the two data models, so **do not attempt it.** Instead both post to a single **`2050 GRNI Clearing` (LIABILITY)** account — the `1900/1901/1902` clearing idiom (ADR 0050 phase-B pin):

1. **Goods receipt** (`StockReceived` consumer): `Dr 1100 Inventory / Cr 2050 GRNI` — inventory arrives, an obligation accrues.
2. **Vendor bill posted** (`ap/BillWriter`, inventory lines): `Dr 2050 GRNI (net) / Cr 2000 AP` — the obligation is now an invoiced payable. (Non-inventory lines keep `Dr 5000`; VAT keeps `Dr 1300`.)
3. **Clearing:** GRNI nets to zero when receipt value == bill net. A residual is a genuine **price/timing variance** left visible in `2050` for the accountant (no per-item reconciliation) — the same posture the cash-clearing accounts already take. `1100` ends debited at the *moving-average landed value* (restaurant's authority); `2000 AP` credited at the *invoice value* (finance's authority); the difference is the variance, by construction.

`1100` has **exactly one writer** (finance's `StockReceived` consumer) — restaurant never values inventory into the GL, finance never re-values it. This keeps the boundary clean and avoids two services fighting over the asset balance.

### 3. Routing a purchase to inventory vs. plain expense (minimal, backward-compatible)

Add an **optional per-line `inventory` boolean** (default `false`) to `CreateBillRequest.LineRequest` and the service `BillLineInput`; persist it on `finance` `bill_line.is_inventory` (new column, defaults false). `BillWriter` sums two nets — `EXPENSE_NET` (Σ non-inventory) and `INVENTORY_NET` (Σ inventory) — and passes both into `buildEntryFromBreakdown`. The `BILL_POSTED` template gains a **version 2**:

```
Dr EXPENSE       (EXPENSE_NET)     -- 5000, existing behaviour for non-inventory lines
Dr GRNI_CLEARING (INVENTORY_NET)   -- 2050, NEW
Dr VAT_INPUT     (TAX)             -- 1300, unchanged
Cr AP            (GROSS)           -- 2000, unchanged
```

`BILL_VOID` v2 mirrors the contra. Zero-amount lines are already omitted by `JournalPostingService`, so:

- A tenant with no inventory lines → `INVENTORY_NET = 0` → the GRNI leg drops out → **byte-identical to v1** (a globally-effective v2 template is therefore safe for every tenant).
- `BillWriter` only populates `INVENTORY_NET` when the company is **perpetual-active**; otherwise the flag is ignored and everything routes to `5000` (non-activated tenants are completely unaffected).

**Backward-compat:** old API clients omit `inventory` → `false` → today's behaviour. **Expense-claim inventory routing (petty-cash inventory) is DEFERRED** (Phase E) — it needs an `employee-service` schema change and is rare; until then a flagged claim keeps posting to `5000`. The primary capitalization path is the priced receive + the AP bill.

### 4. Stocktake: expense-all → true-up the real asset

Under perpetual, `restaurant` book stock is decremented per sale by depletion, so a stocktake variance (`counted − book`) is the **genuine residual loss** (waste/theft/spoilage/un-recipe'd usage) — *not* normal consumption (that already left via COGS). Posting `Dr 5800 / Cr 1100` for that variance **trues `1100` to the physically-counted value** and books only the real loss. The finance `StocktakeWriter` GL logic is therefore **unchanged**; only its correctness precondition changes — it now trues a *real* `1100` (built by receipts − COGS) rather than an always-negative one.

Two things to get right (SME-gated):

- **Value basis.** The true-up must value the variance at the **moving-average** unit cost (the ingredient opname, post-ADR-0056) so `1100` trues to the same value basis receipts/COGS built it from. The menu-item stocktake (V28, sellable-portion/86-gate) values at the menu-item static cost and is a *different* concern — keep it out of the `1100` true-up.
- **Reconciliation drift.** ADR 0056 accepted-limitation #2 (opname value bucket vs. shrinkage GL diverge by sub-rupiah) becomes material once `1100` is real. Recommend a periodic **finance `1100` vs. restaurant inventory-value reconciliation report** and an SME-set materiality band before posting a true-up.

### 5. Company-level election + cutover (activation, not a big-bang)

Perpetual is a **finance-owned company election** (mirrors ADR 0056's company-level costing election and ADR 0066's setup-gate):

- **New companies:** perpetual-active from creation (cutover = creation; `1100` starts at 0 — no opening balance needed).
- **Existing companies:** activate via a console **setup-gate** that books an **opening inventory balance** as of the cutover (reuse ADR 0037 opening-balances: `Dr 1100 / Cr 3900 Opening Balance Equity` for the counted on-hand value), establishing a correct `1100` base before COGS starts crediting it.
- Finance branches the three consumers (`StockReceived`, `SaleCogsRecorded`, `StocktakeCompleted`) and the bill routing on **"is this company perpetual-active for the event's period?"** — a single decision it owns locally (no cross-service config event; restaurant stays election-agnostic). Before cutover: old behaviour (bills → `5000`, opname → `5800`, receipt/COGS ignored-but-claimed). After cutover: perpetual.

### Out of scope (explicitly)

Per-line GRNI auto-matching; expense-claim inventory routing (Phase E); FIFO/lot/expiry (ADR 0056 keeps weighted-average only); landed-cost allocation (freight/duty into unit cost); multi-currency inventory + FX (a receipt currency ≠ the ingredient cost currency is still rejected upstream); COGS/inventory for carwash/barbershop (no ingredient model); rewriting sealed pre-cutover history; a real refund/void COGS reversal (ADR 0050: the dish was made — a genuine restock surfaces at the next opname).

## Phased roadmap (B before C is load-bearing; each increment ships behind the inactive election)

Every phase starts from a **failing test / acceptance criteria**, is committed atomically, and runs `/code-review` in a fresh context before merge. **Money, tenancy, and event-contract changes are mandatory review gates.** No company is perpetual-active until Phase D activates it, so B and C shipping in separate releases cannot produce a broken intermediate.

- **Phase 0 — contracts + config (ship-safe, no behaviour change).** *(event-contract gate)*
  - Register `StockReceived` + `SaleCogsRecorded` `.avsc` in `libs/contracts`; add both to `EVENT-CATALOG.md` (status `SCHEMA REGISTERED`); contract-test triads on both producer (restaurant) and consumer (finance) sides (parse + round-trip + back-compat for-change / against-break).
  - `AccountRole.GRNI_CLEARING` (→ `2050`) and `AccountRole.COGS` (→ `5100`, which already exists in the chart).
  - Finance migration (next `V53`): `chart_of_account` `2050 GRNI Clearing (ILLUSTRATIVE)` `ON CONFLICT DO NOTHING`; `role_account_map` `GRNI_CLEARING→2050`, `COGS→5100` (v1 illustrative); widen the `amount_basis` CHECK to add `EXPENSE_NET`, `INVENTORY_NET`; `BILL_POSTED` v2 + `BILL_VOID` v2 templates (effective-dated); `inventory_method_config` table (company_id, `method`, `perpetual_active`, `cutover_period`, `activated_at`; Auditable + FORCE RLS) defaulting new tenants active. **Acceptance:** migration test green, all templates resolve, no tenant behaves differently (INVENTORY_NET always 0 until Phase B).
- **Phase B — capitalize on receipt + bill routing (B before C).** *(money + tenancy gate)*
  - Restaurant: `goods_receipt` table (per-event anchor; the `idempotency_key` that would close ADR 0056 #1 is present but UNwired — see below); `IngredientWriter.addStock` priced branch writes the `StockReceived` outbox row in-tx.
  - Finance: `StockReceived` consumer → `Dr 1100 / Cr 2050` (perpetual-active) / claimed no-op otherwise; `bill_line.is_inventory` column + `CreateBillRequest`/`BillLineInput` flag + `BillWriter` net split → `Dr 2050 (INVENTORY_NET)`.
  - **Acceptance (failing test first):** a priced receive + a matching inventory-flagged bill leave `2050 ≈ 0`, `1100` debited at landed value, `2000 AP` credited at invoice value; a non-activated tenant is byte-identical to today.
- **Phase C — perpetual COGS on sale.** *(money gate)*
  - Restaurant: fold COGS in `IngredientDepletionWriter`, persist `sale.cogs_minor`/`cogs_currency`, emit `SaleCogsRecorded` in the sale tx.
  - Finance: `SaleCogsRecorded` consumer → `Dr 5100 / Cr 1100` (perpetual-active) / claimed no-op otherwise.
  - **Acceptance:** for an activated tenant, buy → receive → sell leaves `1100` = landed − COGS (non-negative), COGS on the GL-derived P&L (ADR 0065), inventory expensed **exactly once**; `PnlMatchesIncomeStatementTest`-style parity holds.
- **Phase D — activation UX + stocktake true-up semantics + opening balance.** *(money + tenancy gate)*
  - **HARD PREREQUISITE (blocks activation): receive idempotency.** Before ANY tenant is activated, thread a caller idempotency key through the priced-receive endpoint into `goods_receipt.idempotency_key` (the partial-unique index is already in V43). Without it a retried receive emits two `StockReceived` events with distinct ids → the finance consumer (deduped per-event-UUID) double-posts `Dr 1100 / Cr 2050`, driving the asset negative — the exact failure this ADR exists to prevent. This also finally closes ADR 0056 accepted-limitation #1.
  - **Activation-gate decision — COGS rounding / oversell drift (Phase C review W1).** The Phase-C fold values COGS at `depleted qty × round(moving-avg unit cost)` (the HPP basis), while depletion books out `round(value × qty / stock_qty)` — they differ by sub-unit rounding (ADR 0056 #2), and on an **oversell** (`qty ≥ stock_qty`, stock floored to 0) the folded COGS can exceed the residual `1100` value and push it negative. This is what §4's true-up + the `1100`-negative alarm catch; make it explicit on the checklist and add a fold-vs-bucket reconciliation/materiality test before activation.
  - **Activation-gate decision — sealed-period COGS symmetry (Phase C review W2).** `RevenuePostingWriter` quarantines a `SaleRecorded` dated into a VAT-sealed period, but the `SaleCogsRecorded` (and Phase B `StockReceived`) consumers do NOT. For an active tenant this could post COGS into a sealed period while its paired revenue is quarantined — a one-sided sealed-book entry. Decide before activation: quarantine COGS/receipt symmetrically, or document why the asymmetry is acceptable.
  - Finance stocktake consumer branch: perpetual-active ⇒ the existing `Dr 5800 / Cr 1100` is the true-up (value-basis + materiality per §4).
  - Console setup-gate to activate an existing company: book the opening `1100` via ADR 0037; per-line inventory toggle on the bill form; a "record goods receipt" nudge for ingredient purchases (§Risks).
  - **Acceptance:** activation books a balanced opening entry; a post-activation stocktake trues `1100` to the counted value; a `1100`-goes-negative monitor is wired.
- **Phase E — deferred.** Expense-claim inventory routing (employee-service schema + GRNI leg); finance↔restaurant `1100` reconciliation report; landed-cost.

## Consequences

- **Inventory is expensed exactly once** (COGS when sold; `5800` for the residual that never sells), `1100` is a correct non-negative asset, and the double-count is closed **for activated tenants**. The dashboard and income statement agree by construction (ADR 0065) because COGS is a GL posting.
- **Fix-forward, no history rewrite** (mirrors ADR 0064's "reverse+re-post, never mutate" and the project's "leave old as audit truth" precedent): pre-cutover sealed periods keep their old (double-counted) postings as audit truth; the new rules apply from each company's cutover. Non-activated tenants **retain the current double-count by design** until an owner activates them — activation (with an opening `1100`) is the remedy, not a silent retroactive correction.
- **The critical operational risk — perpetual COGS is only correct if purchases capitalize.** If a perpetual-active company keeps booking food purchases as plain `5000` expenses (never flags a bill line inventory, never records a priced receive) while its recipes emit `SaleCogsRecorded`, COGS re-expenses and drives `1100` negative — the original bug returns. Mitigations, all in Phase D: (1) the console steers ingredient purchases to the receive / inventory-flag path and nudges on unflagged food bills; (2) a **`1100`-goes-negative alarm**; (3) SME confirmation that the tenant's purchasing workflow actually capitalizes. **This risk must be understood before activation.**
- **GRNI residual is expected, not a bug** — it is the price/timing variance between the restaurant's landed value and the vendor invoice, left visible in `2050` for periodic accountant review (no per-item matching; rule 1). Recommend a GRNI-ageing read and an SME clearing policy.
- **Reconciliation drift** (ADR 0056 #2) becomes material once `1100` is real; the finance↔restaurant inventory-value reconciliation + materiality band (Phase E / §4) is the follow-up.
- **Illustrative-account caveat (hard gate before prod).** `1100 / 2050 / 5100 / 5800 / 2000` are all `uses_illustrative=TRUE` placeholders. **An accountant must confirm the real chart of accounts, the elected costing method for tax (weighted-average is permitted; PSAK 14 / SAK EMKM — LIFO prohibited, ADR 0056), and — decisively — whether the tenant is on final UMKM PPh 0.5% (in which case COGS is tax-irrelevant and the whole perpetual apparatus is bookkeeping-only), before any production activation.** No code change is needed to swap the codes — higher-version `role_account_map` rows (the SME seam).
- **Enforcement:** new-event contract tests both sides (rule 7); `Money`-only arithmetic + the ArchUnit decimal ban (rule 8); RLS + `Auditable` on `goods_receipt` and `inventory_method_config` (rules 4–5); outbox-only publishing in the same tx (rule 3); idempotency by event UUID + `source_event_id` UNIQUE (rule 3); `/code-review` mandatory on the money/tenancy phases.
