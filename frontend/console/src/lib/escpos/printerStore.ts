/**
 * Saved-printer preferences (ADR 0039) — a tiny localStorage store, per-TAB-agnostic and shared
 * across the login on this device (a till's printer belongs to the device, not the tenant). Holds
 * only reconnection HINTS: the browser owns the actual device grant (WebUSB/WebSerial persist it;
 * BLE must re-pair each session by spec). We persist the chosen transport + paper width + the
 * cash-drawer-kick toggle so the POS can silently re-attach a USB/serial printer on load and knows
 * the column width to render at.
 */
import type { PaperWidth } from './receipt'
import type { TransportKind } from './transport'

export interface PrinterConfig {
  transport: TransportKind
  paper: PaperWidth
  /** Pulse the cash drawer on each cash receipt. */
  drawerKick: boolean
  /** Print the receipt automatically as soon as a sale is paid (optional — older configs lack it). */
  autoPrint?: boolean
  /** Last-known device label, for the settings UI (informational only). */
  label?: string
}

const KEY = 'native.pos.printer'

export function loadPrinterConfig(): PrinterConfig | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as PrinterConfig
    if (parsed.transport && (parsed.paper === 58 || parsed.paper === 80)) return parsed
    return null
  } catch {
    return null
  }
}

export function savePrinterConfig(config: PrinterConfig): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(config))
  } catch {
    /* storage unavailable — the session keeps the in-memory transport regardless */
  }
}

export function clearPrinterConfig(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}
