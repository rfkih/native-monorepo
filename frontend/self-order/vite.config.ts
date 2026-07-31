import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev-only proxy: /api/** → the gateway (the ONLY reachable path for /api/v1/self-order/** —
// it's the fleet's anonymous business route, AnonymousRateLimitFilter + AnonymousTenantHeaderStripFilter,
// see gateway RoutingConfig / ADR 0029). Mirrors the console's own VITE_GATEWAY_URL dev recipe.
const GATEWAY = process.env.VITE_GATEWAY_URL ?? 'http://localhost:8090'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    // FAIL if 5174 is taken instead of silently hopping ports — mirrors the console's own
    // strictPort convention (a second `npm run dev` means a server is already running).
    strictPort: true,
    proxy: {
      '/api': { target: GATEWAY, changeOrigin: true },
    },
  },
})
