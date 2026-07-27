// End-to-end OIDC smoke: landing → Sign in → Keycloak form → dashboard screenshot.
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

mkdirSync('shots', { recursive: true })
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const browser = await chromium.launch({ executablePath: CHROME })
const page = await browser.newPage({ viewport: { width: 1440, height: 1024 } })

await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1200)
await page.getByRole('button', { name: /sign in/i }).first().click()
await page.waitForURL(/18080/, { timeout: 15000 })
await page.waitForTimeout(1500)
await page.screenshot({ path: 'shots/kc-login.png' })

// The Native login theme may rename ids — locate by input name instead.
const user = page.locator('input[name="username"], #username').first()
const pass = page.locator('input[name="password"], #password').first()
await user.waitFor({ timeout: 10000 })
await user.fill('owner-acme')
await pass.fill('owner-password')
await page
  .locator('#kc-login, button[type="submit"], input[type="submit"]')
  .first()
  .click()
await page.waitForURL(/127\.0\.0\.1:5173|localhost:5173/, { timeout: 20000 })
await page.waitForTimeout(2500)
await page.screenshot({ path: 'shots/live-dashboard.png' })
console.log('logged in, url:', page.url())
await browser.close()
