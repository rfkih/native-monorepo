/**
 * menuView.ts — the rules behind the phone menu work page (ADR 0083, "Manajemen Menu").
 *
 * Everything an owner does to one item happens on its row; this module holds the parts of that
 * which are pure: which chips the menu earns and their counts, how a query narrows the list (item
 * name OR the recipe's ingredient names), the summary line, the read-only stock meta, the row's
 * margin, a recipe line's derived cost, and the immutable PUT bodies for the full-replace recipe
 * write. Minor-unit integers in, plain values out — formatting is the screen's job.
 *
 * HPP is never typed: a line costs qty × the ingredient's moving-average unit cost (ADR 0056),
 * so the figures here only ever read the cost the catalog already carries.
 */
import { isoMinorExponent } from '@/lib/money'
import { computeMarginRatio, type PutRecipeLineInput } from '../recipeApi'

/** The fields this module reads off a menu item. */
export interface MenuItemLike {
  id: string
  name: string
  category: string
  priceMinor: number
  currency: string
  available: boolean
  stockQuantity: number | null
}

/** The fields this module reads off a recipe line. */
export interface RecipeLineLike {
  ingredientId: string
  ingredientName: string
  unit: string
  modifierOptionId: string | null
  qtyPerPortion: number
  unitCostMinor: number | null
  costCurrency: string | null
}

/** What the HPP summary contributes per item. */
export interface HppLike {
  unitHppMinor: number | null
  hppCurrency: string | null
}

/** The chip that shows every item. */
export const ALL_CHIP = 'all'

/** At or under this many units a tracked item reads as running out — the desktop pill's threshold. */
export const LOW_STOCK_UNITS = 5

export interface CategoryChip {
  /** `ALL_CHIP`, the canonical category key, or '' for uncategorised. */
  key: string
  /** Display name; '' for the all chip (the screen supplies its own word). */
  label: string
  count: number
}

/**
 * One chip per category the menu actually uses, counted, sorted by display name in the locale;
 * "all" first, the uncategorised bucket last. `canon` and `display` are `categoryCanon.ts`'s
 * (kept as parameters so this stays pure of i18next).
 */
export function categoryChips(
  items: readonly MenuItemLike[],
  canon: (name: string) => string,
  display: (name: string) => string,
  uncategorisedLabel: string,
  locale: string,
): CategoryChip[] {
  const byKey = new Map<string, CategoryChip>()
  for (const it of items) {
    const raw = it.category.trim()
    const key = raw === '' ? '' : canon(raw)
    const cur = byKey.get(key)
    if (cur) cur.count += 1
    else byKey.set(key, { key, label: raw === '' ? uncategorisedLabel : display(raw), count: 1 })
  }
  const named = [...byKey.values()]
    .filter((c) => c.key !== '')
    .sort((a, b) => a.label.localeCompare(b.label, locale))
  const none = byKey.get('')
  return [{ key: ALL_CHIP, label: '', count: items.length }, ...named, ...(none ? [none] : [])]
}

/**
 * The chip narrows first, then the query: an item stays when its name OR any of its recipe's
 * ingredient names contains the query (case-folded in the locale). An item whose recipe is not
 * known yet still matches on its own name.
 */
export function filterItems<T extends MenuItemLike>(
  items: readonly T[],
  chip: string,
  query: string,
  ingredientNamesById: ReadonlyMap<string, readonly string[]>,
  canon: (name: string) => string,
  locale: string,
): T[] {
  const needle = query.trim().toLocaleLowerCase(locale)
  return items.filter((it) => {
    if (chip !== ALL_CHIP) {
      const raw = it.category.trim()
      const key = raw === '' ? '' : canon(raw)
      if (key !== chip) return false
    }
    if (needle === '') return true
    if (it.name.toLocaleLowerCase(locale).includes(needle)) return true
    const names = ingredientNamesById.get(it.id) ?? []
    return names.some((n) => n.toLocaleLowerCase(locale).includes(needle))
  })
}

export interface MenuSummary {
  active: number
  /** Items the owner marked unavailable (the 86 flag), not stock-outs. */
  soldOut: number
  /** Mean margin over the items that have both an HPP and a positive price, or null when none do. */
  avgMargin: number | null
}

export function summary(
  items: readonly MenuItemLike[],
  hppById: ReadonlyMap<string, HppLike>,
): MenuSummary {
  const margins = items
    .map((it) => marginOf(it, hppById.get(it.id)))
    .filter((m): m is number => m != null)
  return {
    active: items.length,
    soldOut: items.filter((it) => !it.available).length,
    avgMargin: margins.length > 0 ? margins.reduce((s, m) => s + m, 0) / margins.length : null,
  }
}

export type StockKind = 'untracked' | 'zero' | 'low' | 'ok'

/** The read-only stock word in the meta line; `low` is what the design sets in a heavier weight. */
export function stockMeta(item: MenuItemLike): { kind: StockKind; qty: number | null } {
  const qty = item.stockQuantity
  if (qty == null) return { kind: 'untracked', qty: null }
  if (qty === 0) return { kind: 'zero', qty }
  if (qty <= LOW_STOCK_UNITS) return { kind: 'low', qty }
  return { kind: 'ok', qty }
}

/** The row's margin ratio, or null when there is no HPP to set against the price. */
export function marginOf(item: MenuItemLike, hpp: HppLike | undefined): number | null {
  if (!hpp) return null
  return computeMarginRatio(item.priceMinor, item.currency, hpp.unitHppMinor, hpp.hppCurrency)
}

/** qty × the ingredient's unit cost, or null when the ingredient carries no cost yet. */
export function lineCostMinor(line: RecipeLineLike): number | null {
  return line.unitCostMinor == null ? null : line.qtyPerPortion * line.unitCostMinor
}

/**
 * The recipe's own total over BASE lines (option deltas are per-option extras, not the portion).
 * `partial` says a base line is uncosted, so the total under-reads the true HPP.
 */
export function recipeTotal(lines: readonly RecipeLineLike[]): {
  minor: number | null
  partial: boolean
  baseCount: number
} {
  const base = lines.filter((l) => l.modifierOptionId == null)
  if (base.length === 0) return { minor: null, partial: false, baseCount: 0 }
  let minor = 0
  let partial = false
  for (const l of base) {
    const c = lineCostMinor(l)
    if (c == null) partial = true
    else minor += c
  }
  return { minor, partial, baseCount: base.length }
}

function toInput(l: RecipeLineLike): PutRecipeLineInput {
  return {
    ingredientId: l.ingredientId,
    modifierOptionId: l.modifierOptionId,
    qtyPerPortion: l.qtyPerPortion,
  }
}

/** The BASE lines as a PUT body for a copy — option deltas belong to option ids the copy lacks. */
export function cloneLines(lines: readonly RecipeLineLike[]): PutRecipeLineInput[] {
  return lines.filter((l) => l.modifierOptionId == null).map(toInput)
}

/** The full-replace body with a base line for `ingredientId` at `qty` — added, or requantified if present. */
export function withLine(
  lines: readonly RecipeLineLike[],
  ingredientId: string,
  qty: number,
): PutRecipeLineInput[] {
  const next = lines.map(toInput)
  const i = next.findIndex((l) => l.ingredientId === ingredientId && l.modifierOptionId == null)
  if (i >= 0) next[i] = { ...next[i], qtyPerPortion: qty }
  else next.push({ ingredientId, modifierOptionId: null, qtyPerPortion: qty })
  return next
}

/** The full-replace body without the base line for `ingredientId`. */
export function withoutLine(
  lines: readonly RecipeLineLike[],
  ingredientId: string,
): PutRecipeLineInput[] {
  return lines
    .filter((l) => !(l.ingredientId === ingredientId && l.modifierOptionId == null))
    .map(toInput)
}

/** The full-replace body with the base line for `ingredientId` at a new quantity. */
export function withQty(
  lines: readonly RecipeLineLike[],
  ingredientId: string,
  qty: number,
): PutRecipeLineInput[] {
  return withLine(lines, ingredientId, qty)
}

/**
 * The typed price in major units → minor units for the currency (IDR none, USD two), or null
 * when blank, unparseable or not positive — a sale of zero is not a price.
 */
export function parseMajorPrice(raw: string, currency: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const value = Number(trimmed.replace(',', '.'))
  if (!Number.isFinite(value) || value <= 0) return null
  const minor = Math.round(value * 10 ** isoMinorExponent(currency))
  return minor > 0 ? minor : null
}
