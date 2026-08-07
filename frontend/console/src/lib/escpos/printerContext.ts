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
  connect: (
    kind: TransportKind,
    paper: PaperWidth,
    drawerKick: boolean,
    /** Native (in-app) transport only: the device picked in settings (ADR 0043). */
    native?: { deviceId: string; label: string },
  ) => Promise<void>
  disconnect: () => Promise<void>
  setDrawerKick: (on: boolean) => void
  setAutoPrint: (on: boolean) => void
  /** Live paper-width change for the CONNECTED printer — next print renders at the new width. */
  setPaper: (paper: PaperWidth) => void
  /**
   * Prints the receipt to the connected device. Returns true on success, false if there is no
   * device or the write failed (the caller then falls back to window.print()).
   *
   * `cashTender` gates the drawer kick: it only makes sense to pop the drawer on a CASH sale, so a
   * caller printing a card/QRIS/other-tender receipt should pass `false`. Omitted (or any caller
   * not yet updated, e.g. the settings test print) defaults to `true` — the historical behavior.
   */
  printReceipt: (data: EscposReceiptData, opts?: { cashTender?: boolean }) => Promise<boolean>
  /**
   * Re-attempts the LAST saved connection without leaving the current screen (P1 flow hardening —
   * a cashier mid-sale should never have to abandon the till to reach `/settings/printer`).
   * USB/serial/RawBT/native re-attach silently from the saved config; BLE has no persisted grant
   * (browser spec) so it re-opens the device chooser — legitimate here because the call is only
   * ever made from a user-gesture handler (a "Reconnect" button tap IS the gesture). Returns true
   * on success, false if there is nothing saved to reconnect to or the attempt failed.
   */
  reconnect: () => Promise<boolean>
}

export const PrinterContext = createContext<PrinterContextValue | null>(null)

export function usePrinter(): PrinterContextValue {
  const ctx = useContext(PrinterContext)
  if (!ctx) throw new Error('usePrinter must be used within a PrinterProvider')
  return ctx
}
