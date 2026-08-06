# 39. Direct thermal receipt printing (ESC/POS over WebUSB / Web Bluetooth / WebSerial)

Date: 2026-08-06

## Status

Accepted

## Context

The POS printed receipts only through `window.print()` — the browser's OS print dialog rendering the
WYSIWYG paper. That works for a full-size office printer but is wrong for the hardware SMBs actually
run a till on: 58 mm / 80 mm **thermal receipt printers** (Epson TM-class and the ubiquitous
Indonesian clones — Xprinter, EPPOS, Goojprt), which expect **ESC/POS** command bytes, not a
rasterized page. Through the browser dialog these printers either need a vendor driver, mis-scale the
80 mm roll, and always interrupt the cashier with a modal — unacceptable for a one-tap checkout.

The console is a PWA served over HTTPS (the UAT funnel and prod both qualify). Modern Chromium
(desktop + Android) exposes three capabilities that let a web page talk to a printer directly:
**WebUSB**, **Web Bluetooth** (BLE), and **WebSerial**. All are HTTPS-gated and require one
user-gesture pairing, after which writes are silent.

## Decision

Print receipts as **raw ESC/POS bytes sent straight from the browser to the device**, with the
existing `window.print()` kept as the automatic fallback.

### Encoder + layout (`src/lib/escpos/`)

- `encoder.ts` — a hand-rolled ESC/POS command builder (init, align, bold, size, feed, cut,
  cash-drawer kick, raw text). ~15 commands, the subset a receipt needs; a driver dependency would
  bring a full printer-control surface to audit. **ASCII-only wire**: text is transliterated
  (typographic dashes/quotes/×, which our `Intl` formatters and copy emit) then `?`-substituted,
  sidestepping the notoriously unreliable codepage selection on clone firmware.
- `receipt.ts` — monospace column layout (32 cols @ 58 mm, 48 @ 80 mm) rendering the **same
  normalized receipt data model** the on-screen `ThermalReceipt` already uses. One source of truth
  for receipt content; this only does column math + the ESC/POS emphasis for the header/total.

### Transports (`transport.ts`)

One `PrinterTransport` interface, three implementations — WebUSB (printer interface class 0x07,
grant persists via `getDevices()`), Web Bluetooth (BLE GATT print services; must re-pair each
session per spec), WebSerial (grant persists via `getPorts()`). Capability-detected; a browser
missing all three simply never leaves the `window.print()` path.

### Wiring

- `PrinterProvider` (app-root context) holds at most one live transport for the session and silently
  re-attaches a previously-granted USB/serial printer on load. `printReceipt` returns a boolean —
  `false` (no device, or a mid-print unplug) makes the caller fall back to `window.print()`, so a
  device failure never loses the receipt.
- `ThermalReceipt`'s Print button (every receipt surface already routes through it) tries the device
  first, then the browser dialog — no per-caller change.
- A **Printer settings** page (`/settings/printer`, not page-gated — whoever sets up a till connects
  it) pairs a device, picks paper width, and prints a test receipt.

### Scope / non-goals

- Receipt content is unchanged; this is a transport, not a redesign.
- The cash-drawer kick pulse is emitted when the device toggle is on (RJ11 pin-2, the near-universal
  wiring). Barcode/QR **printer-native** rendering is deferred (the on-screen receipt's decorative
  barcode is not reproduced as scannable output yet).
- Kitchen tickets (KOT) still print via `window.print()`; routing them to a second station printer
  is a follow-up (the transport layer already supports more than one device conceptually, but the
  provider holds one).

## Consequences

- **No drivers, no local agent, no app install** — a cashier on an Android tablet pairs a USB-OTG or
  Bluetooth printer once and prints silently thereafter, over the same HTTPS origin the PWA already
  uses.
- **Graceful degradation** — Safari/Firefox (no WebUSB/BLE/Serial) and un-paired tills keep the
  exact previous browser-print behavior. No regression for anyone.
- **Browser requirement** — silent thermal printing needs Chrome/Edge (Chromium). Documented in the
  settings UI.
- **Clone variance** — the ASCII-only wire + core-command subset maximizes compatibility, but a
  specific clone may ignore the cutter (no-op, harmless) or need a different drawer pin; both are
  device-level, not code changes.
- New surface is unit-tested at the byte level (`__tests__/receipt.test.ts`) — init/cut/kick
  sequences, ASCII safety, column fit, right-alignment — and the transports are thin wrappers over
  browser APIs exercised manually against real hardware.
