# Native Employee — Android shell (ADR 0049 P5)

A thin **Capacitor** Android app that renders the **live console origin**'s role-gated `/me`
employee self-service surface (payslips, time-off, claims, PII profile, own sales/commission) in
a WebView. Every employee signs in with their **personal Keycloak login** — this is a separate,
second installable app from the **Business/Till app** (`frontend/native-till`, ADR 0043), not a
mode inside it. No native bridge lives here: unlike the Till app's in-process ESC/POS
`NativePrint` bridge (bonded Bluetooth/BLE/USB thermal printers), the Employee app is pure
self-service with nothing to print, so it registers no Capacitor plugins.

**This build is NOT part of the root Gradle build.** It has its own Android toolchain (AGP 8.13,
JDK 21, Gradle 8.14 wrapper) and must never be wired into `settings.gradle.kts` / `build-logic/`
— exactly the same posture as `frontend/native-till`.

## Environments: UAT vs Prod are separate apps (ADR 0058)

Two Gradle product flavors (`env` dimension), mirroring the Till app → two **distinct installable
apps** so a device can hold both:

| Flavor | applicationId | Launcher name | Icon | Origin |
|---|---|---|---|---|
| `prod` | `id.co.nativeapp.employee` (canonical, permanent) | **Native Karyawan** | brand (cyan) | the stable prod origin |
| `uat` | `id.co.nativeapp.employee.uat` | **Native Karyawan UAT** | amber, badged | UAT |

The flavor sets only identity/name/icon; the WebView **origin** is baked at `cap sync` time from
`NATIVE_EMPLOYEE_URL`. Use the wrapper so the two stay paired:

```powershell
npm run build:uat                                          # → dist/native-employee-app-uat-v<code>.apk
$env:NATIVE_EMPLOYEE_URL="https://<prod-origin>"; npm run build:prod
```

`build:prod` **refuses** an ephemeral `*.trycloudflare.com` origin — build prod only once a named
tunnel / domain gives a **stable** origin. Gradle tasks are flavor-qualified
(`assembleUatRelease` / `assembleProdRelease`); output paths gain a flavor segment.

## Build (Windows, this repo's dev machine)

Prereqs: Node ≥ 20, Android SDK (Android Studio default install), JDK 21
(Android Studio's bundled `jbr` works — do **not** use the backend's JDK 25).

```powershell
cd frontend/native-employee
npm ci
npx cap sync android            # regenerates gitignored android assets/plugin wiring
cd android
# android/local.properties must point at your SDK (machine-local, gitignored):
#   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleUatDebug
# → app\build\outputs\apk\uat\debug\app-uat-debug.apk   (or just: npm run apk)
```

The WebView origin defaults to the Employee UAT funnel (see `capacitor.config.ts`); override per
build with `NATIVE_EMPLOYEE_URL=https://... npx cap sync android` (or let `npm run build:uat|build:prod`
set it — see **Environments** above).

## Release (production) build

**First, generate this app's OWN keystore — do NOT reuse the Till app's key.** A developer runs
this once, before the first release build (adjust the path/alias/validity to taste; keep the
`storeFile` path in the eventual `keystore.properties` in sync):

```powershell
keytool -genkeypair -v `
  -keystore C:\Users\<you>\native-employee-signing\native-employee-release.jks `
  -alias native-employee `
  -keyalg RSA -keysize 2048 -validity 10000
```

Then, mirroring `native-till`'s signing wiring, create `android/keystore.properties` (gitignored,
**BOM-free** — PS5.1 `Set-Content` writes a BOM that breaks Java's Properties parser) pointing at
that keystore:

```properties
storeFile=C:\\Users\\<you>\\native-employee-signing\\native-employee-release.jks
storePassword=<store password>
keyAlias=native-employee
keyPassword=<key password>
```

then build (prefer the wrapper `npm run build:prod` / `build:uat` — it pairs origin with flavor):

```powershell
cd frontend/native-employee/android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleProdRelease   # or assembleUatRelease
# → app\build\outputs\apk\prod\release\app-prod-release.apk (SIGNED when keystore.properties exists)
```

Signing: the keystore lives OUTSIDE the repo, e.g. `C:\Users\rifki\native-employee-signing\`
(keystore + CREDENTIALS.txt + archived builds), mirroring `native-till`'s convention.
**Back that folder up** — the key is the app's permanent identity: updates must be signed with the
same key, and losing it means installed devices can only uninstall/reinstall. Without
`keystore.properties`, `assembleRelease` produces an unsigned APK (CI/fresh clones still compile).
A release-signed app can NOT install over a debug-signed one — uninstall first, once. Play Store
later: use this key as the upload key with Play App Signing (deferred, per ADR 0049 P5 scope note
— "Play-Store distribution of the Employee app" is out of scope for now).

Publish to UAT: copy the signed APK to `docker/uat/downloads/native-employee-app-v<versionCode>.apk`
(served by the edge at `https://<uat-origin>/native-employee-app-v<versionCode>.apk`; see
`docker/uat/downloads/README.md`). Do NOT `docker cp` it into the console container — that
copy dies on the next recreate.

## What was intentionally dropped vs. native-till

This shell is a copy-and-rebrand of `frontend/native-till`, with the printer-specific surface
removed rather than carried over unused:

- **Bluetooth permissions** (`BLUETOOTH_CONNECT`, legacy `BLUETOOTH`) and the
  `android.hardware.bluetooth` feature declaration — dropped. `INTERNET` is the only permission
  this app declares.
- **The `NativePrint` bridge** (`NativePrintPlugin.kt`, `BlePrinterConnection.kt`,
  `PrinterConnections.kt`, `SppTestPrinter.kt`) and the debug 🖨 TEST button in `MainActivity` —
  not copied at all. The Employee app never prints, and the bridge was cleanly separable (it lives
  entirely in those four files plus two isolated call sites in `MainActivity`), so keeping a
  Bluetooth-capable printer bridge as dead code in a personal self-service app would only raise
  unnecessary permission-review questions with no upside. `MainActivity` here is the plain
  WebView-shell essentials only: text-zoom normalization + in-app back navigation.

## Icons / splash

Reuses the Till app's icon and splash assets **as-is** (same brand mark/gradient) so the project
is complete today. The **UAT flavor** now overrides this with a badged amber launcher icon + a
"Native Karyawan UAT" name (ADR 0058) so UAT and prod are distinguishable. **Still a follow-up:**
commission Employee-specific art to visually distinguish it from the Business/Till app (a separate
concern from the UAT/prod badge) on a device that has both installed.

## Versioning contract

Fresh lineage: `versionCode 1`, `versionName "1.0"` — this is a new app, not a continuation of
native-till's (which is at 6/1.4). Same contract as native-till going forward: `versionCode`/
`versionName` bump **only when native code changes** (WebView host, manifest, future plugins). Web
features (the `/me` surface itself) ship via the normal console deploy and appear in the app with
no update.
