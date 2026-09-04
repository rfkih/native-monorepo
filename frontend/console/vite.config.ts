import { defineConfig, type ProxyOptions, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'

// A per-build identity stamped into the bundle (`__APP_BUILD__`) AND emitted as /version.json, so a
// running client can detect it is behind the deployed build and offer a self-healing reload
// (ADR 0062). CI passes a git short SHA via VITE_APP_BUILD; a local/UAT `docker build` with no arg
// falls back to a build timestamp (still unique per deploy). Date.now() here runs in Node at BUILD
// time — this is the vite config, not app code.
const APP_BUILD = process.env.VITE_APP_BUILD || Date.now().toString(36)

// Emits dist/version.json = {build} so the deployed origin always advertises its current build id.
// Served no-store (nginx) and fetched no-store (lib/appVersion) so a stale client never reads a
// cached copy; kept out of the workbox precache via `globIgnores` below.
const emitVersionJson: Plugin = {
  name: 'native-version-json',
  generateBundle() {
    this.emitFile({
      type: 'asset',
      fileName: 'version.json',
      source: JSON.stringify({ build: APP_BUILD }),
    })
  },
}

// Dev proxy targets. In the documented dev recipe each service runs directly (gradle bootRun) on a
// port you choose, with NATIVE_DEV_TENANT_FILTER_ENABLED=true (no gateway / JWT in dev). The console
// reaches org-service (company onboarding) and finance-service (the dashboard) on their own ports.
// Override via env: VITE_ORG_URL / VITE_FINANCE_URL. Defaults assume org=8082, finance=8085.
const ORG = process.env.VITE_ORG_URL ?? 'http://localhost:8082'
const FINANCE = process.env.VITE_FINANCE_URL ?? 'http://localhost:8085'
const RESTAURANT = process.env.VITE_RESTAURANT_URL ?? 'http://localhost:8086'
const EMPLOYEE = process.env.VITE_EMPLOYEE_URL ?? 'http://localhost:8084'

// When VITE_GATEWAY_URL is set (the oidc dev recipe), proxy ALL /api/** to the single gateway
// origin (the bearer token flows through it, same as production). Otherwise fall back to the
// per-path proxy straight to each service (the header-trust dev recipe, no gateway).
const GATEWAY = process.env.VITE_GATEWAY_URL

const proxy: Record<string, ProxyOptions> = GATEWAY
  ? { '/api': { target: GATEWAY, changeOrigin: true } }
  : {
      // org-service
      '/api/v1/companies': { target: ORG, changeOrigin: true },
      '/api/v1/org-units': { target: ORG, changeOrigin: true },
      '/api/v1/consolidation-groups': { target: ORG, changeOrigin: true },
      // finance-service
      '/api/v1/pnl': { target: FINANCE, changeOrigin: true },
      '/api/v1/revenue': { target: FINANCE, changeOrigin: true },
      '/api/v1/statements': { target: FINANCE, changeOrigin: true },
      '/api/v1/closes': { target: FINANCE, changeOrigin: true },
      '/api/v1/groups': { target: FINANCE, changeOrigin: true },
      // employee-service (HR + payroll)
      '/api/v1/employees': { target: EMPLOYEE, changeOrigin: true },
      '/api/v1/payroll-runs': { target: EMPLOYEE, changeOrigin: true },
      '/api/v1/payroll-setup': { target: EMPLOYEE, changeOrigin: true },
      // employee-service (ADR 0049 P3b) — the till's operator roster + PIN sign-in
      '/api/v1/operators': { target: EMPLOYEE, changeOrigin: true },
      // restaurant-service (POS)
      '/api/v1/menu': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/orders': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/sales': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/payments': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/tables': { target: RESTAURANT, changeOrigin: true },
      // restaurant-service (Phase 6, ADR 0029) — MANAGEMENT side of self-order QR (owner/manager,
      // authenticated). The anonymous `/api/v1/self-order/**` diner surface is reachable ONLY
      // through the gateway (AnonymousRateLimitFilter) — see frontend/self-order's own dev proxy.
      '/api/v1/self-order-access': { target: RESTAURANT, changeOrigin: true },
      // restaurant-service (Phase 5 offline mode, ADR 0028) — cached client-side for offline pricing
      '/api/v1/pricing': { target: RESTAURANT, changeOrigin: true },
    }

// PWA (Phase 5 offline mode, ADR 0028): precaches the built app shell so the POS UI itself loads
// offline; API traffic is NEVER cached or served by the service worker — offline sales are queued
// in IndexedDB (features/pos/offline) and replayed once connectivity returns, so a stale cached API
// response would be actively wrong, not merely unhelpful. `devOptions.enabled: false` keeps the
// service worker out of `npm run dev` entirely — it only activates in a production build/preview.
const pwa = VitePWA({
  registerType: 'autoUpdate',
  // Registration is hand-rolled in src/lib/pwa.ts (not auto-injected): inside the Native Till
  // Android shell the SW must NOT register at all — a SW-served document can't receive the
  // Capacitor bridge injection (ADR 0043), which silently kills in-app printing.
  injectRegister: null,
  devOptions: { enabled: false },
  workbox: {
    // /version.json is the deploy's build advertiser — it MUST always come from the network (the
    // whole point is to detect a stale precache), so keep it out of the precache manifest. It is a
    // Rollup-EMITTED asset, which `globIgnores` doesn't catch, so filter the final manifest instead.
    manifestTransforms: [
      (manifest) => ({
        manifest: manifest.filter((entry) => !entry.url.endsWith('version.json')),
      }),
    ],
    // Navigating to an API path (should never happen, defensive only) must not fall back to the
    // cached index.html the way a normal SPA route does. `/auth/` is the IdP when Keycloak is
    // co-hosted on the console origin (the UAT single-origin layout): without the denylist entry
    // the SW serves the cached shell INSTEAD of the Keycloak login form on every sign-in
    // navigation once it controls the page — login becomes an instant bounce back to the landing.
    // (`/auth/callback` still reaches the SPA via the server's history fallback — network, not
    // SW cache — and a callback is meaningless offline, so denylisting all of /auth/ is safe.)
    // .apk: the Native app installer is served off this origin (host mount on the UAT edge,
    // docker/uat/downloads) — without the denylist entry the SW serves the SPA shell instead
    // of the download and the router bounces the user to the landing page (field-found).
    navigateFallbackDenylist: [/^\/api\//, /^\/auth\//, /\.apk$/],
    runtimeCaching: [
      // Menu images off the object store (ADR 0048): /api/media/** URLs are content-addressed
      // (sha256 keys) and served with Cache-Control: immutable, so CacheFirst is safe by
      // construction — a changed image is a DIFFERENT URL. This is what keeps product photos on
      // the offline till: the old base64-in-IndexedDB payload is gone, so previously-viewed
      // images must survive via the SW cache instead. MUST be registered BEFORE the generic
      // /api/ NetworkOnly rule (workbox: first matching route wins). Function matcher, not a
      // RegExp: workbox tests RegExps against the full href, where a ^\/api\/ anchor can never
      // match.
      {
        urlPattern: ({ url, sameOrigin }) =>
          sameOrigin && url.pathname.startsWith('/api/media/'),
        handler: 'CacheFirst',
        options: {
          cacheName: 'media-images',
          expiration: { maxEntries: 500, maxAgeSeconds: 30 * 24 * 60 * 60 },
          cacheableResponse: { statuses: [200] },
        },
      },
      {
        urlPattern: /^\/api\//,
        handler: 'NetworkOnly',
      },
    ],
  },
})

// Boot-critical vendor split (startup-perf pass). The entry chunk carried React + Router + Query +
// oidc-client-ts + i18next all inline (~700 KB). Splitting the STABLE libraries into their own
// content-hashed chunks means: (1) a normal web/app deploy that only changes app code leaves these
// hashes untouched, so the immutable-cached vendor chunks survive in the WebView disk cache and are
// NOT re-downloaded (the Android shell has no service worker — ADR 0043 — so the HTTP cache is the
// only thing keeping repeat launches fast); (2) they download in parallel over HTTP/2 instead of as
// one long pole. CRITICAL: only libraries already on the boot path are bucketed here. Everything else
// is left to Rollup's default so a lazy-only dependency stays OUT of the boot download and inside its
// route chunk — e.g. `qrcode` is reached only through the lazy POS chunks (QrisPanelViews /
// SelfOrderQr), so it must never be named here.
function bootVendorChunk(id: string): string | undefined {
  if (!id.includes('node_modules')) return
  if (/[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return 'vendor-react'
  if (/[\\/]node_modules[\\/](react-router|@remix-run)[\\/]/.test(id)) return 'vendor-router'
  if (/[\\/]node_modules[\\/]@tanstack[\\/]/.test(id)) return 'vendor-query'
  if (/[\\/]node_modules[\\/]oidc-client-ts[\\/]/.test(id)) return 'vendor-oidc'
  if (/[\\/]node_modules[\\/](i18next|react-i18next)[\\/]/.test(id)) return 'vendor-i18n'
  return undefined
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss(), pwa, emitVersionJson],
  define: {
    // Baked into the bundle; read via lib/appVersion.ts. JSON.stringify so it inlines as a literal.
    __APP_BUILD__: JSON.stringify(APP_BUILD),
  },
  build: {
    rollupOptions: {
      output: { manualChunks: bootVendorChunk },
    },
  },
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // FAIL if 5173 is taken instead of silently hopping to 5174/5175 — a second `npm run dev`
    // means a server is ALREADY running: reuse it, don't stack instances.
    strictPort: true,
    // Allow extra hostnames when the dev server is reached through a tunnel/reverse proxy
    // (e.g. a Tailscale Funnel host). Comma-separated in VITE_ALLOWED_HOSTS; default keeps
    // Vite's normal localhost-only host check.
    allowedHosts: process.env.VITE_ALLOWED_HOSTS
      ? process.env.VITE_ALLOWED_HOSTS.split(',')
      : undefined,
    proxy,
  },
})
