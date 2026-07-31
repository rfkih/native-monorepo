import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'node:url'

// Kept SEPARATE from vite.config.ts on purpose: the app's Vite config wires the React + Tailwind +
// PWA plugins (browser build concerns) that the unit tests below don't need — the offline module's
// tests are plain TS logic (pricing math, queue/sync state machine) plus IndexedDB via
// fake-indexeddb, so a plain `node` environment is enough (no jsdom/browser DOM required).
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    setupFiles: ['./src/features/pos/offline/__tests__/setup.ts'],
    include: ['src/**/*.test.ts'],
  },
})
