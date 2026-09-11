import { formatMoney } from '@/lib/money'
import type { ThermalLineItem } from '@/features/pos/ThermalReceipt'

/**
 * The item block of a receipt, from a sale's or a bill's lines — ONE rule for both papers and
 * for the ESC/POS printer that renders the same model.
 *
 * The rule is that the paper must add up in the customer's hand. The server charges
 * `lineTotal = (unitPrice + Σ modifier deltas) × qty` (OrderLine / BillLine); the receipt used to
 * print that line total on the PRODUCT row and the per-unit delta on each add-on row beneath it —
 * so a 2× Nasi Goreng + Telur read "Rp 54.000 / +Rp 2.000": the product looked more expensive
 * than it is, the add-on was priced for one, and no two numbers on the paper summed to anything
 * (owner report, 2026-09-11). Now:
 *
 *   2× Nasi Goreng          Rp 50.000   ← the product's own price × qty
 *      + Telur              +Rp 4.000   ← the add-on × qty
 *
 * and the subtotal is their sum. A free add-on prints its name only; a negative one keeps its sign.
 */
export interface ReceiptLineInput {
  qty: number
  name: string
  /** The product's BASE unit price — before any modifier. */
  unitPriceMinor: number
  modifiers?: ReadonlyArray<{ nameSnapshot: string; priceDeltaMinor: number }>
}

export function receiptLineItems(
  lines: ReadonlyArray<ReceiptLineInput>,
  currency: string,
  locale: string,
): ThermalLineItem[] {
  return lines.map((line) => ({
    qty: line.qty,
    name: line.name,
    priceLabel: formatMoney(line.unitPriceMinor * line.qty, currency, locale),
    modifiers: (line.modifiers ?? []).map((mod) => {
      const extended = mod.priceDeltaMinor * line.qty
      return {
        label: mod.nameSnapshot,
        deltaLabel:
          extended !== 0 ? `${extended > 0 ? '+' : ''}${formatMoney(extended, currency, locale)}` : undefined,
      }
    }),
  }))
}
