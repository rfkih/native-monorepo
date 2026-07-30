import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
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
    }

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
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
