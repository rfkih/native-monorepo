/**
 * Adapts the on-screen {@code ThermalProps} data model to the printable {@link EscposReceiptData}
 * (ADR 0039). The two share the same normalized, pre-formatted-label shape by design, so this is a
 * pure field re-map plus the one field the screen renders structurally (the grand-total caption)
 * that the printer needs as text.
 */
import type { EscposReceiptData } from './receipt'

interface Modifier {
  label: string
  deltaLabel?: string
}
interface LineItem {
  qty: number
  name: string
  priceLabel: string
  modifiers: Modifier[]
}
interface Row {
  label: string
  valueLabel: string
}

export interface ThermalToEscposInput {
  businessName: string
  title: string
  reference: string
  tagline?: string
  dateTime: string
  metaRows: Row[]
  lineItems: LineItem[]
  totalRows: Row[]
  grandTotalLabel: string
  /** Localized "Total" caption (the screen shows it as a styled label, the printer as text). */
  grandTotalCaption: string
  paymentRows: Row[]
  footerNote: string
  provisionalNote?: string
}

export function toEscposReceiptData(input: ThermalToEscposInput): EscposReceiptData {
  return {
    businessName: input.businessName,
    title: input.title,
    reference: input.reference,
    tagline: input.tagline,
    dateTime: input.dateTime,
    metaRows: input.metaRows.map((r) => ({ label: r.label, valueLabel: r.valueLabel })),
    lineItems: input.lineItems.map((it) => ({
      qty: it.qty,
      name: it.name,
      priceLabel: it.priceLabel,
      modifiers: it.modifiers.map((m) => ({ label: m.label, deltaLabel: m.deltaLabel })),
    })),
    totalRows: input.totalRows.map((r) => ({ label: r.label, valueLabel: r.valueLabel })),
    grandTotalLabel: input.grandTotalLabel,
    grandTotalCaption: input.grandTotalCaption,
    paymentRows: input.paymentRows.map((r) => ({ label: r.label, valueLabel: r.valueLabel })),
    footerNote: input.footerNote,
    provisionalNote: input.provisionalNote,
  }
}
