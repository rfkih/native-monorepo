/**
 * Printer transports (ADR 0039, RawBT bridge ADR 0041): four ways to carry the same ESC/POS bytes.
 *
 *  - WebUSB    — USB thermal printers (interface class 0x07). Desktop Chrome + Android Chrome
 *                (OTG tablets — the common Indonesian till). Permission survives reloads via
 *                navigator.usb.getDevices().
 *  - Bluetooth — BLE printers (the 58 mm battery clip printers). Web Bluetooth speaks BLE GATT
 *                only — CLASSIC-SPP-only printers cannot pair here (the settings UI says so).
 *  - Serial    — RS-232/virtual-COM printers on desktops via WebSerial.
 *  - RawBT     — Android only: hands the bytes to the RawBT app via its `rawbt:` intent URL,
 *                and RawBT drives the printer over Bluetooth CLASSIC (SPP) — the transport for
 *                the many cheap printers Chrome's BLE-only Web Bluetooth can never reach.
 *
 * The browser APIs are HTTPS-gated (the UAT funnel and prod both qualify) and need one
 * user-gesture pairing; after that printing is silent — no print dialog. Browsers without these
 * APIs keep the window.print() fallback path.
 *
 * The ambient declarations below are the narrow structural slices of the WebUSB/WebBluetooth/
 * WebSerial specs we call — typed locally instead of pulling three @types packages for a page of
 * API surface.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

export type TransportKind = 'usb' | 'ble' | 'serial' | 'rawbt'

export interface PrinterTransport {
  readonly kind: TransportKind
  /** Human-readable device label for the settings UI. */
  readonly label: string
  write(bytes: Uint8Array): Promise<void>
  disconnect(): Promise<void>
}

export function transportSupport(): Record<TransportKind, boolean> {
  const nav = navigator as any
  return {
    usb: !!nav.usb,
    ble: !!nav.bluetooth,
    serial: !!nav.serial,
    // intent: URLs are an Android-Chrome mechanism — RawBT itself is an Android app.
    rawbt: /android/i.test(nav.userAgent ?? ''),
  }
}

// ---------------------------------------------------------------------------
// WebUSB
// ---------------------------------------------------------------------------

const USB_PRINTER_CLASS = 7

async function openUsbDevice(device: any): Promise<PrinterTransport> {
  await device.open()
  if (device.configuration == null) await device.selectConfiguration(1)

  // Find the printer interface + its bulk OUT endpoint.
  let ifaceNumber = -1
  let endpointNumber = -1
  for (const iface of device.configuration.interfaces) {
    for (const alt of iface.alternates) {
      const out = alt.endpoints.find((ep: any) => ep.direction === 'out' && ep.type === 'bulk')
      if (out && (alt.interfaceClass === USB_PRINTER_CLASS || ifaceNumber === -1)) {
        ifaceNumber = iface.interfaceNumber
        endpointNumber = out.endpointNumber
        if (alt.interfaceClass === USB_PRINTER_CLASS) break
      }
    }
  }
  if (ifaceNumber === -1) throw new Error('no bulk OUT endpoint on device')
  await device.claimInterface(ifaceNumber)

  return {
    kind: 'usb',
    label: `${device.manufacturerName ?? 'USB'} ${device.productName ?? ''}`.trim(),
    async write(bytes: Uint8Array) {
      // 4 KB chunks — comfortably below every clone's bulk buffer.
      for (let i = 0; i < bytes.length; i += 4096) {
        await device.transferOut(endpointNumber, bytes.slice(i, i + 4096))
      }
    },
    async disconnect() {
      try {
        await device.close()
      } catch {
        /* already gone */
      }
    },
  }
}

/**
 * User-gesture pairing: shows the browser device picker. We DON'T filter by printer class —
 * cheap thermal clones commonly declare class 0x07 only on the interface descriptor (device class
 * 0), so a class filter hides them and the chooser comes up empty ("nothing to connect to"). We
 * show all USB devices and find the printer's bulk endpoint after the user picks (openUsbDevice).
 */
export async function requestUsbPrinter(): Promise<PrinterTransport> {
  const usb = (navigator as any).usb
  const device = await usb.requestDevice({ filters: [] })
  return openUsbDevice(device)
}

/** Silent re-attach of an already-granted USB printer (page load / reconnect). */
export async function reattachUsbPrinter(): Promise<PrinterTransport | null> {
  const usb = (navigator as any).usb
  if (!usb) return null
  const devices = await usb.getDevices()
  if (devices.length === 0) return null
  try {
    return await openUsbDevice(devices[0])
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// Web Bluetooth (BLE)
// ---------------------------------------------------------------------------

/** The serial-over-GATT services the BLE receipt-printer boards actually ship. */
const BLE_PRINT_SERVICES = [
  0x18f0, // common Chinese printer boards ("ISSC/JieLi" print service)
  '49535343-fe7d-4ae5-8fa9-9fafd205e455', // ISSC transparent UART
  '000018f0-0000-1000-8000-00805f9b34fb',
  '0000ffe0-0000-1000-8000-00805f9b34fb', // HM-10 style UART
]

async function openBleDevice(device: any): Promise<PrinterTransport> {
  const server = await device.gatt.connect()
  let characteristic: any = null
  for (const service of await server.getPrimaryServices()) {
    for (const ch of await service.getCharacteristics()) {
      if (ch.properties.writeWithoutResponse || ch.properties.write) {
        characteristic = ch
        break
      }
    }
    if (characteristic) break
  }
  if (!characteristic) {
    device.gatt.disconnect()
    throw new Error('no writable characteristic')
  }
  const useNoResponse = characteristic.properties.writeWithoutResponse

  return {
    kind: 'ble',
    label: device.name ?? 'Bluetooth printer',
    async write(bytes: Uint8Array) {
      // Small chunks + a breather: BLE print boards overrun silently on long receipts otherwise.
      for (let i = 0; i < bytes.length; i += 100) {
        const chunk = bytes.slice(i, i + 100)
        if (useNoResponse) {
          await characteristic.writeValueWithoutResponse(chunk)
          await new Promise((r) => setTimeout(r, 12))
        } else {
          await characteristic.writeValueWithResponse(chunk)
        }
      }
    },
    async disconnect() {
      try {
        device.gatt.disconnect()
      } catch {
        /* already gone */
      }
    },
  }
}

export async function requestBlePrinter(): Promise<PrinterTransport> {
  const bluetooth = (navigator as any).bluetooth
  // acceptAllDevices, NOT a services filter: most BLE receipt printers advertise only a name, not
  // their print-service UUID, so a filter leaves the chooser empty. We list every BLE device, let
  // the operator pick the printer, then hunt for a writable characteristic across the optional
  // services (openBleDevice) — which must still be declared so we're allowed to access them.
  const device = await bluetooth.requestDevice({
    acceptAllDevices: true,
    optionalServices: BLE_PRINT_SERVICES,
  })
  return openBleDevice(device)
}

/**
 * Classifies a pairing/connect failure into a stable reason code the UI maps to copy. The browser
 * throws DOMExceptions with well-known names; a user cancelling the chooser is NOT an error worth
 * shouting about (it's `NotFoundError`/`NotAllowedError` depending on the API).
 *
 * BLE gets its own `NetworkError` bucket: `gatt.connect()` failing usually means the printer is
 * either held by another app (RawBT's background service keeps the one Bluetooth socket these
 * boards have) or is Bluetooth CLASSIC-only — which Web Bluetooth can NEVER reach. Calling that
 * "busy" sends the operator into a retry loop; the copy must point at USB or the RawBT bridge.
 */
export type ConnectFailureReason =
  | 'cancelled'
  | 'blocked'
  | 'inUse'
  | 'bleUnreachable'
  | 'noEndpoint'
  | 'unknown'

export function classifyConnectError(err: unknown, kind?: TransportKind): ConnectFailureReason {
  const name = err instanceof Error ? err.name : ''
  const message = err instanceof Error ? err.message : String(err)
  if (name === 'NotFoundError') return 'cancelled' // chooser dismissed / no device picked
  if (name === 'SecurityError' || name === 'NotAllowedError') return 'blocked'
  if (name === 'NetworkError' && kind === 'ble') return 'bleUnreachable'
  if (name === 'InvalidStateError' || name === 'NetworkError') return 'inUse'
  if (/no bulk OUT endpoint|no writable characteristic/i.test(message)) return 'noEndpoint'
  return 'unknown'
}

// ---------------------------------------------------------------------------
// WebSerial
// ---------------------------------------------------------------------------

async function openSerialPort(port: any): Promise<PrinterTransport> {
  await port.open({ baudRate: 9600 })
  return {
    kind: 'serial',
    label: 'Serial printer',
    async write(bytes: Uint8Array) {
      const writer = port.writable.getWriter()
      try {
        await writer.write(bytes)
      } finally {
        writer.releaseLock()
      }
    },
    async disconnect() {
      try {
        await port.close()
      } catch {
        /* already gone */
      }
    },
  }
}

export async function requestSerialPrinter(): Promise<PrinterTransport> {
  const serial = (navigator as any).serial
  const port = await serial.requestPort()
  return openSerialPort(port)
}

export async function reattachSerialPrinter(): Promise<PrinterTransport | null> {
  const serial = (navigator as any).serial
  if (!serial) return null
  const ports = await serial.getPorts()
  if (ports.length === 0) return null
  try {
    return await openSerialPort(ports[0])
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// RawBT bridge (ADR 0041)
// ---------------------------------------------------------------------------

const RAWBT_PACKAGE = 'ru.a402d.rawbtprinter'
const RAWBT_PLAY_URL = `https://play.google.com/store/apps/details?id=${RAWBT_PACKAGE}`

/**
 * The Android intent URL RawBT registers for: `rawbt:base64,<bytes>` prints the decoded bytes
 * verbatim (our ESC/POS stream, drawer kick and all). The `intent:` wrapper pins the package and
 * falls back to the Play Store listing when RawBT isn't installed — the standard Android-Chrome
 * pattern, and the shape RawBT's own web examples use (encodeURI, which leaves base64 intact).
 */
export function rawbtIntentUrl(bytes: Uint8Array): string {
  let bin = ''
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return (
    'intent:' +
    encodeURI(`base64,${btoa(bin)}`) +
    `#Intent;scheme=rawbt;package=${RAWBT_PACKAGE};` +
    `S.browser_fallback_url=${encodeURIComponent(RAWBT_PLAY_URL)};end;`
  )
}

/**
 * The silent path: the companion "Server for RawBT" app (same developer, package `rawbt.server`)
 * keeps RawBT running in the background and exposes its print queue on a local WebSocket —
 * ws://127.0.0.1:40213/, raw ESC/POS bytes as one binary message then close(1000), per 402d's own
 * reference client. Reachable from our HTTPS page (localhost is exempt from mixed-content in
 * Chrome) and needs NO user activation — so it prints with no app switch, no RawBT popup, and no
 * transient-activation window for auto-print. Resolves false (never throws) when the server app
 * isn't installed/running: a refused localhost connect errors in milliseconds; the timeout only
 * guards a hung connect.
 */
const RAWBT_WS_URL = 'ws://127.0.0.1:40213/'

export function sendViaRawbtWs(bytes: Uint8Array, timeoutMs = 600): Promise<boolean> {
  return new Promise((resolve) => {
    if (typeof WebSocket === 'undefined') {
      resolve(false)
      return
    }
    let ws: WebSocket
    try {
      ws = new WebSocket(RAWBT_WS_URL)
    } catch {
      resolve(false)
      return
    }
    let settled = false
    const done = (ok: boolean) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try {
        ws.close(1000)
      } catch {
        /* already closed */
      }
      resolve(ok)
    }
    const timer = setTimeout(() => done(false), timeoutMs)
    ws.binaryType = 'arraybuffer'
    ws.onopen = () => {
      // A connect completing after the timeout already fell back to the intent URL — sending here
      // too would print the receipt twice. (Browsers abort a CONNECTING socket on close(), but
      // don't lean on that.)
      if (settled) return
      try {
        // .slice() detaches from any larger backing buffer and satisfies send()'s ArrayBuffer type.
        ws.send(bytes.slice().buffer)
        done(true) // close(1000) flushes the queued message before the closing handshake
      } catch {
        done(false)
      }
    }
    ws.onerror = () => done(false)
  })
}

/**
 * Not a device connection at all: each write hands the bytes to the RawBT app, which owns the
 * actual (Bluetooth Classic / whatever it's configured for) printer link. Silent WebSocket path
 * first (see above); otherwise NAVIGATE to a rawbt: intent URL — Android foregrounds RawBT
 * briefly (the "popup"), and the page stays loaded. Fire-and-forget either way — RawBT gives no
 * per-job success signal back. There is nothing to pair, so "connect" is just selecting it; the
 * settings test print is the real end-to-end check.
 */
export function createRawbtTransport(): PrinterTransport {
  return {
    kind: 'rawbt',
    label: 'RawBT',
    async write(bytes: Uint8Array) {
      if (await sendViaRawbtWs(bytes)) return
      window.location.href = rawbtIntentUrl(bytes)
    },
    async disconnect() {
      /* nothing held */
    },
  }
}
