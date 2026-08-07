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
import {
  loadPrinterConfig,
  savePrinterConfig,
  clearPrinterConfig,
  shouldKickDrawer,
  type PrinterConfig,
} from './printerStore'
import {
  createRawbtTransport,
  requestNativePrinter,
  requestUsbPrinter,
  requestBlePrinter,
  requestSerialPrinter,
  silentReattach,
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

  // Silent re-attach of a persisted grant on load — see silentReattach's doc for what can/can't.
  useEffect(() => {
    const saved = loadPrinterConfig()
    if (!saved) return
    let cancelled = false
    ;(async () => {
      const transport = await silentReattach(saved)
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
    async (
      kind: TransportKind,
      paper: PaperWidth,
      drawerKick: boolean,
      // Native only: the settings picker chose a device (there is no OS chooser in-app).
      native?: { deviceId: string; label: string },
    ) => {
      if (kind === 'native' && !native) {
        throw new Error('native connect requires a picked device') // settings always passes one
      }
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
                : kind === 'native' && native
                  ? await requestNativePrinter(native.deviceId, native.label)
                  : createRawbtTransport() // no chooser — RawBT owns the printer link
        transportRef.current = transport
        const next: PrinterConfig = {
          transport: kind,
          paper,
          drawerKick,
          // Fresh localStorage read, not the state closure (connect is a stable callback).
          autoPrint: loadPrinterConfig()?.autoPrint ?? false,
          label: transport.label,
          deviceId: native?.deviceId,
        }
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

  /**
   * The in-flow recovery action (see the context doc): reattempts the SAVED connection right where
   * the operator is (typically the receipt view, mid-sale), never forcing a trip to
   * /settings/printer. BLE re-opens the chooser here specifically BECAUSE this is always invoked
   * from a user-gesture handler — see silentReattach's doc for why it can't happen on mount.
   */
  const reconnect = useCallback(async (): Promise<boolean> => {
    const saved = loadPrinterConfig()
    if (!saved) return false
    setConnectingLabel(saved.transport)
    try {
      const transport =
        saved.transport === 'ble' ? await requestBlePrinter().catch(() => null) : await silentReattach(saved)
      if (!transport) return false
      await transportRef.current?.disconnect().catch(() => {})
      transportRef.current = transport
      setConnected(true)
      return true
    } finally {
      setConnectingLabel(null)
    }
  }, [])

  const setDrawerKick = useCallback((on: boolean) => {
    setConfig((prev) => {
      if (!prev) return prev
      const next = { ...prev, drawerKick: on }
      savePrinterConfig(next)
      return next
    })
  }, [])

  const setAutoPrint = useCallback((on: boolean) => {
    setConfig((prev) => {
      if (!prev) return prev
      const next = { ...prev, autoPrint: on }
      savePrinterConfig(next)
      return next
    })
  }, [])

  const setPaper = useCallback((paper: PaperWidth) => {
    setConfig((prev) => {
      if (!prev) return prev
      const next = { ...prev, paper }
      savePrinterConfig(next)
      return next
    })
  }, [])

  const printReceipt = useCallback(
    async (data: EscposReceiptData, opts?: { cashTender?: boolean }): Promise<boolean> => {
      const cfg = loadPrinterConfig()
      if (!cfg) return false
      // `cashTender` defaults to true so a caller that hasn't been updated (e.g. the settings
      // test print) keeps today's behavior; see shouldKickDrawer's doc for the actual policy.
      const cashTender = opts?.cashTender ?? true
      const bytes = renderReceipt(data, cfg.paper, {
        drawerKick: shouldKickDrawer(cfg.drawerKick, cashTender),
      })

      const transport = transportRef.current
      if (transport) {
        try {
          await transport.write(bytes)
          return true
        } catch {
          // Device fell asleep / was unplugged mid-print — drop the stale handle and fall
          // through to the self-heal below.
          transportRef.current = null
          setConnected(false)
        }
      }

      // Self-heal, once (field-found on the Android till: cheap SPP printers drop their idle
      // Bluetooth socket between connect and the sale, which made AUTO-print fail silently —
      // by design it never pops window.print). silentReattach is deterministic for native
      // (platform bond + saved deviceId) and grant-replay for usb/serial; BLE stays manual
      // (its chooser needs a user gesture — the caller's fallback handles it).
      const revived = await silentReattach(cfg).catch(() => null)
      if (!revived) return false
      try {
        await revived.write(bytes)
        transportRef.current = revived
        setConnected(true)
        return true
      } catch {
        await revived.disconnect().catch(() => {})
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
        setAutoPrint,
        setPaper,
        printReceipt,
        reconnect,
      }}
    >
      {children}
    </PrinterContext.Provider>
  )
}
