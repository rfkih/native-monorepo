# 0046 — Ingredient-level inventory, phase 1: catalog + receive/adjust + stock opname (no recipes)

Status: Accepted (2026-08-08)

## Context

The stocktake shipped by [ADR 0038](0038-daily-close-all-tender-and-inventory.md) phase 3
counts **tracked menu items**. Owner feedback from UAT: for made-to-order food that is the
wrong subject — "stock opname" means counting **ingredients** (bahan: bread, patty, sauce,
vegetables). One Burger sale consumes several ingredients; the countable, shrinkable
inventory is the raw material, not the finished dish. Menu-item stock remains useful only
as the sellable-portion / sold-out (86) gate.

Owner scope decisions:
1. **Phased — counts first.** This phase ships an ingredient catalog, receive/adjust
   endpoints, and an ingredient stock opname. Usage is inferred by counted difference
   (periodic). Recipes/BOM and per-sale auto-depletion are a later phase.
2. **Both stocks stay.** `menu_item.stock_quantity` and its endpoints are untouched (86
   gate). The console opname flow switches subject to ingredients.

## Decision

**New `inventory` feature in restaurant-service** (`id.co.nativeapp.restaurant.inventory`),
cloning the `stocktake` feature's layout and idioms. Migration `V31__ingredient_inventory.sql`
adds three tables (all Auditable + FORCE RLS):

- `ingredient` — per-outlet catalog: `name`, `unit` (display text), `stock_qty INTEGER
  NOT NULL DEFAULT 0 CHECK (stock_qty >= 0)` (always tracked — no NULL/untracked state),
  nullable cost pair `unit_cost_minor BIGINT` + `cost_currency CHAR(3)` (CHECK
  both-or-neither), `active` soft-delete flag, partial unique name per outlet while active.
- `ingredient_stocktake` + `ingredient_stocktake_line` — clones of `stocktake`/
  `stocktake_line` keyed by `ingredient_id`, with parent `currency` **nullable** (below).

**Integer quantities, in the ingredient's own unit.** The ArchUnit rule
`entitiesHaveNoDecimalMoneyFields` is a blanket field-type scan over every
`@Entity`/`@Embeddable` — a `BigDecimal` quantity fails the build. Therefore quantities are
whole numbers and the console's unit picker offers **g / ml / pcs / pack only** (no kg/L —
an integer cannot express 0.5 kg; "500 g" is the honest entry). `unit` is opaque display
text server-side (no conversions), so finer units or milli-unit integers can be added later
without schema breakage.

**The opname reuses the existing `StocktakeCompleted` event verbatim.** The schema carries
only {stocktake_id, company_id, business_id, counted_at, signed net `shrinkage_minor`,
currency} — no per-line or menu-specific data; its semantics ("net valued shrinkage of a
physical count at an outlet") fit the ingredient count exactly. Consequences:
- **finance-service is untouched** (same consumer, same Dr 5800 `INVENTORY_SHRINKAGE` /
  Cr 1100 `INVENTORY` legs + both P&L read models, same idempotency on the event UUID).
- Rule 7 holds trivially — only `doc` prose in the `.avsc` and the event-catalog entry are
  generalized (subject: menu items in the legacy flow, ingredients here).
- Ingredient stocktake UUIDs share the `"stocktake"` aggregate type; ids cannot collide.

**No-cost submissions emit no event.** An ingredient without a cost is counted operationally
(stock adjusted) but has no ledger value — when **zero** lines carry a cost, the parent row
stores `currency NULL` and the writer skips the outbox entirely (the schema requires a
currency; there is nothing for finance to post). When ≥1 costed line exists the event is
emitted even at net zero, matching the legacy flow. The writer enforces a single
`cost_currency` across costed lines (clone of the legacy currency-mismatch guard).

**Legacy surfaces keep serving.** `/api/v1/stocktakes` (recorded history + API stability)
and the menu-item stock endpoints stay; the console simply stops calling the legacy
stocktake submit. Gateway adds two POS_ROLES routes: `/api/v1/ingredients/**`,
`/api/v1/ingredient-stocktakes/**`.

**Console**: new full-screen `/ingredients` management page (list/create/edit/receive/set/
deactivate), gated like `/menu` (POS surface ∧ `'menu'` page grant ∧ `products` tier — no
new PageKey; the gateway role check is the authz boundary). `StocktakeSheet` switches its
subject to ingredients; its empty state links to `/ingredients`.

## Consequences

- Between opnames the books still carry no COGS-on-sale — deliberately unchanged from
  ADR 0038's periodic model. Perpetual inventory + recipes/BOM remains the separate future
  program (phase 2 of this feature).
- Variance on an ingredient count includes normal usage (cooking), not just loss — until
  recipes exist, "shrinkage" for costed ingredients is really "counted consumption + loss".
  Owners who want the GL untouched by routine usage should leave ingredient costs empty and
  read the count operationally. Noted in the console hint copy.
- Sub-minor-unit costs round (cost per gram in IDR exp-0): valuation is only as fine as one
  minor unit per unit of measure — costing at pcs/pack granularity is nudged in the UI.
- List GETs return bare arrays (the menu/stocktake precedent, not the §1.3 envelope) — an
  outlet's ingredient catalog is menu-sized; revisit only if catalogs grow unbounded.
- The three new tables ride the standard CDC audit stream (rule 4); no hand-rolled audit.

## Alternatives considered

- **Decimal quantities** (NUMERIC + BigDecimal): fails the ArchUnit decimal ban; loosening a
  build-breaking money-safety rule for a convenience was rejected.
- **New `IngredientStocktakeCompleted` event**: adds a schema + catalog entry + finance
  consumer for identical semantics — pure duplication; rejected.
- **Discriminator on the existing `stocktake` tables**: poisons legacy history reads and
  forces nullable dual FKs; rejected.
- **Counting both menu items and ingredients in one opname**: owner explicitly chose
  ingredients-only as the opname subject; menu items stay on their own screen.
