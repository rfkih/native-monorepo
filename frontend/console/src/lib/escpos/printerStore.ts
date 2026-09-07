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
  /**
   * Print the receipt automatically as soon as a sale is paid. OPTIONAL, and absent means ON —
   * read it through {@link autoPrintEnabled}, never `?? false`.
   */
  autoPrint?: boolean
  /** Last-known device label, for the settings UI (informational only). */
  label?: string
  /**
   * Native-app transport only (ADR 0043): the chosen device's bridge id (Bluetooth MAC / usb key).
   * The platform bond owns the pairing, so re-attach by this id is deterministic.
   */
  deviceId?: string
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

/**
 * Whether a paid sale should print itself without anyone tapping Print.
 *
 * Absent means ON. Setting up a printer at all is the opt-in — a till that paired one wants its
 * receipts on paper, and making every device rediscover a buried toggle to get that was the wrong
 * default. An operator who genuinely prints on request only still turns it off, and that explicit
 * `false` is preserved: only the ABSENCE of a choice reads as yes.
 *
 * The `config == null` guard is what keeps this safe to flip: with no printer configured this is
 * false, so a till without a printer is untouched. Auto-print also never falls back to
 * `window.print()` (see ThermalReceipt's `autoPrint` prop), so the worst case of a wrong guess is
 * paper, never an OS dialog popping unprompted after a sale.
 */
export function autoPrintEnabled(config: PrinterConfig | null): boolean {
  return config != null && (config.autoPrint ?? true)
}

/**
 * Whether THIS print should pulse the cash drawer: only when the device's drawer-kick toggle is
 * on AND the sale is an actual cash tender. A card/QRIS/other-tender receipt must never pop the
 * drawer, even with the device toggle left on — popping it for a non-cash sale is a genuine
 * loss-prevention gap, not a cosmetic one (see ThermalReceipt's `cashTender` prop and usePrinter's
 * `printReceipt` doc, which is the caller). A pure predicate so the policy is testable without a
 * DOM/mounted printer context.
 */
export function shouldKickDrawer(deviceDrawerKick: boolean, cashTender: boolean): boolean {
  return deviceDrawerKick && cashTender
}
