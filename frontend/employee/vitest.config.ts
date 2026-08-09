import { defineConfig } from 'vitest/config'

// Kept SEPARATE from vite.config.ts on purpose (mirrors console's and self-order's own
// vitest.config.ts). No unit tests ship with this package yet (it is a thin router + provider
// stack around the console's own already-tested `/me` surface) — `npm test` stays green via
// `--passWithNoTests` in package.json until a test is added.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
