/**
 * displayPayload.ts — pure builders for the customer-display BroadcastChannel payloads,
 * extracted from Pos.tsx's publishCurrentDisplayState (redesign P1).
 *
 * These are the ONLY places CART_UPDATED payload shapes are assembled — tested against
 * displayChannel.ts's isDisplayMessage validator so a redesign can never silently drift the
 * wire shape and freeze the customer display (the protocol itself lives in displayChannel.ts
 * and MUST NOT change).
 */
import type { DisplayBreakdown, DisplayLine } from '../display/displayChannel'

/** The slice of a BillSummaryResponse the display payload needs. */
export interface BillTotalsLike {
  runningTotalMinor: number
  currency: string
}

/** The slice of a cart line the display payload needs. */
export interface CartLineLike {
  menuItemId: string
  qty: number
  effectiveUnitPriceMinor: number
}

/** The slice of PriceBreakdownResponse the display payload needs. */
export interface BreakdownLike {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
}

/**
 * Bill mode: BillSummaryResponse carries no per-line detail (see its class doc) — the display
 * shows the running total only while a bill is open, no itemised lines.
 */
export function billDisplayBreakdown(activeBill: BillTotalsLike): DisplayBreakdown {
  return {
    subtotalMinor: activeBill.runningTotalMinor,
    discountMinor: 0,
    serviceChargeMinor: 0,
    taxMinor: 0,
    grandTotalMinor: activeBill.runningTotalMinor,
    currency: activeBill.currency,
  }
}

/** Walk-in cart lines, item names resolved from the menu (missing item → empty name, never a crash). */
export function cartDisplayLines(
  cart: CartLineLike[],
  items: { id: string; name: string }[],
): DisplayLine[] {
  return cart.map((l) => ({
    name: items.find((i) => i.id === l.menuItemId)?.name ?? '',
    qty: l.qty,
    unitPriceMinor: l.effectiveUnitPriceMinor,
    lineTotalMinor: l.effectiveUnitPriceMinor * l.qty,
  }))
}

/**
 * Walk-in cart breakdown: the live quote when present, else a client-side fallback (subtotal
 * only — no rule detail without a quote).
 */
export function cartDisplayBreakdown(
  breakdown: BreakdownLike | null,
  fallback: { clientSubtotalMinor: number; grandTotalMinor: number; currency: string },
): DisplayBreakdown {
  return breakdown
    ? {
        subtotalMinor: breakdown.subtotalMinor,
        discountMinor: breakdown.discountMinor,
        serviceChargeMinor: breakdown.serviceChargeMinor,
        taxMinor: breakdown.taxMinor,
        grandTotalMinor: breakdown.grandTotalMinor,
        currency: breakdown.currency,
      }
    : {
        subtotalMinor: fallback.clientSubtotalMinor,
        discountMinor: 0,
        serviceChargeMinor: 0,
        taxMinor: 0,
        grandTotalMinor: fallback.grandTotalMinor,
        currency: fallback.currency,
      }
}
