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

## Release (production) build

```powershell
cd frontend/native-till/android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
# → app\build\outputs\apk\release\app-release.apk (SIGNED when keystore.properties exists)
```

Signing: `android/keystore.properties` (gitignored, BOM-free — PS5.1 `Set-Content` writes a BOM
that breaks Java's Properties parser) points at the keystore OUTSIDE the repo:
`C:\Users\rifki\native-till-signing\` (keystore + CREDENTIALS.txt + archived builds).
**Back that folder up** — the key is the app's permanent identity: updates must be signed with the
same key, and losing it means installed tills can only uninstall/reinstall. Without
keystore.properties, `assembleRelease` produces an unsigned APK (CI/fresh clones still compile).
Release builds drop the debug 🖨 TEST button automatically (BuildConfig.DEBUG). A release-signed
app can NOT install over a debug-signed one — uninstall first, once. Play Store later: use this
key as the upload key with Play App Signing (P2 owner decision).

## P1 hardware drill (acceptance)

> The printer tile lives in the console, so the **deployed** console (UAT origin) must include the
> P1 `'native'` transport before the tile appears in-app — the thin client renders the live origin.

1. Pair the thermal printer in **Android Settings → Bluetooth** (it must be *bonded*; the app
   deliberately has no scan permission). USB printers just plug in (OTG).
2. Sideload `app-debug.apk`, open **Native Till**, log in via Keycloak as usual.
3. Console → **Settings → Printer**: a first tile **"This device's printer"** appears (only
   in-app). Connect → pick the printer from the bonded/attached list → **Test print**.
4. Kill and reopen the app: the printer **re-attaches silently** (saved `deviceId`, no chooser).
5. Flip **Auto-print after payment** on and take a sale to cash: the receipt prints with **no
   popup, no app switch** — the RawBT-era caveats are gone.
6. Repeat step 3 for each hardware kind you have (Classic SPP / BLE / USB) — one build covers all
   three.

Fallback sanity check without any console deploy: the translucent **🖨 TEST** button
(bottom-right, debug builds only) still prints fixed bytes straight over SPP.

## Versioning contract (D7)

`versionCode`/`versionName` bump **only when native code changes** (plugin, kiosk, WebView host,
manifest). Web features ship via the normal console deploy and appear in the app with no update.
