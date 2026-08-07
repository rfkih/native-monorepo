/**
 * Receipt layout for ESC/POS paper (ADR 0039) — renders the SAME normalized data model the
 * on-screen {@code ThermalReceipt} displays (pre-formatted label strings, rule 9/8 upheld by the
 * callers) into printer bytes. One source of truth for receipt content; this module only does
 * monospace column math: 32 columns on 58 mm paper, 48 on 80 mm.
 */
import { EscposEncoder, toAscii } from './encoder'

export type PaperWidth = 58 | 80

export interface EscposRow {
  label: string
  valueLabel: string
}

export interface EscposLineItem {
  qty: number
  name: string
  priceLabel: string
  modifiers: { label: string; deltaLabel?: string }[]
}

/** The printable subset of ThermalProps — data only, no callbacks/UI flags. */
export interface EscposReceiptData {
  businessName: string
  title: string
  reference: string
  tagline?: string
  dateTime: string
  metaRows: EscposRow[]
  lineItems: EscposLineItem[]
  totalRows: EscposRow[]
  grandTotalLabel: string
  grandTotalCaption: string
  paymentRows: EscposRow[]
  footerNote: string
  /** Rendered as a solid banner when set (offline provisional receipts — ADR 0028). */
  provisionalNote?: string
}

export function columnsFor(paper: PaperWidth): number {
  return paper === 58 ? 32 : 48
}

/** Left text + right text on one line, middle padded; left side truncated if it must be. */
function twoColumns(left: string, right: string, cols: number): string {
  const l = toAscii(left)
  const r = toAscii(right)
  const space = cols - r.length - 1
  const leftFitted = l.length > space ? l.slice(0, Math.max(space - 1, 0)) + '.' : l
  return leftFitted + ' '.repeat(Math.max(cols - leftFitted.length - r.length, 1)) + r
}

/** Greedy word-wrap at the column width (long unbroken tokens hard-split). */
function wrap(text: string, cols: number): string[] {
  const words = toAscii(text).split(/\s+/).filter(Boolean)
  const lines: string[] = []
  let current = ''
  for (const word of words) {
    if (word.length > cols) {
      if (current) lines.push(current)
      for (let i = 0; i < word.length; i += cols) lines.push(word.slice(i, i + cols))
      current = ''
      continue
    }
    if (!current) current = word
    else if (current.length + 1 + word.length <= cols) current += ' ' + word
    else {
      lines.push(current)
      current = word
    }
  }
  if (current) lines.push(current)
  return lines.length ? lines : ['']
}

function divider(cols: number): string {
  return '-'.repeat(cols)
}

export function renderReceipt(
  data: EscposReceiptData,
  paper: PaperWidth,
  options: { drawerKick: boolean },
): Uint8Array {
  const cols = columnsFor(paper)
  const e = new EscposEncoder().init()

  if (options.drawerKick) {
    e.drawerKick()
  }

  // Header — business name emphasized double-width when it fits at half columns.
  e.align('center').bold(true)
  const name = toAscii(data.businessName.toUpperCase())
  if (name.length <= Math.floor(cols / 2)) {
    e.size(2, 2).line(name).size(1, 1)
  } else {
    for (const l of wrap(name, cols)) e.line(l)
  }
  e.bold(false)
  e.line(toAscii(data.title.toUpperCase()))
  if (data.tagline) {
    for (const l of wrap(data.tagline, cols)) e.line(l)
  }
  e.align('left').line(divider(cols))

  if (data.provisionalNote) {
    e.align('center').bold(true)
    for (const l of wrap(data.provisionalNote.toUpperCase(), cols)) e.line(l)
    e.bold(false).align('left').line(divider(cols))
  }

  // Meta — date/reference, then caller-provided rows.
  e.line(twoColumns(data.dateTime, data.reference, cols))
  for (const row of data.metaRows) {
    e.line(twoColumns(row.label, row.valueLabel, cols))
  }
  e.line(divider(cols))

  // Items — "2x Nasi Goreng" wrapped, price right-aligned on the last line when it fits,
  // otherwise on its own right-aligned line. Modifiers indented beneath.
  for (const item of data.lineItems) {
    const head = `${item.qty}x ${item.name}`
    const lines = wrap(head, cols)
    const last = lines[lines.length - 1]
    const price = toAscii(item.priceLabel)
    if (last.length + 1 + price.length <= cols) {
      for (let i = 0; i < lines.length - 1; i++) e.line(lines[i])
      e.line(twoColumns(last, price, cols))
    } else {
      for (const l of lines) e.line(l)
      e.line(' '.repeat(Math.max(cols - price.length, 0)) + price)
    }
    for (const mod of item.modifiers) {
      const modText = `+ ${mod.label}`
      if (mod.deltaLabel) {
        e.line(twoColumns('  ' + modText, mod.deltaLabel, cols))
      } else {
        for (const l of wrap(modText, cols - 2)) e.line('  ' + l)
      }
    }
  }
  e.line(divider(cols))

  // Totals, then the grand total emphasized double-height.
  for (const row of data.totalRows) {
    e.line(twoColumns(row.label, row.valueLabel, cols))
  }
  e.bold(true).size(1, 2)
  e.line(twoColumns(data.grandTotalCaption, data.grandTotalLabel, cols))
  e.size(1, 1).bold(false)

  if (data.paymentRows.length > 0) {
    e.line(divider(cols))
    for (const row of data.paymentRows) {
      e.line(twoColumns(row.label, row.valueLabel, cols))
    }
  }

  // Footer. (The reference deliberately does NOT repeat here — it already printed in the
  // header meta row; the duplicate cost a line of paper per receipt.)
  e.line(divider(cols)).align('center')
  for (const l of wrap(data.footerNote, cols)) e.line(l)
  e.align('left')

  // 3 lines clears the tear bar/cutter on the common mechanisms; more just wastes paper.
  e.feed(3).cut()
  return e.build()
}
