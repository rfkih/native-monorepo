/**
 * usePrinter (ADR 0039) — the app-wide connected-thermal-printer context.
 *
 * Holds at most one live {@link PrinterTransport} for the session plus the saved {@link
 * PrinterConfig}. On mount it silently re-attaches a previously-granted USB or serial printer (BLE
 * cannot persist a grant per spec, so it always shows as "reconnect"). The POS receipt surface
 * calls {@link printReceipt}; the settings screen calls the connect/disconnect actions.
 *
 * Printing NEVER throws into the caller's render — errors surface via the returned status so the
 * receipt's window.print() fallback stays available if the device write fails.
 */
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { renderReceipt, type EscposReceiptData, type PaperWidth } from './receipt'
import { loadPrinterConfig, savePrinterConfig, clearPrinterConfig, type PrinterConfig } from './printerStore'
import {
  createRawbtTransport,
  reattachUsbPrinter,
  reattachSerialPrinter,
  requestUsbPrinter,
  requestBlePrinter,
  requestSerialPrinter,
  transportSupport,
  type PrinterTransport,
  type TransportKind,
} from './transport'
import { PrinterContext } from './printerContext'

export function PrinterProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<PrinterConfig | null>(() => loadPrinterConfig())
  const [connected, setConnected] = useState(false)
  const [connectingLabel, setConnectingLabel] = useState<string | null>(null)
  const transportRef = useRef<PrinterTransport | null>(null)
  const support = transportSupport()

  // Silent re-attach of a persisted USB/serial grant on load (BLE cannot persist — skip it).
  // RawBT holds no device grant at all, so it re-attaches unconditionally from the saved config.
  useEffect(() => {
    const saved = loadPrinterConfig()
    if (!saved) return
    let cancelled = false
    ;(async () => {
      const transport =
        saved.transport === 'usb'
          ? await reattachUsbPrinter()
          : saved.transport === 'serial'
            ? await reattachSerialPrinter()
            : saved.transport === 'rawbt' && transportSupport().rawbt
              ? createRawbtTransport()
              : null
      if (cancelled) {
        await transport?.disconnect()
        return
      }
      if (transport) {
        transportRef.current = transport
        setConnected(true)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const connect = useCallback(
    async (kind: TransportKind, paper: PaperWidth, drawerKick: boolean) => {
      setConnectingLabel(kind)
      try {
        await transportRef.current?.disconnect()
        const transport =
          kind === 'usb'
            ? await requestUsbPrinter()
            : kind === 'ble'
              ? await requestBlePrinter()
              : kind === 'serial'
                ? await requestSerialPrinter()
                : createRawbtTransport() // no chooser — RawBT owns the printer link
        transportRef.current = transport
        const next: PrinterConfig = { transport: kind, paper, drawerKick, label: transport.label }
        savePrinterConfig(next)
        setConfig(next)
        setConnected(true)
      } finally {
        setConnectingLabel(null)
      }
    },
    [],
  )

  const disconnect = useCallback(async () => {
    await transportRef.current?.disconnect()
    transportRef.current = null
    setConnected(false)
    clearPrinterConfig()
    setConfig(null)
  }, [])

  const setDrawerKick = useCallback((on: boolean) => {
    setConfig((prev) => {
      if (!prev) return prev
      const next = { ...prev, drawerKick: on }
      savePrinterConfig(next)
      return next
    })
  }, [])

  const printReceipt = useCallback(
    async (data: EscposReceiptData): Promise<boolean> => {
      const transport = transportRef.current
      const cfg = loadPrinterConfig()
      if (!transport || !cfg) return false
      try {
        // A drawer kick only makes sense for a cash sale — the caller signals cash by leaving a
        // non-empty change/tendered row; we keep it simple and honor the device toggle, which the
        // operator sets per till. The encoder emits the pulse before the receipt bytes.
        const bytes = renderReceipt(data, cfg.paper, { drawerKick: cfg.drawerKick })
        await transport.write(bytes)
        return true
      } catch {
        // Device fell asleep / was unplugged mid-print — drop the stale handle so the next attempt
        // re-attaches, and let the caller fall back to the browser print dialog.
        transportRef.current = null
        setConnected(false)
        return false
      }
    },
    [],
  )

  return (
    <PrinterContext.Provider
      value={{
        connected,
        config,
        support,
        connectingLabel,
        connect,
        disconnect,
        setDrawerKick,
        printReceipt,
      }}
    >
      {children}
    </PrinterContext.Provider>
  )
}
