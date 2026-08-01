import { defineConfig } from 'vitest/config'

// Kept SEPARATE from vite.config.ts on purpose (mirrors the console's own vitest.config.ts): the
// app's Vite config wires the React + Tailwind plugins (browser build concerns) the unit tests below
// don't need — api.ts's logic (idempotency-key threading, token-payload decoding) is plain TS, so a
// plain `node` environment is enough (no jsdom/browser DOM required).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
