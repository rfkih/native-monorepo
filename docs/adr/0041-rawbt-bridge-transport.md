# 41. RawBT bridge transport for Bluetooth-Classic thermal printers (Android)

Date: 2026-08-06

## Status

Accepted

Extends [ADR 0039](0039-direct-thermal-printing-escpos.md).

## Context

ADR 0039's Bluetooth transport is **Web Bluetooth, which speaks BLE GATT only** — a hard Chromium
platform limit. A large share of the cheap 58 mm thermal printers on Indonesian tills are
**Bluetooth Classic (SPP) only**: they pair fine with Android apps like RawBT, but a web page can
never open a GATT connection to them. Chrome still lists them in the device chooser, so the failure
surfaced in the field as `gatt.connect()` → `NetworkError`, which our classifier mapped to
"printer is busy" — sending the operator into a hopeless retry loop when the honest answer is
"this browser cannot reach this printer over Bluetooth, ever". A live UAT report (RawBT prints
fine, our connect says busy) confirmed both the gap and the misleading copy.

**RawBT** (`ru.a402d.rawbtprinter`, free) is the de-facto Android bridge app for exactly this
hardware. It registers the `rawbt:` URL scheme; `rawbt:base64,<bytes>` prints the decoded bytes
verbatim — i.e. it accepts our existing ESC/POS stream untouched.

## Decision

1. **Add `rawbt` as a fourth `PrinterTransport`** (`transport.ts`). It holds no device handle:
   each `write` navigates to an `intent:base64,<escpos>#Intent;scheme=rawbt;package=…;end;` URL
   (the shape RawBT's own web examples use), pinned to the RawBT package with a Play-Store
   `S.browser_fallback_url` so a till without the app lands on the install page. Support-detected
   as Android-only (intent URLs are an Android-Chrome mechanism). "Connect" is just selecting it —
   there is nothing to pair; the settings test print is the real end-to-end check. Re-attach on
   load is unconditional from the saved config.
2. **Reclassify the BLE failure**: `NetworkError` on the BLE transport is now its own reason
   (`bleUnreachable`) with copy that names both real causes — another app (RawBT's background
   service) holding the printer's single Bluetooth socket, or a Classic-only printer — and points
   at USB or the RawBT tile. `inUse` is unchanged for USB/serial.

## Consequences

- Classic-SPP printers — the common cheap clone — now print from the POS on Android, closing the
  main hardware gap ADR 0039 left open, at the cost of a third-party app dependency **on that tile
  only** (the three direct transports are unaffected).
- **Fire-and-forget**: RawBT gives no success/failure signal back to the page. `printReceipt`
  reports success once the intent is dispatched; a RawBT-side failure surfaces in RawBT's own UI.
  The drawer-kick pulse rides along in the byte stream as-is.
- Each print briefly shows Android's "opening app" affordance — inherent to the scheme, and how
  every RawBT web integration behaves.
- The receipt pipeline is untouched: same encoder, same layout, same fallback ladder
  (device → `window.print()`).
