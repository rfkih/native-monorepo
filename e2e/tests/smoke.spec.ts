// Deployment smoke — the minimum that must hold on ANY deployed Native stack (UAT or prod).
// These specs are unauthenticated on purpose: they prove the public surface end-to-end
// (tunnel → edge → SPA/Keycloak/gateway) without needing seeded credentials, so the deploy
// pipeline can run them against prod safely.
//
// The full critical-path suite (signup → login per role → POS sale → payment capture → daily
// close → payroll run, incl. the money assertion revenue == recorded sales) is the ADR 0053
// gate-2 follow-up: it needs seeded UAT credentials/data and lives in separate spec files here
// as they land. This file is the floor, not the ceiling.
import { test, expect } from "@playwright/test";

const EMPLOYEE_URL = process.env.E2E_EMPLOYEE_URL ?? "https://a8.tailbf9662.ts.net:10000";

test("console origin serves the SPA shell", async ({ page }) => {
  const response = await page.goto("/");
  expect(response, "console origin must respond").toBeTruthy();
  expect(response!.status(), "console must not error").toBeLessThan(500);
  // Unauthenticated, the console either mounts its shell or redirects to the Keycloak login —
  // both prove edge → SPA wiring. A blank/5xx body proves neither.
  await expect(page.locator("body")).not.toBeEmpty();
});

test("OIDC issuer publishes its discovery document", async ({ request, baseURL }) => {
  const res = await request.get(`${baseURL}/auth/realms/native/.well-known/openid-configuration`);
  expect(res.status(), "issuer discovery must be public").toBe(200);
  const doc = await res.json();
  expect(doc.issuer, "issuer must match the public origin").toBe(`${baseURL}/auth/realms/native`);
  expect(doc.authorization_endpoint).toContain("/auth/realms/native/");
});

test("unauthenticated console reaches the Keycloak login form", async ({ page }) => {
  // Unauthenticated `/` serves the public landing page; "Sign in" starts the OIDC redirect.
  await page.goto("/");
  await page.getByRole("button", { name: /sign in|masuk/i }).click();
  await page.waitForURL(/auth\/realms\/native/, { timeout: 30_000 });
  await expect(page.locator("#username, input[name='username']").first()).toBeVisible();
});

test("keycloak admin surface is NOT publicly reachable", async ({ request, baseURL }) => {
  for (const path of ["/auth/admin/", "/auth/realms/master/", "/auth/"]) {
    const res = await request.get(`${baseURL}${path}`, { maxRedirects: 0 });
    expect([403, 404]).toContain(res.status()); // edge must blackhole these (docker/prod/edge.conf)
  }
});

test("gateway answers through the edge (auth-protected, not 5xx)", async ({ request, baseURL }) => {
  const res = await request.get(`${baseURL}/api/v1/companies/mine`);
  expect(res.status(), "gateway must be up behind the edge").toBeLessThan(500);
  expect(res.status(), "the API must not be anonymously open").not.toBe(200);
});

test("employee origin serves its SPA shell", async ({ page }) => {
  const response = await page.goto(EMPLOYEE_URL);
  expect(response).toBeTruthy();
  expect(response!.status()).toBeLessThan(500);
  await expect(page.locator("body")).not.toBeEmpty();
});
