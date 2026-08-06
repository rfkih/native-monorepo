import type { CapacitorConfig } from '@capacitor/cli';

// Thin-client shell (ADR 0043): the WebView loads the LIVE console origin, so every
// POS feature ships via the normal web deploy with no app update. Override the origin
// per build with NATIVE_TILL_URL (e.g. a prod origin) before `npx cap sync android`.
const SERVER_URL = process.env.NATIVE_TILL_URL ?? 'https://a8.tailbf9662.ts.net:8443';

const config: CapacitorConfig = {
  appId: 'id.co.nativeapp.till',
  appName: 'Native Till',
  // Required by the CLI but unused at runtime while server.url is set — the shell
  // never serves bundled assets (D3: thin client, not bundled-assets).
  webDir: 'www',
  server: {
    url: SERVER_URL,
  },
  android: {
    // The console is HTTPS-only; nothing in the shell talks cleartext.
    allowMixedContent: false,
  },
};

export default config;
