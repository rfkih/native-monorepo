/**
 * newBillForm.ts — the rules behind the phone "Tagihan baru" form (ADR 0084).
 *
 * The form records an incoming vendor invoice and refuses to save one the books cannot defend:
 * a line without a name or a price, a number the same vendor already used, a computed total that
 * differs from the figure printed on the paper, no evidence attached. Everything decidable is
 * decided here, pure: strings the owner typed in, minor-unit integers and issue keys out. The
 * screen formats; finance posts.
 *
 * Money rule 8 throughout: prices parse through `parseMajorPrice` (grouping-aware — "15.000" is
 * fifteen thousand rupiah), quantities through the inventory unit helpers, and the line total is
 * `round(qty × price)` in minor units — never a float on the wire.
 */
import type { CreateBillLineBody } from '../api'
import { parseMajorPrice } from '@/features/menu/lib/menuView'
import { parsePackedQtyBase } from '@/features/inventory/lib/packQty'
import {
  shownFactor,
  shownUnit,
  toDisplayQty,
  type UnitBearing,
} from '@/features/inventory/lib/units'

/** The PPN rates the form offers, in basis points. */
export const TAX_BP_OPTIONS = [0, 1100, 1200] as const
export type TaxBp = (typeof TAX_BP_OPTIONS)[number]

/** The payment terms the chips offer, in days (0 = cash on delivery). */
export const TERM_OPTIONS = [0, 7, 14, 30, 60] as const

/** The default when neither the vendor nor the owner said — finance's own post-time default. */
export const DEFAULT_TERM_DAYS = 30

/** What a line needs to know about its ingredient (a catalog row, or the id/name it carried). */
export interface IngredientRef extends UnitBearing {
  id: string
  name: string
  /** V46 — how many BASE units one pack usually holds (tortilla: 20 pcs; a flour sack on a g-based
   *  item: 25 000); `null` = no remembered default. Pre-fills the line's pack size, never written back. */
  packSize: number | null
}

/** One line as the owner types it; the kind is the "account" chip — Beban (5000) or Persediaan (5100). */
export type DraftLine =
  | {
      key: string
      kind: 'expense'
      description: string
      /** Free quantity (a decimal is allowed; a non-integer is folded into the price on the wire). */
      qty: string
      /** Unit price, major units. */
      price: string
    }
  | {
      key: string
      kind: 'inventory'
      ingredient: IngredientRef | null
      /** The receipt's wording; empty = the ingredient's name. */
      description: string
      /**
       * Quantity in the ingredient's SHOWN unit (kg/liter admit decimals) — or, once `packSizeInput`
       * is set, the NUMBER OF PACKS (a whole number; you don't buy half a pack).
       */
      qty: string
      /**
       * Price per SHOWN unit — or, in pack mode, the price per PACK exactly as the invoice prints
       * it. Major units. The line total is `qty × price` either way; the per-unit cost the books
       * end up with is derived from the total (`perShownUnitMinor`), never typed.
       */
      price: string
      /**
       * "Isi per kemasan" — the vendor sells by the PACK while stock counts CONTENTS (a receipt says
       * "TORTILLA 1 PCS" for a pack of 20). Blank = plain shown-unit purchase; non-blank = how many of
       * the ingredient's SHOWN unit one pack holds (decimal allowed for kg/liter, whole for pcs) —
       * the desktop form's `InventoryLineDraft.packSizeInput` verbatim, so both forms feed
       * `parsePackedQtyBase` the same way.
       */
      packSizeInput: string
    }

export type LineIssue = 'name' | 'ingredient' | 'amount'

export type ParsedLine =
  | {
      ok: true
      body: CreateBillLineBody
      totalMinor: number
      unit: string | null
      /** Pack mode only — how many packs were counted (for the "N × isi = hasil" readback). */
      packs: number | null
      /** Inventory lines — the BASE quantity that lands in stock; 0 on an expense line. */
      qtyBase: number
    }
  | { ok: false; issue: LineIssue }

function parseDecimal(raw: string): number | null {
  const trimmed = raw.trim().replace(',', '.')
  if (trimmed === '' || !/^\d*\.?\d*$/.test(trimmed) || trimmed === '.') return null
  const value = Number(trimmed)
  return Number.isFinite(value) && value > 0 ? value : null
}

/**
 * One line → the wire body and its total, or the first thing wrong with it. An expense line with a
 * whole quantity travels as `quantity × unitPriceMinor`; a fractional one as one unit at the
 * rounded total (the wire quantity is an integer). An inventory line travels the ADR 0072 way:
 * `quantity: 1`, `unitPriceMinor` = the line total, and the real quantity in `ingredientQtyBase`.
 */
export function parseLine(line: DraftLine, currency: string): ParsedLine {
  if (line.kind === 'expense') {
    const description = line.description.trim()
    if (description === '') return { ok: false, issue: 'name' }
    const qty = parseDecimal(line.qty)
    const priceMinor = parseMajorPrice(line.price, currency)
    if (qty == null || priceMinor == null) return { ok: false, issue: 'amount' }
    if (Number.isInteger(qty)) {
      return {
        ok: true,
        totalMinor: qty * priceMinor,
        unit: null,
        packs: null,
        qtyBase: 0,
        body: { description, quantity: qty, unitPriceMinor: priceMinor, inventory: false },
      }
    }
    const totalMinor = Math.round(qty * priceMinor)
    if (totalMinor <= 0) return { ok: false, issue: 'amount' }
    return {
      ok: true,
      totalMinor,
      unit: null,
      packs: null,
      qtyBase: 0,
      body: { description, quantity: 1, unitPriceMinor: totalMinor, inventory: false },
    }
  }
  if (!line.ingredient) return { ok: false, issue: 'ingredient' }
  const ingredient = line.ingredient
  const description = line.description.trim() || ingredient.name
  // Pack mode (non-blank pack size): `qty` counts packs and `price` is per pack, so the SAME
  // `qty × price` is the line total; only the stock quantity goes through the pack maths.
  const packed = parsePackedQtyBase(line.qty, line.packSizeInput, ingredient)
  const priceMinor = parseMajorPrice(line.price, currency)
  if (!packed || priceMinor == null) return { ok: false, issue: 'amount' }
  const count = parseDecimal(line.qty) ?? 0
  const totalMinor = Math.round(count * priceMinor)
  if (totalMinor <= 0) return { ok: false, issue: 'amount' }
  return {
    ok: true,
    totalMinor,
    unit: shownUnit(ingredient),
    packs: packed.packs,
    qtyBase: packed.qtyBase,
    body: {
      description,
      quantity: 1,
      unitPriceMinor: totalMinor,
      inventory: true,
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
      ingredientQtyBase: packed.qtyBase,
    },
  }
}

/**
 * The value the "Isi per kemasan" field is SEEDED with from the ingredient's remembered `packSize`
 * (BASE units → the shown unit, so a 25 000 g default on a kg item reads "25"); '' when there is no
 * default. The desktop form's `packSizeToShownInput`, shared here.
 */
export function packSizeInputOf(ref: IngredientRef): string {
  return ref.packSize != null ? String(toDisplayQty(ref.packSize, ref)) : ''
}

/**
 * What ONE shown unit costs on this line, minor units — the "Rp 1.500 / pcs" the owner reads back
 * after typing the invoice's per-pack price. Derived from the EXACT line total (`total × factor /
 * base qty`), the same rule as `units.ts`'s `shownUnitCostMinor`: rounding a per-gram cost first
 * and scaling it ×1000 would distort a cheap item. `null` when there is no quantity to divide by.
 */
export function perShownUnitMinor(totalMinor: number, qtyBase: number, ref: UnitBearing): number | null {
  if (qtyBase <= 0) return null
  return Math.round((totalMinor * shownFactor(ref)) / qtyBase)
}

export interface Totals {
  subtotalMinor: number
  /** The discount actually applied — clamped to the subtotal. */
  discountMinor: number
  /** Dasar pengenaan pajak: subtotal − discount. */
  dppMinor: number
  taxMinor: number
  totalMinor: number
}

/**
 * `value × bp / 10 000` rounded HALF_EVEN — exactly finance's `Money.applyBasisPoints` (`mulDiv`,
 * `RoundingMode.HALF_EVEN`), so the total the form checks against the paper is the total the
 * server will store. `Math.round` (half-up) would differ by one minor unit on every exact half.
 */
export function applyBasisPoints(valueMinor: number, bp: number): number {
  const numerator = valueMinor * bp
  const q = Math.trunc(numerator / 10_000)
  const r = numerator - q * 10_000
  const twice = r * 2
  if (twice > 10_000) return q + 1
  if (twice < 10_000) return q
  return q % 2 === 0 ? q : q + 1
}

/** The summary card, over the VALID lines only (an invalid line adds nothing until it is fixed). */
export function totals(
  lineTotalsMinor: readonly number[],
  discountMinor: number,
  taxBp: number,
): Totals {
  const subtotal = lineTotalsMinor.reduce((s, t) => s + t, 0)
  const discount = Math.min(Math.max(0, Math.floor(discountMinor)), subtotal)
  const dpp = subtotal - discount
  const tax = applyBasisPoints(dpp, taxBp)
  return {
    subtotalMinor: subtotal,
    discountMinor: discount,
    dppMinor: dpp,
    taxMinor: tax,
    totalMinor: dpp + tax,
  }
}

export type Reconciliation =
  | { state: 'empty' }
  | { state: 'match' }
  | { state: 'higher'; diffMinor: number }
  | { state: 'lower'; diffMinor: number }

/** The printed total against the computed one; the diff is always positive and says which way. */
export function reconcile(totalMinor: number, printedMinor: number | null): Reconciliation {
  if (printedMinor == null || printedMinor <= 0) return { state: 'empty' }
  if (printedMinor === totalMinor) return { state: 'match' }
  return printedMinor > totalMinor
    ? { state: 'higher', diffMinor: printedMinor - totalMinor }
    : { state: 'lower', diffMinor: totalMinor - printedMinor }
}

/** `billDate` (YYYY-MM-DD) plus `termDays`, as YYYY-MM-DD; null when the date is unparseable. */
export function dueDateOf(billDate: string, termDays: number): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(billDate)) return null
  const at = new Date(`${billDate}T00:00:00Z`)
  if (Number.isNaN(at.getTime())) return null
  return new Date(at.getTime() + termDays * 86_400_000).toISOString().slice(0, 10)
}

/** The term in force: the owner's pick while it stands, else the vendor's default, else 30. */
export function effectiveTerms(
  vendorTerm: number | null | undefined,
  override: number | null,
): number {
  if (override != null) return override
  if (vendorTerm != null && vendorTerm >= 0) return vendorTerm
  return DEFAULT_TERM_DAYS
}

/** Whether `number` is already on a live bill of `vendorId` (case-insensitive, VOID excluded). */
export function isDuplicateInvoice(
  bills: readonly { vendorId: string; status: string; vendorInvoiceNumber: string | null }[],
  vendorId: string | null,
  number: string,
): boolean {
  const needle = number.trim().toLocaleLowerCase()
  if (!vendorId || needle === '') return false
  return bills.some(
    (b) =>
      b.vendorId === vendorId &&
      b.status !== 'VOID' &&
      b.vendorInvoiceNumber != null &&
      b.vendorInvoiceNumber.trim().toLocaleLowerCase() === needle,
  )
}

export type Issue =
  | { key: 'vendor' }
  | { key: 'invoiceNumber' }
  | { key: 'duplicate' }
  | { key: 'noLines' }
  | { key: 'unnamed'; count: number }
  | { key: 'unpriced'; count: number }
  | { key: 'printed' }
  | { key: 'mismatch'; diffMinor: number }
  | { key: 'attachment' }

/**
 * The checklist — every reason the bill cannot be saved yet, in the order the form shows them. An
 * empty list is "ready". Mirrors the design's issue list one for one; nothing here is advisory.
 */
export function checklist(input: {
  vendorId: string | null
  invoiceNumber: string
  duplicate: boolean
  lines: readonly ParsedLine[]
  reconciliation: Reconciliation
  attached: boolean
}): Issue[] {
  const issues: Issue[] = []
  if (!input.vendorId) issues.push({ key: 'vendor' })
  if (input.invoiceNumber.trim() === '') issues.push({ key: 'invoiceNumber' })
  if (input.duplicate) issues.push({ key: 'duplicate' })
  if (input.lines.length === 0) issues.push({ key: 'noLines' })
  const unnamed = input.lines.filter(
    (l) => !l.ok && (l.issue === 'name' || l.issue === 'ingredient'),
  ).length
  const unpriced = input.lines.filter((l) => !l.ok && l.issue === 'amount').length
  if (unnamed > 0) issues.push({ key: 'unnamed', count: unnamed })
  if (unpriced > 0) issues.push({ key: 'unpriced', count: unpriced })
  if (input.reconciliation.state === 'empty') issues.push({ key: 'printed' })
  else if (input.reconciliation.state !== 'match')
    issues.push({ key: 'mismatch', diffMinor: input.reconciliation.diffMinor })
  if (!input.attached) issues.push({ key: 'attachment' })
  return issues
}

/** The avatar monogram: the legal-form prefix (CV/PT/UD) dropped, first letters of two words. */
export function vendorInitials(name: string): string {
  const words = name
    .trim()
    .replace(/^(CV|PT|UD|PD|Tb)\.?\s+/i, '')
    .split(/\s+/)
    .filter(Boolean)
  return words
    .slice(0, 2)
    .map((w) => w[0].toLocaleUpperCase())
    .join('')
}
