# 51. Bundled Android shell + OTA live updates — instant cold start without losing web-deploy cadence

Date: 2026-08-09

## Status

Proposed

Amends [ADR 0043](0043-native-android-till-app.md) (revisits its D1 "bundled web assets rejected as
default" and the SW amendment's "in-app offline cold-start is traded away"); reuses
[ADR 0028](0028-offline-mode-cash-only-queue.md) and [ADR 0049](0049-business-and-employee-apps-outlet-terminal-auth.md)
unchanged.

## Context

ADR 0043 ships the Android till as a **thin Capacitor client in `server.url` mode**: the WebView
renders the *live console origin*, and the workbox service worker is deliberately disabled in the
shell (it would strip the `window.Capacitor` bridge). The consequence — accepted at the time — is
that **every cold start is network-bound**: the WebView fetches `index.html`, then the JS/CSS bundle,
then boots React, *before first paint*. On the UAT Tailscale-Funnel origin (a relay, not a CDN) this
reads as "slow and heavy on startup," and there is no service worker to soften a repeat launch.

The startup-perf pass in `perf(app): faster Android cold start` (gzip + vendor chunk split +
cold-start splash) cut the transfer ~2-3× and masks the gap, but the boot is still fundamentally a
network fetch of the whole app.

ADR 0043 D1 rejected bundling the web assets **for one reason only**: it "re-introduces an app-release
cadence per web feature." That objection is real — but it is *specific to a naive bundle*. An **OTA
live-update** layer removes it: the app boots instantly from a bundle on local disk, and new web
builds are delivered over-the-air in the background, with **no Play/APK release** for a web feature.
That is the combination this ADR adopts.

Constraints carried forward from ADR 0043: the `window.NativePrint` bridge must keep working (it does
with locally-served assets — the SW problem was specific to *service-worker*-served documents, not
Capacitor's local web server); ESC/POS stays in the web layer; no new business service or event; the
Java-25 backend build stays isolated from the Android toolchain.

## Decision

1. **Bundle the built console into the APK and boot from local assets** (`server.url` dropped; Capacitor
   serves `https://localhost` from `webDir`). First paint is disk-speed — no network fetch of the app
   shell. The `window.Capacitor` / `NativePrint` bridge is present for locally-served assets, so
   printing is unaffected. The offline error page (ADR 0043) is no longer the primary path — the shell
   always has an app to render — but is kept for a genuinely broken install.

2. **Deliver web features over-the-air with Capgo (`@capgo/capacitor-updater`), self-hosted.** On
   launch the app serves the newest downloaded bundle instantly and checks a self-hosted update
   endpoint in the background; a newer web bundle downloads and activates on the *next* launch (never
   mid-session). This restores ADR 0043's core promise — **a web feature ships without an app release**
   — while keeping disk-speed boot. Capgo (OSS, self-hostable off the existing edge/object store) is
   chosen over Ionic Appflow (subscription) to match the self-hosted UAT posture and avoid per-seat
   cost. `versionCode` still bumps only on *native* changes (ADR 0043 D7); a web bundle is not a
   native change.

3. **The console runs cross-origin.** Bundled assets load from `https://localhost`; the API and IdP
   are remote. The console API/Keycloak layers are already config-driven (`API_BASE_URL`,
   `KEYCLOAK_URL` — `src/lib/config.ts`), so the bundle is built with absolute `VITE_API_BASE_URL` /
   `VITE_KEYCLOAK_URL` pointing at the deployed origin. No `env.js` (there is no container).

4. **The gateway gains a tight CORS policy.** Spring Cloud Gateway **MVC** (servlet) gets a
   `CorsConfigurationSource` wired into the existing JWT `SecurityFilterChain`
   (`gateway/security/SecurityConfig.java`), allowing **only** the bundled shell origin(s)
   (`https://localhost`, and `capacitor://localhost` for iOS if ever added) — an explicit allow-list
   from config, never `*`. Allowed methods/headers are scoped to what the console sends
   (`Authorization`, `X-Company-Id`, `X-Operator-Session`, `Idempotency-Key`, `Content-Type`,
   `Accept`); credentials are **not** used (auth is a bearer header, not cookies), so
   `allowCredentials` stays false. Preflight (`OPTIONS`) is permitted without a JWT; every real method
   still hits the unchanged JWT validation. **This change is on the security edge and MUST get a
   security-engineer review** (tenancy/auth boundary).

5. **Keycloak `native-console` gains the bundled-origin redirect URIs.** Add `https://localhost/*`
   (and `https://localhost` as a Web Origin) to the client's `redirectUris`/`webOrigins`
   (`docker/keycloak/native-realm.json` + the deployed realm). OIDC authorization-code + PKCE runs the
   same as today: the WebView navigates to the *remote* Keycloak login, which redirects back to
   `https://localhost/auth/callback`, served by Capacitor's local web server (SPA history fallback);
   `oidc-client-ts` reads the `?code=`. `window.location.origin` is `https://localhost`, so
   `redirect_uri` derives correctly with no console code change. (Keycloak is self-hosted, so Google's
   embedded-WebView IdP restriction — a watch-item in ADR 0043 D4 — does not apply; a future *social*
   login would still need a Custom Tab.) **Security note (P2 review):** `https://localhost` is a
   non-unique origin, so the code-exchange safety rests on PKCE S256 *and* the flow staying **inside
   the WebView** — the `https://localhost` redirect must never be delegated to the system browser / an
   Android App Link. PKCE S256 is enforced and `directAccessGrantsEnabled: false`; the `*` in
   `https://localhost/*` is a path wildcard (Keycloak matches the host literally, so no
   `localhost.attacker.com` open-redirect).

6. **Toolchain isolation and distribution unchanged** (ADR 0043 D5/D6): `frontend/native-till/` stays a
   monorepo sibling with its own Android build; sideload/MDM for UAT, Play for GA.

## Consequences

- **Cold start becomes disk-speed** — the app shell no longer waits on the network (or the relay) at
  all; only live data does. This is the step-change the perf pass could not reach.
- **Web-deploy cadence is preserved** via OTA — the property ADR 0043 was built around is kept, at the
  cost of running a Capgo update endpoint (an operational dependency + a bundle-publish CI step).
- **New security surface on the gateway** (CORS) and **new IdP config** (redirect URIs) — both scoped
  to the bundled origin, both reviewed (P2 review: PASS). CORS is off by default, exact-origin only,
  credential-less, `/api/**`-only, and a `*`/cleartext-non-loopback origin is rejected at startup so
  the gateway cannot boot permissive. Follow-up (pre-existing, not a regression): the **production**
  realm must strip the dev `http://localhost:5173|4173` redirect URIs from `native-console` — they are
  dev-only.
- **A device-verified OIDC round-trip is now a release gate** — OIDC-in-a-local-WebView can only be
  proven on a device (add to the ADR 0043 hardware drill).
- **First-run needs connectivity** (download the initial data + log in); subsequent boots are instant
  and offline-cold-start of the *shell* is restored (superseding the ADR 0043 SW-amendment trade-off),
  while the ADR 0028 IndexedDB offline sales queue is untouched.
- No event, schema, or business-service change (`docs/EVENT-CATALOG.md` untouched).

## Plan

- **P1 — Bundling foundation (safe, no security surface).** A build pipeline that produces the console
  `dist` and stages it into `native-till/www`; env-toggled Capacitor bundled mode
  (`NATIVE_TILL_BUNDLED=1` drops `server.url`, else the current thin client is unchanged); native
  build env for absolute `VITE_API_BASE_URL`/`VITE_KEYCLOAK_URL`. Verifiable locally; does not flip
  production behavior. (Capgo is specified here but installed in P3, to avoid adding an inert native
  plugin — and a `versionCode` bump — before the OTA server exists.)
- **P2 — Cross-origin backend (security-reviewed).** Gateway CORS allow-list + Keycloak redirect URIs;
  device-verified OIDC round-trip on the bundled build. Flip the bundled build for UAT.
- **P3 — OTA activation.** Stand up the self-hosted Capgo endpoint (off the edge/object store), a
  bundle-publish CI step, `autoUpdate: true`, and a rollback path; verify a web feature reaches an
  installed till with no APK.
- **P4 — GA.** Fold OTA + the bundled build into the Play release; flip this ADR to `Accepted`.

Flip ADR 0043 to note it is amended here for the delivery model (its print-bridge decision stands).
