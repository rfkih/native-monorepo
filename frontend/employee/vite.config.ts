import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

/**
 * The Employee app (ADR 0049 P5) — a dedicated self-service web app that reuses the console's
 * `/me` surface UNCHANGED, under a plain personal OIDC login. It does not fork or copy console
 * source: `@` resolves straight into `../console/src`, so every `@/features/me/*`,
 * `@/lib/*`, `@/components/*` import is the SAME module the console itself ships — one
 * implementation, two front doors (mirrors frontend/self-order's own second-package shape, but
 * self-order reimplements a tiny client; this package shares source instead).
 */

// Dev-only proxy: /api/** → the gateway, same recipe as console/self-order's own VITE_GATEWAY_URL
// dev proxy — the ONLY reachable path for the authenticated /api/v1/me/**, /api/v1/expenses/**,
// /api/v1/payroll-runs/** etc. the shared `/me` surface calls (see @/lib/api.ts, API_BASE_URL).
const GATEWAY = process.env.VITE_GATEWAY_URL ?? 'http://localhost:8090'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // Consume the console's source READ-ONLY — no file under frontend/console is ever copied here.
    alias: { '@': fileURLToPath(new URL('../console/src', import.meta.url)) },
    // frontend/console and frontend/employee are SIBLING packages (no npm workspace), each with
    // its own node_modules — so a bare `import ... from 'react'` resolves differently depending on
    // the PHYSICAL location of the importing file: this app's own src/*.tsx resolves to THIS
    // package's copy, but every aliased `@/**` file physically lives inside ../console/src and
    // would otherwise resolve to the CONSOLE's copy instead. Two live copies of react/react-dom in
    // one render tree is the classic "Invalid hook call" dual-package hazard (every hook reads a
    // dispatcher stored on ITS OWN copy of the `react` module — the copy react-dom actually set the
    // dispatcher on is the only one that works); react-router-dom/@tanstack/react-query/
    // react-i18next/i18next all carry the same hazard via React Context or a `.use()`-registered
    // singleton. `dedupe` forces every one of these — however deep/wherever the importer physically
    // lives — to resolve to THIS package's single copy (see tsconfig.app.json's matching `paths`
    // for the tsc side of the same fix; Vite and tsc each need telling separately).
    dedupe: [
      'react',
      'react-dom',
      'react-router-dom',
      'react-i18next',
      'i18next',
      '@tanstack/react-query',
      'oidc-client-ts',
    ],
  },
  server: {
    port: 5175,
    // FAIL if 5175 is taken instead of silently hopping ports — mirrors console (5173) and
    // self-order (5174): a second `npm run dev` means a server is already running.
    strictPort: true,
    // The `@` alias reaches into ../console/src; source modules pass Vite's default fs allow-list
    // via the alias, but RAW assets served over /@fs (the Plus Jakarta Sans woff2 in
    // console/src/assets/fonts) get 403'd without listing the sibling root — dev then silently
    // falls back to the system font. Dev-server only; production bundles the font.
    fs: {
      allow: ['.', fileURLToPath(new URL('../console', import.meta.url))],
    },
    proxy: {
      '/api': { target: GATEWAY, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
  },
})
