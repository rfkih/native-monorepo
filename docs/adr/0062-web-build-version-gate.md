# 0062. Web-build version gate — a soft "update available" prompt with a self-healing reload

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** rifki, Claude (Opus 4.8)
- **Related:** [0043](0043-native-android-till-app.md) (thin-client shell — no service worker in the Native app), [0028](0028-offline-mode-cash-only-queue.md) (the browser service worker / offline shell), [0051](0051-bundled-shell-with-ota-live-updates.md) (bundled shell + Capgo OTA, dormant)

## Context
The console is a thin client: the Native Till/Employee apps load the live origin, so a fix ships by a normal web deploy — *if* the client actually fetches the new bundle. It often doesn't: the Native shell has **no service worker** (ADR 0043 — a SW-served document loses the Capacitor bridge), so the WebView's **HTTP cache** kept serving a stale app shell across launches, and the exact-match `location = /index.html` no-store rule never covered the **origin root `/`** the WebView actually loads. Net effect: a shipped fix "doesn't show up" until the user clears app data (seen first-hand with the back-button fix). There was **no version signal** at all — nothing told a running client it was behind, and nothing forced a refresh. The owner asked for a way to keep users on the latest version to minimize errors, choosing a **soft** prompt (never a hard block).

## Decision
Stamp every web build with a `__APP_BUILD__` id (vite `define`; CI passes the git short SHA, else a build timestamp) and emit it as **`/version.json`** (excluded from the SW precache; served `no-store`). At runtime the app fetches `/version.json` (`cache: 'no-store'`) on boot, on foreground, and every 15 min; when the deployed build differs from the running one it shows a **dismissible** top banner (`AppUpdatePrompt`, beside `OfflineBanner`) with an **Update** action that tears down any SW + caches and reloads to the latest bundle. It is **never blocking** and **fails closed** (a failed/offline check reads as up-to-date). Independently, nginx now serves the origin **root `/`** (and `/version.json`) `no-store`, so the Native WebView revalidates the shell each launch and picks up new immutable `/assets/` chunks without any user action. **Out of scope:** a NATIVE APK-version gate (blocking "install the new app" when the shell itself is too old — needs `@capacitor/app`), and any hard/forced-update enforcement.

## Consequences
- **Rule code now follows:** anything that must invalidate a cached shell relies on the `/` + `/index.html` + `/version.json` no-store rules and content-hashed immutable `/assets/`; never cache the shell document. A new build id is produced per deploy.
- **Enforced by / tested:** `isOutdated` (the pure staleness rule, fail-closed) is unit-tested (`appVersion.test.ts`); the fetch/reload are DOM side-effects, verified by running the app (the repo does not unit-test DOM behavior — `environment: node`).
- **Easier:** most stale clients now self-correct on relaunch (root no-store); a long-open till that never relaunches still gets nudged by the banner; the operator updates with one tap instead of clearing app data.
- **Costs / follow-ups:** the root `/` and `/version.json` are no longer HTTP-cached (a tiny per-launch revalidation — negligible; `/assets/` stay immutable). The prompt is soft by choice, so a user can keep running a stale bundle until they tap Update. A hard-block minimum-version gate and the native APK-version gate remain future work (the latter pairs naturally with adding `@capacitor/app`).
