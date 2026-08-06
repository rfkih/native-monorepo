/**
 * Printer transports (ADR 0039): three Chromium capabilities carry the same ESC/POS bytes.
 *
 *  - WebUSB    — USB thermal printers (interface class 0x07). Desktop Chrome + Android Chrome
 *                (OTG tablets — the common Indonesian till). Permission survives reloads via
 *                navigator.usb.getDevices().
 *  - Bluetooth — BLE printers (the 58 mm battery clip printers). Web Bluetooth speaks BLE GATT
 *                only — CLASSIC-SPP-only printers cannot pair here (the settings UI says so).
 *  - Serial    — RS-232/virtual-COM printers on desktops via WebSerial.
 *
 * All are HTTPS-gated (the UAT funnel and prod both qualify) and need one user-gesture pairing;
 * after that printing is silent — no print dialog. Browsers without these APIs keep the
 * window.print() fallback path.
 *
 * The ambient declarations below are the narrow structural slices of the WebUSB/WebBluetooth/
 * WebSerial specs we call — typed locally instead of pulling three @types packages for a page of
 * API surface.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

export type TransportKind = 'usb' | 'ble' | 'serial'

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

/** User-gesture pairing: shows the browser device picker filtered to printer-class devices. */
export async function requestUsbPrinter(): Promise<PrinterTransport> {
  const usb = (navigator as any).usb
  const device = await usb.requestDevice({
    filters: [{ classCode: USB_PRINTER_CLASS }],
  })
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
  const device = await bluetooth.requestDevice({
    filters: BLE_PRINT_SERVICES.map((s) => ({ services: [s] })),
    optionalServices: BLE_PRINT_SERVICES,
  })
  return openBleDevice(device)
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
