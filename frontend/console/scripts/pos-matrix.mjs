#!/usr/bin/env node
/**
 * pos-matrix.mjs — the POS screenshot matrix (P0 of the POS redesign program).
 *
 * Captures a parameterized gallery of POS states across all three verticals
 * (restaurant / carwash / barbershop), viewports (portrait tablet 834×1194,
 * phone 390×844), and themes (light/dark), including print-emulated receipt
 * shots (the print-isolation regression tripwire).
 *
 * Prereqs (the OIDC dev recipe — the only mode that reaches carwash/barbershop):
 *   1. Full local stack + all services up (org 8082 … barbershop 8088, gateway 8090,
 *      Keycloak 18080). See docs/RUNBOOK.md / scripts/start-dev-services.ps1.
 *   2. Console dev server in gateway mode:
 *        VITE_GATEWAY_URL=http://localhost:8090 npm run dev
 *   3. Seeded dev data for company 11111111- (owner-acme login): Resto Kota Tua menu
 *      (incl. "Nasi Goreng Special" with modifier groups), Acme Car Wash packages,
 *      Acme Barbershop services + barbers. Outlet ids below are the dev-stack ids;
 *      override via NATIVE_SHOT_OUTLET_RESTAURANT / _CARWASH / _BARBERSHOP.
 *      Optional: at least one active /channels sales channel (ADR 0036 Phase B3) to capture the
 *      ONLINE tender's populated state — without one the shot still succeeds, just showing the
 *      panel's empty state instead.
 *
 * Usage:
 *   node scripts/pos-matrix.mjs [--base http://localhost:5173] [--out shots/base]
 *                               [--only restaurant-flow,carwash-flow] [--headful]
 *
 * Scenario keys: restaurant-flow, restaurant-bills, restaurant-dark, restaurant-phone,
 *                carwash-flow, carwash-dark, carwash-phone, barbershop-flow, barbershop-dark
 *
 * Failure policy: a failed step aborts ITS scenario (with a <key>-FAILED.png), the run
 * continues, and the process exits 1 with a summary — a gallery must never silently lie
 * about what it covered.
 */
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
function argOf(flag, dflt) {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const BASE = argOf('--base', 'http://localhost:5173')
const ONLY = (argOf('--only', '') || '').split(',').filter(Boolean)
const HEADFUL = args.includes('--headful')

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.resolve(consoleRoot, argOf('--out', 'shots/base'))
mkdirSync(OUT, { recursive: true })

const OUTLETS = {
  restaurant: process.env.NATIVE_SHOT_OUTLET_RESTAURANT ?? '5f5e0167-ee70-45b8-8afe-019e8129e659', // Resto Kota Tua
  carwash: process.env.NATIVE_SHOT_OUTLET_CARWASH ?? 'b555d5b8-e17b-4990-a7dd-2c4be199d7a6', // Acme Car Wash
  barbershop: process.env.NATIVE_SHOT_OUTLET_BARBERSHOP ?? 'e24fa39b-ee8a-497e-925a-901408e98168', // Acme Barbershop
}

const VIEWPORTS = {
  tablet: { width: 834, height: 1194 }, // the design baseline (scripts/design-shots.mjs, Pos.tsx header)
  phone: { width: 390, height: 844 },
}

const LOGIN = { user: process.env.NATIVE_SHOT_USER ?? 'owner-acme', pass: process.env.NATIVE_SHOT_PASS ?? 'owner-password' }

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

const failures = []
const captured = []

const browser = await chromium.launch({ channel: 'chrome', headless: !HEADFUL })
const ctx = await browser.newContext({ viewport: VIEWPORTS.tablet, deviceScaleFactor: 1 })
const page = await ctx.newPage()
page.setDefaultTimeout(15000)

async function shot(name) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
  captured.push(name)
  console.log(`  shot: ${name}`)
}

/** Set outlet/theme/lang storage on the CURRENT origin, then (re)load /pos fresh. */
async function openPos({ vertical, theme = 'light', lang = 'en' }) {
  await page.evaluate(
    ([outletId, th, lg]) => {
      window.sessionStorage.setItem('native.console.outlet', outletId)
      window.localStorage.setItem('native.console.theme', th)
      window.localStorage.setItem('native.console.lang', lg)
    },
    [OUTLETS[vertical], theme, lang],
  )
  await page.goto(BASE + '/pos', { waitUntil: 'domcontentloaded' })
}

async function scenario(key, fn) {
  if (ONLY.length > 0 && !ONLY.includes(key)) return
  console.log(`scenario: ${key}`)
  try {
    await fn()
  } catch (err) {
    failures.push({ key, error: String(err).split('\n')[0] })
    await page.screenshot({ path: path.join(OUT, `${key}-FAILED.png`) }).catch(() => {})
    console.error(`  FAILED: ${key}: ${String(err).split('\n')[0]}`)
    // Hard-reset so a stuck overlay/modal never cascades into the next scenario.
    await page.goto(BASE, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await page.waitForTimeout(500)
  } finally {
    await page.emulateMedia({ media: 'screen' }).catch(() => {})
  }
}

const tile = (name) => page.getByText(name, { exact: true }).first()
const btn = (re) => page.getByRole('button', { name: re })
// data-testid first (added in redesign P2), aria/text as fallback for older builds
const tid = (id) => page.locator(`[data-testid="${id}"]`)

async function login() {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  const userField = page.locator('#username')
  const needsLogin = await userField.waitFor({ state: 'visible', timeout: 10000 }).then(() => true, () => false)
  if (needsLogin) {
    await userField.fill(LOGIN.user)
    await page.fill('#password', LOGIN.pass)
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'load' }).catch(() => {}),
      page.click('#kc-login').catch(() => page.click('button[type=submit], input[type=submit]')),
    ])
  }
  await page.waitForURL((u) => u.toString().startsWith(BASE) && !u.toString().includes('18080'), { timeout: 30000 })
  await page.waitForTimeout(2000)
  console.log('logged in:', page.url())
}

// Complete the cash leg of an open payment modal: Exact → Pay → wait for the receipt button.
async function payCashExact(receiptButtonRe) {
  await btn(/^Exact$/).first().click()
  await page.waitForTimeout(300)
  await btn(/^Pay( |$)/).last().click()
  await btn(receiptButtonRe).first().waitFor({ state: 'visible', timeout: 20000 })
  await page.waitForTimeout(500)
}

// ---------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------

await login()

// ---- restaurant: full walk-in flow (tablet · light) ----
await scenario('restaurant-flow', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'restaurant' })
  await tile('Nasi Goreng Special').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('restaurant-menu')

  // Modifier sheet (Nasi Goreng Special carries Spice level + Extras groups)
  await tile('Nasi Goreng Special').click()
  await page.getByText('Spice level').first().waitFor({ state: 'visible' })
  await page.waitForTimeout(400)
  await shot('restaurant-modifier')
  await page.getByText('Medium', { exact: true }).first().click()
  await btn(/Add to cart/).first().click()
  await page.waitForTimeout(500)

  // Two more items → order state
  await tile('Es Teh Manis').click()
  await page.waitForTimeout(300)
  await tile('Kopi Susu').click()
  await page.waitForTimeout(1200) // let the debounced quote land
  await shot('restaurant-order')

  // Payment — cash panel, then the digital (QRIS) panel
  await tid('pos-pay').click()
  await page.getByText('Quick cash').first().waitFor({ state: 'visible' })
  await page.waitForTimeout(400)
  await shot('restaurant-payment-cash')
  // Tender picker is a Segmented — its options are role="tab", not buttons.
  await page.getByRole('tab', { name: 'QRIS' }).first().click()
  await page.waitForTimeout(400)
  await shot('restaurant-payment-digital')

  // ONLINE panel (ADR 0036 Phase B3) — the sales-channel picker. Renders its empty state (with a
  // hint to add one in the dashboard) if the dev stack has no /channels seeded yet; either way the
  // shot captures the panel-open state the task asked for.
  await page.getByRole('tab', { name: 'Online' }).first().click()
  await page.waitForTimeout(400)
  await shot('restaurant-payment-online')

  await page.getByRole('tab', { name: 'Cash' }).first().click()
  await page.waitForTimeout(300)

  // Pay exact → receipt (+ print-emulated shot: the print-isolation tripwire)
  await payCashExact(/New order/)
  await shot('restaurant-receipt')
  await page.emulateMedia({ media: 'print' })
  await page.waitForTimeout(400)
  await shot('restaurant-receipt-print')
  await page.emulateMedia({ media: 'screen' })
  await btn(/New order/).first().click()
  await page.waitForTimeout(500)
})

// ---- restaurant: open bill → append → split → settle (tablet · light) ----
await scenario('restaurant-bills', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'restaurant' })
  await tile('Nasi Goreng Special').waitFor({ state: 'visible', timeout: 30000 })

  // Open a new bill on table T1
  await page.getByLabel('Open a new bill').first().click()
  await page.locator('#bill-guest-label').fill('Guest A')
  await page.getByText('T1', { exact: true }).first().click().catch(() => {})
  await btn(/^Open bill$/).first().click()
  await page.waitForTimeout(1200)
  await page.keyboard.press('Escape') // collapse the sheet if it auto-opened
  await page.waitForTimeout(400)

  // Append two lines in bill mode (server round-trips)
  await tile('Es Teh Manis').click()
  await page.waitForTimeout(900)
  await tile('Ayam Bakar').click()
  await page.waitForTimeout(1200)
  await shot('restaurant-bill-tabs')

  // Expand the bill sheet
  await page.getByLabel('View bill').first().click()
  await page.waitForTimeout(800)
  await shot('restaurant-bill-sheet')

  // Kitchen ticket (KOT) — the second print surface (#native-kot-print tripwire)
  await btn(/Kitchen ticket/).first().click()
  await page.waitForTimeout(600)
  await shot('restaurant-kot')
  await page.emulateMedia({ media: 'print' })
  await page.waitForTimeout(400)
  await shot('restaurant-kot-print')
  await page.emulateMedia({ media: 'screen' })
  // Scope the close to the KOT overlay — bare "Close" can match hidden buttons elsewhere.
  await page.locator('[aria-label="Kitchen ticket"] [aria-label="Close"]').first().click()
  await page.waitForTimeout(400)

  // Split mode: select the first line (the Split button's aria-label overrides its text)
  const splitBtn = btn(/Toggle split-by-item mode|^Split$/)
  await splitBtn.first().click()
  await page.waitForTimeout(400)
  await page.getByRole('checkbox').first().check()
  await page.waitForTimeout(400)
  await shot('restaurant-split')
  await splitBtn.first().click() // exit split mode
  await page.waitForTimeout(300)

  // Settle the whole bill so re-runs don't accumulate open bills
  await tid('bill-pay').click()
  await page.getByText('Quick cash').first().waitFor({ state: 'visible' })
  await page.waitForTimeout(400)
  await shot('restaurant-bill-payment')
  await payCashExact(/New order|Back to bills|Close/)
  await shot('restaurant-bill-receipt')
  await page.keyboard.press('Escape')
})

// ---- restaurant: dark + phone variants ----
await scenario('restaurant-dark', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'restaurant', theme: 'dark' })
  await tile('Nasi Goreng Special').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('restaurant-menu-dark')
  await tile('Es Teh Manis').click()
  await page.waitForTimeout(300)
  await tile('Kopi Susu').click()
  await page.waitForTimeout(1200)
  await shot('restaurant-order-dark')
  await openPos({ vertical: 'restaurant', theme: 'light' }) // reset theme
})

await scenario('restaurant-phone', async () => {
  await page.setViewportSize(VIEWPORTS.phone)
  await openPos({ vertical: 'restaurant' })
  await tile('Nasi Goreng Special').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('restaurant-menu-phone')
  await tile('Es Teh Manis').click()
  await page.waitForTimeout(300)
  await tile('Kopi Susu').click()
  await page.waitForTimeout(1200)
  await shot('restaurant-order-phone')
  await tid('pos-pay').click()
  await page.getByText('Quick cash').first().waitFor({ state: 'visible' })
  await page.waitForTimeout(400)
  await shot('restaurant-payment-phone')
  await btn(/Cancel/).first().click().catch(() => page.keyboard.press('Escape'))
})

// ---- carwash: full ticket flow (tablet · light) ----
await scenario('carwash-flow', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'carwash' })
  await tile('Premium Wash').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('carwash-catalog')

  await tile('Premium Wash').click()
  await page.getByLabel(/^Add Wax$/).first().click().catch(() => tile('Wax').click())
  await page.getByPlaceholder('e.g. Bay 1').fill('2')
  await page.getByPlaceholder('e.g. B 1234 XYZ').fill('B 2461 KJT')
  await page.locator('select').first().selectOption({ label: 'Budi' }).catch(() => {})
  await page.waitForTimeout(1200) // quote lands
  await shot('carwash-ticket')

  await tid('service-charge').click()
  await page.getByText('Quick cash').first().waitFor({ state: 'visible' })
  await page.waitForTimeout(400)
  await shot('carwash-payment')
  await payCashExact(/New ticket/)
  await shot('carwash-receipt')
  await page.emulateMedia({ media: 'print' })
  await page.waitForTimeout(400)
  await shot('carwash-receipt-print')
  await page.emulateMedia({ media: 'screen' })
  await btn(/New ticket/).first().click()
})

await scenario('carwash-dark', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'carwash', theme: 'dark' })
  await tile('Premium Wash').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('carwash-catalog-dark')
  await openPos({ vertical: 'carwash', theme: 'light' })
})

await scenario('carwash-phone', async () => {
  await page.setViewportSize(VIEWPORTS.phone)
  await openPos({ vertical: 'carwash' })
  await tile('Premium Wash').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('carwash-catalog-phone')
  await tile('Premium Wash').click()
  await page.waitForTimeout(1200)
  await shot('carwash-ticket-phone')
})

// ---- barbershop: catalog + ticket (payment modal is shared with carwash) ----
await scenario('barbershop-flow', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'barbershop' })
  await tile('Signature Cut').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('barbershop-catalog')

  await tile('Signature Cut').click()
  await page.getByLabel(/^Add Hair Wash$/).first().click().catch(() => tile('Hair Wash').click())
  await page.locator('select').first().selectOption({ label: 'Rudi' }).catch(() => {})
  await page.getByPlaceholder('e.g. Chair 1').fill('1').catch(() => {})
  await page.waitForTimeout(1200)
  await shot('barbershop-ticket')
})

await scenario('barbershop-dark', async () => {
  await page.setViewportSize(VIEWPORTS.tablet)
  await openPos({ vertical: 'barbershop', theme: 'dark' })
  await tile('Signature Cut').waitFor({ state: 'visible', timeout: 30000 })
  await page.waitForTimeout(800)
  await shot('barbershop-catalog-dark')
  await openPos({ vertical: 'barbershop', theme: 'light' })
})

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

await browser.close()

console.log(`\n${captured.length} shots → ${OUT}`)
if (failures.length > 0) {
  console.error(`\n${failures.length} scenario(s) FAILED:`)
  for (const f of failures) console.error(`  - ${f.key}: ${f.error}`)
  process.exit(1)
}
console.log('all scenarios passed')
