# 43. Native Android till app — Capacitor thin-client + in-process NativePrint bridge

Date: 2026-08-07

## Status

Proposed

Extends [ADR 0039](0039-direct-thermal-printing-escpos.md) and [ADR 0041](0041-rawbt-bridge-transport.md);
reuses [ADR 0028](0028-offline-mode-cash-only-queue.md) unchanged.

## Context

The dominant thermal printers on Indonesian tills are **Bluetooth Classic (SPP) only**. Web
Bluetooth is BLE-GATT-only by Chromium design, so the browser POS can *never* reach them directly
(ADR 0041's root cause). The RawBT bridge closes the gap but costs either a per-print popup
(intent URL) or a second companion app ("Server for RawBT"), plus a transient-activation caveat on
auto-print. Every at-scale POS competitor ships a native Android app for exactly this reason.

Constraints that shape the solution: the web console's feature velocity and deploy cadence must be
untouched; no new backend, event, or service boundary (the app is just another gateway client over
the same Keycloak JWT); ESC/POS encoding must stay in the web layer (single source of receipt
bytes, ADR 0039).

## Decision

1. **Ship a thin native Android shell with Capacitor** (`frontend/native-till/`), in `server.url`
   mode: the WebView renders the **live deployed console origin**. All POS features keep shipping
   via the normal web deploy with **zero app updates**. The PWA service worker + IndexedDB offline
   queue (ADR 0028) work unchanged inside the WebView (Android System WebView is Chromium).
   - *TWA rejected*: cannot inject a JS bridge (the entire point) and stays BLE-only.
   - *Plain Kotlin WebView rejected*: re-invents plugin marshaling, permissions, and packaging that
     Capacitor maintains.
   - *Bundled web assets rejected as default*: re-introduces an app-release cadence per web feature;
     kept as a documented fallback if Play review or an offline-first customer ever demands it.
2. **The app's one native capability is `window.NativePrint`** — a Capacitor plugin that is a
   **dumb byte pipe** to thermal printers over **Bluetooth Classic (SPP), BLE, and USB** (WebView
   has no WebUSB/Web Bluetooth, so the bridge is the only in-app print path — which also satisfies
   Play's "minimum functionality" bar, together with kiosk/keep-awake). ESC/POS bytes are produced
   by the unchanged web-side `encoder.ts`/`receipt.ts` and cross the bridge base64-encoded; the
   Kotlin side never interprets them.
3. **The web layer consumes the bridge as a 5th `TransportKind` `'native'`** in
   `frontend/console/src/lib/escpos/transport.ts`, feature-detected via `!!window.NativePrint` —
   inert in every browser. Bridge surface: `apiVersion`, `listDevices()`, `connect(deviceId)`,
   `write(base64)`, `disconnect()`; reject codes map onto the existing `ConnectFailureReason` set
   (`cancelled` / `blocked` / `inUse` / `noEndpoint` / `unknown`). `bleUnreachable` is unreachable
   on this path — the native transport is precisely what closes it. `printerStore` gains an
   optional `deviceId` so re-attach is deterministic (the platform bond is the pairing state).
4. **Auth is unchanged**: the WebView loads the real console origin, so the existing Keycloak
   authorization-code + PKCE flow and same-origin `/auth/callback` work with zero Keycloak config
   change. (Watch-item: if federated social login is ever added, that button must open a Custom
   Tab — embedded WebViews are disallowed by Google's IdP policy. Not used today.)
5. **Repo + toolchain isolation**: `frontend/native-till/` is a monorepo sibling (like
   `frontend/self-order/`) with its own Android/Gradle build. It is **never** wired into the root
   `settings.gradle.kts`, `build-logic/`, or `./gradlew build` — the Android toolchain (AGP, JDK 21)
   is separate from the Java 25 backend build. CI gets a separate Android job at P2.
6. **Distribution & versioning contract**: sideload/MDM for UAT (P1), Google Play for GA (P2).
   The shell's `versionCode` bumps **only on native changes** (plugin, kiosk, WebView host,
   manifest). A web feature must never wait on an app release.

## Consequences

- Silent, popup-free printing to Classic/BLE/USB printers from one installed app; the RawBT ladder
  and its transient-activation caveat become unnecessary on the app path (both remain for the
  plain-browser PWA path — ADR 0039/0041 stay in force there).
- The bridge can report real per-write failures (unlike RawBT's fire-and-forget), so `printReceipt`
  can honestly fall back on device failure.
- We take on an Android toolchain, a signing keystore, Play review (P2), and a hardware drill
  matrix (one SPP clone, one BLE printer, one USB/OTG printer) as release gates for shell changes.
- Android 12+ runtime `BLUETOOTH_CONNECT` is required before any device access; the permission set
  stays minimal (no `BLUETOOTH_SCAN` while only bonded devices are listed; camera deferred to the
  barcode phase).
- No event, schema, or backend change: `docs/EVENT-CATALOG.md` is untouched by design.

## Plan

Phases (full plan reviewed 2026-08-06): **P0** spike — shell renders the UAT console + Kotlin
module test-prints fixed bytes to a bonded SPP printer (this ADR authored `Proposed`). **P1** —
bridge wired into the console (`'native'` transport, device picker, silent auto-print), sideload.
**P2** — Play internal track, lock-task kiosk, Android CI job (flip this ADR to `Accepted` no later
than GA). **P3** — cash drawer / barcode / customer display.

## Amendment (2026-08-07): P1 — the exact bridge contract as shipped

- **Call convention**: the bridge is the Capacitor plugin proxy (`Capacitor.Plugins.NativePrint`,
  injected by the app's runtime; the console also honors a direct `window.NativePrint` for tests).
  Methods take a single options object: `getInfo() → {apiVersion}`, `listDevices() → {devices}`,
  `connect({deviceId})`, `write({base64})`, `disconnect()`. Rejections carry a `code` from the
  existing `ConnectFailureReason` set; `classifyConnectError` trusts it verbatim on the native path.
- **All three links shipped**: Classic **SPP** (RFCOMM, bounded 10 s connect), **BLE** (GATT —
  same print-service preference order as the web BLE transport, per-chunk write acknowledgement,
  MTU negotiation), **USB host** (bulk-OUT, printer-class interface preferred, per-device system
  consent dialog mapped to `cancelled` on decline). One live connection at a time — mirroring the
  web `usePrinter` model. A failed write closes the link and rejects, so the console's fall-back-
  to-window.print() and re-attach semantics hold unchanged.
- **Device identity**: Bluetooth = MAC, USB = `usb:vid:pid`. The console persists it as
  `PrinterConfig.deviceId` and re-attaches silently on load — deterministic because the platform
  bond owns the pairing (no per-session chooser, unlike Web Bluetooth).
- **Shell branding**: adaptive icon + splash derive from the console's one brand glyph
  (`Wordmark.tsx` trend line) on the brand-500→800 gradient; status bar matches the console paper
  background. Keep-screen-on active (D6 P1). `versionCode 2`.

## Amendment (2026-08-07, later): the service worker must not run inside the shell

Field-found on the first phone install, **correcting D3's "offline stack works unchanged" claim**:
Capacitor injects `window.Capacitor` (and the NativePrint proxy) by intercepting the *document*
request at the WebView network layer — a document served by the console's workbox service worker
never reaches that interceptor, so after the SW's first activation every launch loses the bridge
(printer settings degrade to dead browser tiles). Fix (`979dbe61`): SW registration is hand-rolled;
inside the shell — detected via `window.androidBridge`, which Capacitor exposes to every WebView
page regardless of how it was served — the SW never registers, existing registrations are torn
down, and a bridge-less SW-served page reloads once. Consequences: **in-app offline cold-start is
traded away** (the IndexedDB offline queue is unaffected; the D3 bundled-assets fallback becomes
the committed path if offline cold-start is ever required); in-shell printer settings show only the
native tile (the four web transports, RawBT included, are dead weight there). Browsers keep the SW
and all four tiles exactly as before.
