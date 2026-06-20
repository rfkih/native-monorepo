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
      // restaurant-service (POS)
      '/api/v1/menu': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/orders': { target: RESTAURANT, changeOrigin: true },
      '/api/v1/sales': { target: RESTAURANT, changeOrigin: true },
    }

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy,
  },
})
