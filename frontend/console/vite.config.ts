import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'

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
  devOptions: { enabled: false },
  workbox: {
    // Navigating to an API path (should never happen, defensive only) must not fall back to the
    // cached index.html the way a normal SPA route does. `/auth/` is the IdP when Keycloak is
    // co-hosted on the console origin (the UAT single-origin layout): without the denylist entry
    // the SW serves the cached shell INSTEAD of the Keycloak login form on every sign-in
    // navigation once it controls the page — login becomes an instant bounce back to the landing.
    // (`/auth/callback` still reaches the SPA via the server's history fallback — network, not
    // SW cache — and a callback is meaningless offline, so denylisting all of /auth/ is safe.)
    navigateFallbackDenylist: [/^\/api\//, /^\/auth\//],
    runtimeCaching: [
      {
        urlPattern: /^\/api\//,
        handler: 'NetworkOnly',
      },
    ],
  },
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss(), pwa],
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
