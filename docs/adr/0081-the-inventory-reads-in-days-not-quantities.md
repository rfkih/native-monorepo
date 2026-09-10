# 0081. The inventory catalog reads in days, not quantities

- **Status:** Proposed
- **Date:** 2026-09-10
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0046](0046-ingredient-inventory-phase1.md) (the catalog this redraws),
  [ADR 0074](0074-sales-leak-detection.md) (the V47 movement ledger the days-left figure reads),
  [ADR 0072](0072-purchase-linked-inventory-and-periodic-cogs-routing.md) §5 (why Terima is never priced),
  [ADR 0067](0067-perpetual-inventory-accounting.md) (the inventory-method page the value hero opens),
  [ADR 0075](0075-navigation-and-overlay-contract.md) / [ADR 0078](0078-the-phone-more-surface-is-a-screen.md)
  (destination = route, interruption = dialog), [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md)
  (the ink palette). Source design: `Native Persediaan.dc.html` (Claude Design, 412×915 + 1240).

## Context

The ingredient catalog (`/inventory`) was a flat, name-ordered list: name, cost per unit, one
quantity chip, three buttons. Its only status was `stockQty === 0` — binary, and lit only after it
was too late. Three kilograms is a lot of turmeric and nothing of flour, so a quantity alone never
told an owner what to buy; they scrolled sixteen rows every morning and guessed.

Three further facts had gone unseen because nothing on the screen summed or compared them:

- the per-row stock value was there and never added up, although its total is exactly what
  account 1100 holds once perpetual inventory is on (and it is only that if every item carries a
  cost — an uncosted item is counted at opname and never posts);
- an ingredient whose base unit is `pack` (or a legacy `kg`) cannot enter any recipe, so it is
  invisible to HPP and to shortfall detection — the owner experienced it as "the recipe form won't
  take my number", and the row said nothing;
- the opname history printed a line's raw base quantity with its base unit (`8.400 g`) while the
  catalog showed `8,4 kg` for the same item.

The V47 ledger (ADR 0074) already exposes a seven-day movement roll-up
(`GET /api/v1/ingredients/stock-history?from&to`) that the console never consumed.

## Decision

**Divide stock by what a day consumes, and lead with the result.** Every row's figure is *sisa
hari* — days left — coloured by class, with the per-day rate under it. The rules are one pure
module, `features/inventory/lib/catalogView.ts`:

- **Rate** = an ingredient's `totalUsedQty` over the last 7 outlet-local days ÷ the most days any
  ingredient moved in that window (the closest the roll-up comes to "days the outlet was open").
  Per *open* day, not per calendar day: a sporadic ingredient (used once a week) is not read as
  burning through its stock daily, and a ledger that started three days ago is not diluted to a
  seventh of the truth. "Terpakai hari ini" (today's money) stays a separate figure from the
  single-day endpoint. The design assumed only today's usage existed; the average was chosen with
  the owner because one day is noise — a slow Tuesday should not read as sixty days of stock.
- **Class ladder**, most urgent first: `zero` (out) → `low` (≤ 3 days) → `unit` (base unit too
  coarse to cook with) → `nocost` → `ok`. The default order ranks by that ladder, then fewest
  days, then name; the four chips count each condition independently (a zero-stock pack item is
  under both "Stok nol" and "Satuan bermasalah").
- **One action stays on the row** — Terima, because deliveries arrive daily. Set quantity, edit
  and the unit fix-up move to the ingredient's own screen.
- **A value hero** sums the costed shelf and names how many items are uncosted rather than hiding
  them; for the owner it is the door to `/settings/inventory`, and it hands the catalog value over
  so the activation form can offer it as the opening figure.

**Screens are routes.** `/inventory/:id`, `/inventory/new`, `/inventory/:id/edit`,
`/inventory/:id/convert`, `/inventory/history[/:stocktakeId]` — a destination earns a history
entry, so Back pops to the list with its filter intact; the filter chip and the sort live in the
URL (`?filter=`, `?sort=`, `replace`) per N5. Below `lg` each is its own screen with the phone
`ScreenHeader`; from `lg` the catalog is a two-pane page whose right rail follows the route
(detail, form or conversion) and a row click *replaces* the URL. The one modal is the quantity
keypad (Terima / Atur jumlah), a `DialogOverlay` over whichever screen opened it — the same pad
as the opname count sheet (`inventory/lib/countKeypad.ts`, `inventory/QtyKeypad.tsx`).

**The history reads through the catalog's units.** A line carries its base unit; it is formatted
against the catalog's ingredient (`8,4 kg`) and falls back to the raw base unit only for an
ingredient that has since been removed. The overlay the standalone opname opens and the
`/inventory/history` screens share the same bodies.

**Terima is a quantity, never a price** (ADR 0072 §5 restated): a purchase with a payment is
recorded once, in full, on the company-expense form, which posts the money and applies the stock
receive together. The keypad sheet points finance logins there and offers no price field.

## Consequences

**The days-left figure is derived, and says so.** It is an estimate from one week's ledger, marked
`≈`, meant to decide a purchase — not a forecast. An outlet with no usage yet shows "—" on every
row and the ranking degrades to the class ladder and name; the list still loads, because the
roll-up and today's usage are never gates on the catalog query.

**Two more requests per catalog visit** (today's usage and the roll-up), both cached 30 s and
already served by the restaurant-service; no backend change.

**The old dialogs are gone.** `IngredientFormDialog`, `ReceiveDialog`, `SetQtyDialog`,
`ConvertUnitDialog` and the bespoke `DialogShell` are replaced by screens and one `DialogOverlay`;
the i18n keys only they used are removed. `StocktakeHistorySheet` keeps its overlay role but no
longer hand-rolls a scrim.

**`/settings/inventory` becomes inline steps.** Inactive → form → confirm are states of the page,
not a modal; the safety framing (explicit entry, permanence warning, acknowledgement, per-submit
idempotency key) is unchanged. On the phone it has a `ScreenHeader`; from `sm` up it keeps the
minimal owner-settings topbar.

**Money totals never mix currencies.** The hero, "terpakai hari ini" and the shelf-days figure sum
only items costed in the company base currency; an item costed in another currency counts as
costed (it is not "tanpa biaya") but adds nothing to a total.

**Open.** No local draft persistence on the form; no list virtualisation (fine to a few hundred
items).
