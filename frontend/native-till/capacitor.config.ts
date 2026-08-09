import type { CapacitorConfig } from '@capacitor/cli';

// Thin-client shell (ADR 0043): the WebView loads the LIVE console origin, so every
// POS feature ships via the normal web deploy with no app update. Override the origin
// per build with NATIVE_TILL_URL (e.g. a prod origin) before `npx cap sync android`.
const SERVER_URL = process.env.NATIVE_TILL_URL ?? 'https://a8.tailbf9662.ts.net:8443';

// Bundled mode (ADR 0051): NATIVE_TILL_BUNDLED=1 drops server.url so the WebView boots the app from
// local disk (webDir → https://localhost) for instant, network-free cold start. The bundle is staged
// by `scripts/bundle-console.mjs` (built with absolute api/keycloak URLs). Default stays the thin
// client until the bundled build is verified end-to-end on a device (P2) and OTA lands (P3).
const BUNDLED = process.env.NATIVE_TILL_BUNDLED === '1' || process.env.NATIVE_TILL_BUNDLED === 'true';

const config: CapacitorConfig = {
  // appId is the app's permanent technical identity — NEVER rename it (a change = a different
  // app to Android + Play). The user-facing launcher name is appName / strings.xml app_name.
  appId: 'id.co.nativeapp.till',
  appName: 'Native',
  // Required by the CLI but mostly unused at runtime while server.url is set — the shell
  // serves no bundled app (D3: thin client). Exception: error.html (server.errorPath).
  webDir: 'www',
  // Matches the console's page background (--color-paper) so the moment between splash
  // and first paint isn't a white flash.
  backgroundColor: '#F6F9FA',
  // Bundled (ADR 0051): no `url` → Capacitor serves the packaged webDir from local disk.
  // Thin client (ADR 0043): `url` points the WebView at the live origin.
  // Both keep errorPath: a branded retry page (www/error.html) instead of Chromium's raw
  // net::ERR_* — the primary path when thin, a broken-install fallback when bundled.
  server: BUNDLED
    ? { errorPath: 'error.html' }
    : { url: SERVER_URL, errorPath: 'error.html' },
  android: {
    // The console is HTTPS-only; nothing in the shell talks cleartext.
    allowMixedContent: false,
  },
  plugins: {
    // Cold-start splash (startup-perf pass): a thin client (ADR 0043) must fetch the whole console
    // over the network before first paint, and there is no service worker to soften it — so the gap
    // was a blank paper screen. This keeps a branded splash (paper background = --color-paper, no
    // white flash) over exactly that gap. The web app calls SplashScreen.hide() the instant it
    // paints its first frame (frontend/console/src/lib/nativeSplash.ts), so on a healthy load the
    // splash disappears the moment content is ready. launchShowDuration is only a BACKSTOP for the
    // paths that never signal hide — the offline error page, or an older console deploy without the
    // hide call — so the terminal can never get stuck on the splash.
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 3000,
      backgroundColor: '#F6F9FA',
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: false,
    },
  },
};

export default config;
