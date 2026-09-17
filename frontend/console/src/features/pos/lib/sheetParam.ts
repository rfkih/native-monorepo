/**
 * sheetParam — the till's ONE deep-link: `/pos?sheet=<name>` opens one of its overlays on arrival.
 *
 * The home's figure cards are doors (ADR 0082 amendment): "Transaksi" lands on the sales-history
 * sheet, "Tagihan terbuka" on the order switcher. Neither overlay is a route — they are sheets the
 * till parks over `/pos` (ADR 0075) — so the card cannot link to them directly; it names the sheet
 * in a query parameter and Pos.tsx opens it once, then strips the parameter with `replace` so a
 * re-render never reopens it and Back still pops cleanly.
 *
 * An allow-list, not a passthrough: a stray `?sheet=anything` opens nothing.
 */
export type PosSheet = 'history' | 'orders'

export function sheetFromParam(value: string | null | undefined): PosSheet | null {
  if (value === 'history' || value === 'orders') return value
  return null
}
