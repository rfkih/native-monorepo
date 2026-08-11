// Native e2e — runs against a DEPLOYED stack, never a local dev server (ADR 0053: the QA gate
// exercises UAT; the deploy smoke exercises prod through the same specs where safe).
//
//   E2E_BASE_URL      business/console origin (default: the stable UAT funnel)
//   E2E_EMPLOYEE_URL  employee-app origin     (default: the UAT :10000 funnel)
//
//   cd e2e && npm ci && npm run install-browsers && npm test
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  retries: 1, // funnel/tunnel hops can blip; one retry keeps the gate honest without flake-masking
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "https://a8.tailbf9662.ts.net:8443",
    // E2E_CHANNEL=chrome runs on the system Chrome (no playwright-browser download) — handy
    // locally; CI installs chromium via `npm run install-browsers` and leaves this unset.
    channel: process.env.E2E_CHANNEL || undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
