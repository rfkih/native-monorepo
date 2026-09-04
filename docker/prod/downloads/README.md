# PROD Android installer downloads

This directory is bind-mounted read-only into the PROD **edge** nginx at `/srv/downloads`
(see `docker/compose.prod.yml`). The edge serves any top-level `/*.apk` URL from here on the
**business origin** — `https://<prod-origin>/native-app-v<versionCode>.apk` → the file in this folder.
Contents (APK / OTA zips) are **gitignored**; only this README + `.gitkeep` are tracked.

Stable links (edge 302 redirects, `docker/prod/edge.conf`):
- **Business/Till app:** `https://<prod-origin>/native-app-latest.apk` → the current versioned APK.
- **Employee app:** `https://<prod-origin>/native-employee-app-latest.apk` → the current versioned APK.

Both 404 until the first prod build is dropped here — that is intentional.

## PROD ≠ UAT — a separate app (ADR 0058)

The prod app is a **different installable app** from the UAT app, so a device can hold both:

| | applicationId | Launcher name | Icon |
|---|---|---|---|
| **Prod** | `id.co.nativeapp.till` / `…​.employee` | Native / Native Karyawan | brand (cyan) |
| **UAT** | `id.co.nativeapp.till.uat` / `…​.employee.uat` | Native UAT / Native Karyawan UAT | amber, badged |

The prod app is built from the Gradle `prod` product flavor and points at the **prod** origin.
The UAT app (`uat` flavor) points at UAT and lives under `docker/uat/downloads/`.

## ⚠️ Do NOT drop a build baked against an ephemeral quick-tunnel URL

The prod WebView origin is **baked into the APK at build time**. While prod runs on an ephemeral
`*.trycloudflare.com` quick tunnel, that URL changes on every prod restart — an installed app would
break. Wait for the **named tunnel / domain**, then build against that stable origin. The build
script refuses an ephemeral URL for a prod build unless you pass `--allow-ephemeral` (throwaway only).

## Publishing a release (once a stable prod origin exists)

```powershell
# Business/Till app
cd frontend/native-till
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:NATIVE_TILL_URL = "https://<prod-domain>"      # the STABLE prod origin
npm run build:prod                                  # → dist/native-app-prod-v<versionCode>.apk
Copy-Item dist\native-app-prod-v<N>.apk "..\..\docker\prod\downloads\native-app-prod-v<N>.apk"

# Employee app
cd ..\native-employee
$env:NATIVE_EMPLOYEE_URL = "https://<prod-employee-origin>"
npm run build:prod                                  # → dist/native-employee-app-prod-v<versionCode>.apk
Copy-Item dist\native-employee-app-prod-v<N>.apk "..\..\docker\prod\downloads\native-employee-app-prod-v<N>.apk"
```

Then bump the `native-app-latest.apk` / `native-employee-app-latest.apk` redirect targets in
`docker/prod/edge.conf` to the new `native-*-prod-v<N>.apk` filenames.

⚠️ edge.conf is a SINGLE-FILE bind mount: `sed -i` and most editors replace the file's inode, and
the container keeps the OLD inode — `nginx -s reload` then reloads the stale config and the 302
silently keeps serving the previous version (bit the v9 release). Two working recipes:

- Zero-downtime: rewrite the file IN PLACE (same inode), then reload —
  `sed 's|v8\.apk|v9.apk|' edge.conf > /tmp/e && cat /tmp/e > edge.conf && rm /tmp/e`
  then `docker exec native-prod-edge nginx -s reload`.
- After an inode-replacing edit (`sed -i`, vim): `docker restart native-prod-edge` (~2s blip) —
  the restart re-resolves the mount by path.

Either way, VERIFY from inside the container before trusting the public URL:
`docker exec native-prod-edge grep native-app-latest /etc/nginx/conf.d/default.conf`.

Delete superseded `native-*-prod-v<old>.apk` once no link points at them (keep one for rollback).

Signing keys (unchanged from UAT — the app's permanent identity, back them up):
`C:\Users\rifki\native-till-signing\` (Till) and the Employee app's own keystore.
```
