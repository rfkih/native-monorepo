import type { CapacitorConfig } from '@capacitor/cli';

// Thin-client shell (ADR 0049 P5, mirrors the Till app's ADR 0043 shell pattern): the WebView
// loads the LIVE console origin's role-gated `/me` self-service surface, so every feature ships
// via the normal web deploy with no app update. Override the origin per build with
// NATIVE_EMPLOYEE_URL (e.g. a prod origin) before `npx cap sync android`.
//
// PLACEHOLDER: the fallback below is the shared UAT funnel origin (the same one the Till app
// shell points at) — there is no dedicated employee origin yet. Replace this default once one
// exists; do not ship this placeholder to a real release build without an explicit
// NATIVE_EMPLOYEE_URL override.
const SERVER_URL = process.env.NATIVE_EMPLOYEE_URL ?? 'https://a8.tailbf9662.ts.net:8443';

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
