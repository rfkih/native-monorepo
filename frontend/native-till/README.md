# Native Till — Android shell (ADR 0043)

A thin **Capacitor** Android app that renders the **live console origin** in a WebView and adds the
one capability the web can never have: an in-process **NativePrint** bridge that reaches
**Bluetooth-Classic (SPP)** thermal printers (plus BLE/USB from P1). No POS logic lives here —
ESC/POS bytes are produced by the unchanged web layer (`frontend/console/src/lib/escpos/`); the
Kotlin side is a dumb byte pipe.

**This build is NOT part of the root Gradle build.** It has its own Android toolchain (AGP 8.13,
JDK 21, Gradle 8.14 wrapper) and must never be wired into `settings.gradle.kts` / `build-logic/`.

## Environments: UAT vs Prod are separate apps (ADR 0058)

Two Gradle product flavors (`env` dimension) → two **distinct installable apps** so a device can hold
both and never confuse them:

| Flavor | applicationId | Launcher name | Icon | Origin |
|---|---|---|---|---|
| `prod` | `id.co.nativeapp.till` (canonical, permanent) | **Native** | brand (cyan) | the stable prod origin |
| `uat` | `id.co.nativeapp.till.uat` | **Native UAT** | amber, badged | UAT |

The flavor sets only the Android identity/name/icon; the WebView **origin** is still baked at
`cap sync` time from `NATIVE_TILL_URL`. Use the wrapper so the two stay paired — **never** build the
`uat` flavor while synced to the prod URL (or vice-versa):

```powershell
npm run build:uat                                     # → dist/native-app-uat-v<code>.apk (UAT origin)
$env:NATIVE_TILL_URL="https://<prod-domain>"; npm run build:prod   # → dist/native-app-prod-v<code>.apk
```

`build:prod` **refuses** an ephemeral `*.trycloudflare.com` origin (it would break on the next prod
restart) — build prod only once a named tunnel / domain gives a **stable** origin. Gradle tasks are
now flavor-qualified: `assembleUatRelease` / `assembleProdRelease` (plain `assembleRelease` builds
both); output paths gain a flavor segment (`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`).

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
.\gradlew.bat assembleUatDebug
# → app\build\outputs\apk\uat\debug\app-uat-debug.apk   (or just: npm run apk)
```

The WebView origin defaults to the UAT console; override per build with
`NATIVE_TILL_URL=https://... npx cap sync android` (or let `npm run build:uat|build:prod` set it —
see **Environments** above).

## Release (production) build

Prefer the wrapper (`npm run build:prod` / `build:uat`, see **Environments** above) — it pairs origin
with flavor and names the output. To drive Gradle directly:

```powershell
cd frontend/native-till/android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleProdRelease   # or assembleUatRelease
# → app\build\outputs\apk\prod\release\app-prod-release.apk (SIGNED when keystore.properties exists)
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

Publish to UAT: copy the signed APK to `docker/uat/downloads/native-app-v<versionCode>.apk`
(served by the edge at `https://<uat-origin>/native-app-v<versionCode>.apk`; see
`docker/uat/downloads/README.md`). Do NOT `docker cp` it into the console container — that
copy dies on the next recreate.

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

## Bundled shell + OTA device drill (ADR 0051 P2/P3)

The default build is still the **thin client** (ADR 0043 — WebView loads the live origin). This drill
verifies the two things that can only be proven on real hardware before the bundled + OTA model can
be switched on: the **OIDC round-trip inside the `https://localhost` WebView** (P2) and a full **OTA
cycle incl. rollback** (P3). `<ORIGIN>` below is the deployed origin, e.g.
`https://a8.tailbf9662.ts.net:8443`.

### Server prerequisites (once)
- **Gateway CORS** must allow the bundled origin — set on the gateway container and redeploy:
  `NATIVE_GATEWAY_CORS_ALLOWED_ORIGINS=https://localhost` (empty = off = thin-client default).
  Verify from any machine:
  ```bash
  curl -s -D - -o /dev/null -X OPTIONS \
    -H "Origin: https://localhost" -H "Access-Control-Request-Method: GET" \
    "<ORIGIN>/api/v1/companies/mine" | grep -i access-control-allow-origin
  # expect: access-control-allow-origin: https://localhost
  ```
- **Keycloak** `native-console` client must list `https://localhost/*` in Valid Redirect URIs — already
  in `docker/keycloak/native-realm.json`; confirm it's in the RUNNING realm (realm re-import wipes KC
  users — do not re-import to fix this; add the URI via the admin console/kcadm if missing).

### Part A — bundled build + OIDC-in-WebView (closes P2)
```powershell
cd frontend/native-till
$env:NATIVE_TILL_ORIGIN = "<ORIGIN>"   # bakes absolute VITE_API_BASE_URL + <ORIGIN>/auth into the bundle
npm run bundle                          # builds console → stages www/
$env:NATIVE_TILL_BUNDLED = "1"          # drop server.url → serve www from https://localhost
npx cap sync android
cd android; $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleUatRelease
```
Sanity before installing: `android/app/src/main/assets/capacitor.config.json` must have **no**
`server.url` (only `errorPath`). Then uninstall the old app once (signing), install the APK, and check:
1. **Instant boot** — the app shows the splash then the shell with no network wait for the app itself.
2. **Login** — Keycloak login opens, and on success lands back **authenticated** (the redirect to
   `https://localhost/auth/callback` is served by the local shell; the flow stays IN-WebView, never a
   system browser).
3. **Data loads** — dashboard/POS fetch works (proves cross-origin CORS + bearer to `<ORIGIN>/api`).
4. **Printing** — the native printer tile still works (the bridge is present with local assets).

Acceptance (P2): login + data + print all succeed from the bundled build.

### Part B — OTA cycle (closes P3 mechanism)
Rebuild the bundled APK with OTA enabled and install it:
```powershell
$env:NATIVE_TILL_UPDATE_URL = "<ORIGIN>/app/updates/updates.json"
npx cap sync android; cd android; .\gradlew.bat assembleUatRelease   # install this build
```
Make a **visible** web change (e.g. a label), then publish a higher bundle version:
```powershell
cd frontend/native-till
$env:NATIVE_TILL_ORIGIN = "<ORIGIN>"; npm run bundle
$env:NATIVE_TILL_BUNDLE_VERSION = "1.0.1"          # semver, higher than the installed bundle
$env:NATIVE_TILL_OTA_BASE_URL   = "<ORIGIN>/app/updates"
npm run publish                                     # → ota-dist/updates.json + native-till-1.0.1.zip
```
Copy both `ota-dist/*` into the UAT host mount `docker/uat/downloads/app/updates/` (served by
`edge.conf`). Confirm the endpoint answers the client's POST:
`curl -s -X POST "<ORIGIN>/app/updates/updates.json"` → the `{version,url,checksum}` JSON.
On the device: **relaunch twice** (Capgo checks in the background, applies on the *next* cold start).
Acceptance (P3): the visible change appears with **no APK reinstall**.

### Part C — negative rollback test (P3 safety — from the security review)
Publish a deliberately **broken** bundle (a version whose `index.html`/JS white-screens), bump its
version, push it via `updates.json`, relaunch twice. The broken bundle activates, never calls
`notifyAppReady()`, and on the **following** launch Capgo auto-rolls-back to the last good bundle.
Acceptance: a bad bundle cannot brick the till.

### Do NOT switch OTA on for the fleet until (activation gate, then re-review)
Parts A–C green **and** Capgo **encryption v2** (RSA-signed checksum) **and** an anti-rollback control
are in place — see ADR 0051 P3. The `npm run publish` checksum is integrity-only (SHA-256); it proves
no corruption, not authorship. To revert to the thin client, build with `NATIVE_TILL_BUNDLED` unset.

## Versioning contract (D7)

`versionCode`/`versionName` bump **only when native code changes** (plugin, kiosk, WebView host,
manifest). Web features ship via the normal console deploy and appear in the app with no update.
