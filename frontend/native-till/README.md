# Native Till — Android shell (ADR 0043)

A thin **Capacitor** Android app that renders the **live console origin** in a WebView and adds the
one capability the web can never have: an in-process **NativePrint** bridge that reaches
**Bluetooth-Classic (SPP)** thermal printers (plus BLE/USB from P1). No POS logic lives here —
ESC/POS bytes are produced by the unchanged web layer (`frontend/console/src/lib/escpos/`); the
Kotlin side is a dumb byte pipe.

**This build is NOT part of the root Gradle build.** It has its own Android toolchain (AGP 8.13,
JDK 21, Gradle 8.14 wrapper) and must never be wired into `settings.gradle.kts` / `build-logic/`.

## Build (Windows, this repo's dev machine)

Prereqs: Node ≥ 20, Android SDK (Android Studio default install), JDK 21
(Android Studio's bundled `jbr` works — do **not** use the backend's JDK 25).

```powershell
cd frontend/native-till
npm ci
npx cap sync android            # regenerates gitignored android assets/plugin wiring
cd android
# android/local.properties must point at your SDK (machine-local, gitignored):
#   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
# → app\build\outputs\apk\debug\app-debug.apk
```

The WebView origin defaults to the UAT console; override per build with
`NATIVE_TILL_URL=https://... npx cap sync android`.

## P0 hardware drill (acceptance)

1. Pair the thermal printer in **Android Settings → Bluetooth** (it must be *bonded*; the app
   deliberately has no scan permission).
2. Sideload `app-debug.apk` (enable "install unknown apps"), open **Native Till** — the UAT console
   loads; log in via Keycloak as usual (same-origin `/auth/callback` round-trips in the WebView).
3. Tap the translucent **🖨 TEST** button (bottom-right, debug builds only) → grant the Bluetooth
   permission when asked → pick the printer from the bonded list.
4. A "P0 NATIVE BRIDGE TEST" slip prints → the ADR 0041 Bluetooth-Classic gap is closed.

## Versioning contract (D7)

`versionCode`/`versionName` bump **only when native code changes** (plugin, kiosk, WebView host,
manifest). Web features ship via the normal console deploy and appear in the app with no update.
