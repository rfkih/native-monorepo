/**
 * The printer context object + the {@code usePrinter} hook (ADR 0039). Split from the provider
 * component (usePrinter.tsx) so each file satisfies react-refresh/only-export-components — the
 * same split the session context uses.
 */
import { createContext, useContext } from 'react'
import type { EscposReceiptData, PaperWidth } from './receipt'
import type { TransportKind } from './transport'
import type { PrinterConfig } from './printerStore'

export interface PrinterContextValue {
  /** True when a live device is connected and ready to print silently. */
  connected: boolean
  config: PrinterConfig | null
  support: Record<TransportKind, boolean>
  connectingLabel: string | null
  connect: (kind: TransportKind, paper: PaperWidth, drawerKick: boolean) => Promise<void>
  disconnect: () => Promise<void>
  setDrawerKick: (on: boolean) => void
  setAutoPrint: (on: boolean) => void
  /** Live paper-width change for the CONNECTED printer — next print renders at the new width. */
  setPaper: (paper: PaperWidth) => void
  /**
   * Prints the receipt to the connected device. Returns true on success, false if there is no
   * device or the write failed (the caller then falls back to window.print()).
   */
  printReceipt: (data: EscposReceiptData) => Promise<boolean>
}

export const PrinterContext = createContext<PrinterContextValue | null>(null)

export function usePrinter(): PrinterContextValue {
  const ctx = useContext(PrinterContext)
  if (!ctx) throw new Error('usePrinter must be used within a PrinterProvider')
  return ctx
}
