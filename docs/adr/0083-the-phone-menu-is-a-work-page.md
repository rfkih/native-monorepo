# 0083. The phone menu is a work page, not a catalog

- **Status:** Proposed
- **Date:** 2026-09-11
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0050](0050-recipes-bom-costing.md) (recipe lines are ingredient + base-unit
  quantity; HPP is their sum), [ADR 0056](0056-moving-average-inventory-cost.md) (the unit cost a line
  is priced at), [ADR 0081](0081-the-inventory-reads-in-days-not-quantities.md) (ingredient stock is the
  real stock; the catalog the picker reads), [ADR 0075](0075-navigation-and-overlay-contract.md) N2/N3/N5
  (one chrome, one Dialog primitive, list state in the URL), [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md)
  (the ink palette). Source design: `Manajemen Menu.dc.html` (Claude Design, 390×844).

## Context

`/menu` (`menu/MenuManagement.tsx`, ~2,700 lines) had no phone variant: one desktop tree wrapped by
`max-sm:` classes, twelve dialogs, and a per-item action cluster — stock pill, options, recipe, edit,
availability, delete — that simply wrapped onto its own line at 390px. Changing a price meant a
dialog; seeing what that price was made of meant a second drawer; nothing on the row said what the
item earned.

The design's thesis is one sentence: *a work page, not a catalog*. Everything an owner does to one
item — name, price, availability, recipe, duplicate, delete — finishes on that item's own row,
without a screen change; HPP is never typed, it is the recipe's sum, so the margin moves the moment
an ingredient is added or removed; the page is grey, the one colour is the red on "Hapus item"; low
stock is a heavier weight, not a yellow badge.

## Decision

**Below 640px `/menu` renders `MenuPhone`** (`MenuManagement` switches on `useIsPhone()`; the
desktop tree is untouched). The page is: a `ScreenHeader` with the one action ("+ Item"), a search
field, category chips with counts, a summary line, and a list card whose rows open — one at a time —
into the item's whole editor.

- **The chips are the categories the menu actually uses.** `menu_item.category` is a free-text
  string (`categoryId` is never written), so the chips come from the items, canonicalised through
  `categoryCanon.ts`, counted, with an uncategorised bucket last. Chip and query live in the URL
  (`?cat=`, `?q=`, `replace`).
- **Search reaches into the recipes.** "Cari item atau bahan" matches an item's name OR any
  ingredient name in its recipe. There is no bulk recipe read, so recipes are fetched lazily — one
  `GET /menu/{id}/recipe` per item via `useQueries`, only while the query has two characters, keyed
  exactly like `useRecipe` so an open row and the search share one cache entry per item.
- **The panel PATCHes on commit; there is no save button.** Name and price are uncontrolled fields
  keyed on the server value: blur or Enter commits (`PATCH /menu/{id}`), the server's answer remounts
  the field with the truth. Availability is a real `role="switch"` over `/86` and `/un-86`. The note
  under the panel says so: *berlaku langsung di kasir — tidak perlu disimpan.*
- **HPP is derived, and the line says what it costs.** The recipe list shows each base line's
  ingredient, its quantity in the ingredient's BASE unit ("200 g" — per-portion amounts are small, and
  it is the unit ADR 0050 stores), and qty × the moving-average unit cost; "—" when the ingredient is
  uncosted, with a line saying the total is partial. The "HPP dari resep" figure is that total.
- **A line is picked, not typed.** "Tambah bahan" opens the ingredient catalog in a sheet; tapping
  one opens the base-unit keypad (digits only — a recipe quantity is an integer); the quantity is
  written with a full-replace `PUT /menu/{id}/recipe` built by the pure `withLine`/`withoutLine`
  helpers. Tapping a line's quantity reopens the keypad; × removes it. The design's editable name
  and cost fields are not built: the ingredient's name and cost belong to the catalog (ADR 0081), and
  typing a cost here would silently disagree with the moving average.
- **Duplikat is client-side.** There is no clone endpoint: `POST /menu` with the item's fields
  (name + "(salinan)", category, price, currency, photo, unit cost, `autoTrackStock: false`) then
  `PUT recipe` with the source's base lines; the copy's row opens. Modifier groups are not cloned,
  and the note says so.
- **"+ Item" is a sheet, not an empty row.** `POST /menu` needs a name and a positive price, so a
  new item is a bottom sheet (name · category chips from the menu's own categories then the unused
  templates, or a custom one · price) and the created row opens.
- **Hapus item confirms in a Dialog** (N3) and soft-deactivates (`DELETE /menu/{id}`); the row leaves
  the list.
- **Not on the phone page:** per-item stock adjustment (ingredient stock is the real stock since
  ADR 0081; the row shows the legacy quantity read-only), modifier options, and the photo. They stay
  on the desktop tree.

The rules are one pure module, `menu/lib/menuView.ts` (chips, filter, summary, stock meta, margin,
line cost, recipe total, the PUT bodies, the price parser), with tests.

## Consequences

**One place to work on an item.** Price, availability and recipe changes land from the row and the
figures on the row move with them — the HPP total from the recipe, the margin from the HPP summary
once the server re-reads.

**Requests.** The list is three reads (menu, HPP summary, ingredients). Opening a row adds one
(`/recipe`); a search adds one per item, once, cached five minutes.

**Two units on one screen, deliberately.** The inventory catalog shows kg/liter (ADR 0081); a recipe
line shows g/ml, because 15 ml is a quantity and 0.015 liter is a rounding error waiting to happen.
The keypad here takes base units and refuses fractions.

**The desktop is unchanged** — including its recipe drawer, which still edits per-option deltas the
phone page does not show.

**Open.** Reordering lines; per-option recipe deltas on the phone; a bulk price change; reactivating
a deleted item (no endpoint exists on either surface).
