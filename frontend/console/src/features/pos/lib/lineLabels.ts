import { formatMoney } from '@/lib/money'

/**
 * How the CASHIER's line rows name a product and its add-ons — the till-side twin of
 * `receiptLines.ts` (the customer's paper). Same rule, different shape: a dock or bill row shows
 * unit economics ("2 × Rp 25.000") and the line total, so each add-on carries its PER-UNIT price
 * on its own row and the unit price is the product's own:
 *
 *   Nasi Goreng                              Rp 54.000
 *   Telur (+Rp 2.000)
 *   2 × Rp 25.000
 *
 * and 2 × (25.000 + 2.000) = 54.000 reads at a glance. It used to show "2 × Rp 27.000" with the
 * add-on named but unpriced — the product looked dearer than it is, and the add-on looked free
 * (owner report, 2026-09-11).
 */
export interface LineModifierLike {
  nameSnapshot: string
  priceDeltaMinor: number
}

/** "Telur (+Rp 2.000)" — or just "Less ice" when the add-on is free. */
export function modifierLabel(mod: LineModifierLike, currency: string, locale: string): string {
  if (mod.priceDeltaMinor === 0) return mod.nameSnapshot
  const sign = mod.priceDeltaMinor > 0 ? '+' : ''
  return `${mod.nameSnapshot} (${sign}${formatMoney(mod.priceDeltaMinor, currency, locale)})`
}

/** "Telur (+Rp 2.000), Less ice" — the add-ons of one line, on their own wrapping row under the
 *  product name (a suffix on the name would truncate away the very price this exists to show).
 *  Empty when there are none. */
export function modifierList(
  mods: ReadonlyArray<LineModifierLike>,
  currency: string,
  locale: string,
): string {
  return mods.map((m) => modifierLabel(m, currency, locale)).join(', ')
}

/** The product's own unit price, recovered from the effective price the cart was charged at. */
export function baseUnitPrice(
  effectiveUnitPriceMinor: number,
  mods: ReadonlyArray<LineModifierLike>,
): number {
  return effectiveUnitPriceMinor - mods.reduce((sum, m) => sum + m.priceDeltaMinor, 0)
}
