import type { CapacitorConfig } from '@capacitor/cli';

// Thin-client shell (ADR 0049 P5, mirrors the Till app's ADR 0043 shell pattern): the WebView
// loads the LIVE console origin's role-gated `/me` self-service surface, so every feature ships
// via the normal web deploy with no app update. Override the origin per build with
// NATIVE_EMPLOYEE_URL (e.g. a prod origin) before `npx cap sync android`.
//
// The default is the Employee app's OWN funnel origin, port :10000 (ADR 0049 P5) — NOT the
// console/Business origin :8443, which would load the full console instead of the /me
// self-service surface. Override per environment (e.g. a prod origin) via NATIVE_EMPLOYEE_URL.
const SERVER_URL = process.env.NATIVE_EMPLOYEE_URL ?? 'https://a8.tailbf9662.ts.net:10000';

// Keycloak lives on the BUSINESS origin (ADR 0049 P5 — both apps share one token issuer,
// ${PUBLIC_URL}/auth), which in prod is a DIFFERENT host than this app's own origin
// (app.native-app.my.id vs emp.native-app.my.id). Capacitor opens any top-frame navigation whose
// host is neither server.url's host nor in allowNavigation in the SYSTEM BROWSER
// (Bridge.launchIntent) — which threw every interactive login out to Chrome and stranded the user
// on the web (field report: app "redirects to the web" after a restart, i.e. whenever the silent
// token renew fails and the explicit Keycloak redirect runs). Whitelisting the auth host keeps the
// IdP pages inside the WebView. The check is HOST-only (ports ignored) — why UAT, where the
// employee origin and Keycloak share a8.tailbf9662.ts.net (:10000 vs :8443), never reproduced it.
// The bare default is the UAT auth host — a PROD build must go through scripts/build-app.mjs,
// which sets this together with NATIVE_EMPLOYEE_URL (prod auth default: app.native-app.my.id).
const AUTH_ORIGIN = process.env.NATIVE_EMPLOYEE_AUTH_ORIGIN ?? 'https://a8.tailbf9662.ts.net:8443';

const config: CapacitorConfig = {
  // appId is the app's permanent technical identity — NEVER rename it (a change = a different
  // app to Android + Play). The user-facing launcher name is appName / strings.xml app_name.
  // Distinct from the Business/Till app's appId — a separate installable app.
  appId: 'id.co.nativeapp.employee',
  appName: 'Native Karyawan',
  // Required by the CLI but mostly unused at runtime while server.url is set — the shell
  // serves no bundled app (thin client). Exception: error.html (server.errorPath).
  webDir: 'www',
  // Matches the console's page background (--color-paper) so the moment between splash
  // and first paint isn't a white flash.
  backgroundColor: '#F6F9FA',
  server: {
    url: SERVER_URL,
    // The auth host must stay inside the WebView (see AUTH_ORIGIN above). External links
    // (privacy policy, WhatsApp, …) stay OUT of this list on purpose — they belong in the browser.
    allowNavigation: [new URL(AUTH_ORIGIN).hostname],
    // Offline cold start (no SW in-shell): show a branded retry page instead of Chromium's raw
    // net::ERR_* error. Lives in www/, auto-retries the origin.
    errorPath: 'error.html',
  },
  android: {
    // The console is HTTPS-only; nothing in the shell talks cleartext.
    allowMixedContent: false,
  },
};

export default config;
