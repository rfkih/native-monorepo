# 0058. Per-environment Android app identities (UAT vs Prod, side-by-side)

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** product owner (rifki) + tech-lead
- **Amends:** [0043](0043-native-till-android-shell.md) D7 (the Till app's identity/versioning contract) and [0049](0049-business-and-employee-apps-outlet-terminal-auth.md) P5 (the Employee app) — the single `applicationId` per app is now the **prod** flavor's identity; a **uat** flavor with a suffixed id is added. Everything else in those ADRs stands.
- **Related:** `frontend/native-till/**`, `frontend/native-employee/**` (Gradle `productFlavors`, `src/uat/res`, `scripts/build-app.mjs`); [0057](0057-cloudflare-edge-rollback-first-prod-deploy.md) (prod edge) — `docker/prod/edge.conf` + `docker/prod/downloads/` now serve the prod installer; `docker/uat/edge.conf` + `docker/uat/downloads/` serve UAT.

## Context

Both Android shells (Native Till / Business, and Native Karyawan / Employee) are thin Capacitor clients whose backend origin is baked at `cap sync` time from an env var (`NATIVE_TILL_URL` / `NATIVE_EMPLOYEE_URL`). Until now that env var was the **only** thing distinguishing a UAT build from a prod build: the `applicationId` (`id.co.nativeapp.till` / `…​.employee`) and the launcher name ("Native" / "Native Karyawan") were identical in both.

That is a problem the moment prod distribution exists (ADR 0057 stood up prod but deliberately left the APK host-mount out — "add it when it is wired"):

1. **Collision.** A UAT-pointed and a prod-pointed APK share one `applicationId`, so installing one **uninstalls the other**. A tester cannot run both; a merchant device and a test device cannot be the same phone.
2. **Indistinguishable.** Both are named "Native" with the same icon — no way to tell at a glance which environment an installed app talks to. Ringing a real sale from a UAT build (or testing against prod) is an easy, costly mistake.

## Decision

### 1. UAT and prod are separate installable apps (Gradle product flavors)
Add a `flavorDimensions "env"` with two flavors in each app's `android/app/build.gradle`:

| Flavor | applicationId | Launcher name | Icon | Origin |
|---|---|---|---|---|
| `prod` | `id.co.nativeapp.till` / `…​.employee` (unchanged — the canonical, permanent, future Play id) | Native / Native Karyawan | brand (cyan) | the stable prod origin |
| `uat` | `…​.till.uat` / `…​.employee.uat` (via `applicationIdSuffix ".uat"`) | Native UAT / Native Karyawan UAT | amber, badged | UAT |

The `.uat` suffix lets both install side-by-side and keeps their OTA channels (ADR 0051) separate. **Prod keeps the base id** so its installed-app lineage and any future Play upload are unaffected. UAT-only overrides (strings + a badged amber adaptive icon) live in `src/uat/res` — a flavor source set that *replaces* same-named `src/main` resources for the `uat` variant only, so there is no resource-merge duplicate. The launcher intent-filter is MAIN/LAUNCHER only (no BROWSABLE deep-link scheme), and OIDC is an in-WebView https redirect, so changing the id per flavor is safe.

### 2. One command pairs origin ⇄ flavor
The origin (sync-time env) and the flavor (Gradle build task) are independent stages, so they can be mismatched by hand. `scripts/build-app.mjs` (`npm run build:uat` / `build:prod`) makes `--env` the single source of truth: it sets the origin env, runs `cap sync`, builds `assemble<Flavor>Release`, and writes `dist/native-*-<env>-v<code>.apk`. A **prod** build **refuses** an unset origin or an ephemeral `*.trycloudflare.com` quick-tunnel URL (baking it would break the app on the next prod restart) unless `--allow-ephemeral` is passed.

### 3. Prod serves its own installer, mirroring UAT
`docker/prod/edge.conf` serves top-level `/*.apk` + `/app/updates/` from a new `./prod/downloads` host mount (added to the `edge` service in `compose.prod.yml`), with stable `native-app-latest.apk` / `native-employee-app-latest.apk` 302 aliases. Binaries are gitignored; the dir is kept by `.gitkeep`. See `docker/prod/downloads/README.md`.

## Consequences

- A device can hold **Native** (prod) and **Native UAT** at once, visually distinct (amber badge + " UAT" suffix). No more accidental cross-environment ringing.
- **Existing UAT testers migrate**: the app they have (`id.co.nativeapp.till`, pointed at UAT) becomes the *prod* id; the new UAT build is `…​.uat`. They uninstall the old and install "Native UAT" once. A release-signed flavor cannot install over the differently-identified one — uninstall first.
- Build tasks change: `assembleRelease` → `assembleProdRelease` / `assembleUatRelease`; output paths gain a flavor segment (`apk/<flavor>/release/app-<flavor>-release.apk`). READMEs updated; `npm run apk` now builds `assembleUatDebug`.
- **The prod APK is deferred, not blocked**: everything (flavors, wrapper, edge serving, docs) is wired now; the prod build is a one-command drop once a **named tunnel / domain** (ADR 0057's pending item) gives a stable origin. Until then `…/native-app-latest.apk` on the prod origin 404s by design.
- Legacy pre-API-26 launcher PNGs stay branded for the UAT flavor (only the adaptive icon is badged); the " UAT" name still distinguishes them on old devices.
