/**
 * backguard-smoke — end-to-end walk of the hardware-Back guard against a local dev server.
 * Forces the guard on in a plain browser via the sessionStorage dev override; the browser Back
 * button then drives the exact popstate path the Android WebView produces.
 *
 *   VITE_AUTH_MODE=dev npm run dev   (terminal 1, in frontend/console)
 *   node backguard-smoke.mjs         (terminal 2)
 */
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'

const COMPANY = {
  companyId: 'c0000000-0000-0000-0000-000000000001',
  name: 'Warung Kemang',
  baseCurrency: 'IDR',
  defaultLanguage: 'id',
  businessId: 'b0000000-0000-0000-0000-000000000001',
  divisionId: null,
  planTier: 'FULL',
}

let failures = 0
function check(name, cond, extra = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else {
    failures++
    console.log(`  FAIL  ${name} ${extra}`)
  }
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
await ctx.addInitScript(
  ([company]) => {
    localStorage.setItem('native.console.sessions', JSON.stringify([company]))
    localStorage.setItem('native.console.theme', 'light')
    localStorage.setItem('native.console.lang', 'id')
    sessionStorage.setItem('backGuardDev', '1') // force the guard on in a plain browser
  },
  [COMPANY],
)
await ctx.route('**/api/v1/**', (route) => {
  // List endpoints must answer with arrays or components crash on .find/.map (which unmounts the
  // React root and invalidates the walk) — everything else tolerates an empty object.
  const p = new URL(route.request().url()).pathname
  const isList = /\/(outlets|org-units|users|invoices|bills|closes|payslips|channels)$/.test(p)
  return route.fulfill({ status: 200, contentType: 'application/json', body: isList ? '[]' : '{}' })
})
const page = await ctx.newPage()
page.on('pageerror', (e) => console.log('PAGEERROR', String(e.message).slice(0, 200)))

const dialogTitle = (t) => page.getByText(t, { exact: true })
const visible = async (loc) => await loc.isVisible().catch(() => false)
const path = () => new URL(page.url()).pathname

// ── 1. Load home, client-navigate to a second page ───────────────────────────
await page.goto(`${BASE}/`, { waitUntil: 'load' })
await page.waitForTimeout(1500)
check('home loaded', path() === '/', path())

//

await page.getByRole('link', { name: 'Laporan' }).first().click({ timeout: 8000 }).catch(async () => {
  // fall back: tab labels differ per persona — navigate via history push instead
  await page.evaluate(() => window.history.pushState({ usr: null, key: 'smoke1', idx: (window.history.state?.idx ?? 0) + 1 }, '', '/statements/income'))
  await page.evaluate(() => window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state })))
})
await page.waitForTimeout(1000)
const secondPath = path()
check('client-navigated to a second page', secondPath !== '/', secondPath)

// ── 2. Back on a page → leave dialog appears, URL unchanged ─────────────────
await page.goBack()
await page.waitForTimeout(600)
check('leave dialog shown', await visible(dialogTitle('Keluar dari halaman ini?')))
check('URL unchanged while dialog open', path() === secondPath, path())

// ── 3. Batal keeps you on the page ──────────────────────────────────────────
await page.getByRole('button', { name: 'Batal' }).click()
await page.waitForTimeout(400)
check('cancel closes dialog', !(await visible(dialogTitle('Keluar dari halaman ini?'))))
check('still on the page after cancel', path() === secondPath, path())

// ── 4. Back again works after cancel (stack balanced) → confirm leaves ──────
await page.goBack()
await page.waitForTimeout(600)
check('leave dialog shown again after cancel', await visible(dialogTitle('Keluar dari halaman ini?')))
await page.getByRole('button', { name: 'Keluar', exact: true }).click()
await page.waitForTimeout(800)
check('confirm actually leaves to home', path() === '/', path())
check('no dialog after confirm', !(await visible(dialogTitle('Keluar dari halaman ini?'))))

// ── 5. Back at home → exit dialog; back AGAIN while open = cancel ───────────
await page.goBack()
await page.waitForTimeout(600)
check('exit dialog at home', await visible(dialogTitle('Keluar aplikasi?')))
await page.goBack()
await page.waitForTimeout(600)
check('second back while dialog open cancels it', !(await visible(dialogTitle('Keluar aplikasi?'))))
check('still at home', path() === '/', path())

// ── 6. Overlay: More sheet closes on Back, no dialog ────────────────────────
const more = page.getByRole('button', { name: 'Lainnya', exact: true })
if (await visible(more)) {
  await more.click()
  await page.waitForTimeout(600)
  await page.goBack()
  await page.waitForTimeout(600)
  const sheetGone = !(await visible(page.getByRole('dialog', { name: 'Lainnya' })))
  check('back closes the More sheet', sheetGone)
  check('no confirm dialog when closing overlay', !(await visible(dialogTitle('Keluar aplikasi?'))))
  check('still at home after overlay dismiss', path() === '/', path())
} else {
  console.log('  SKIP  More tab not visible (persona) — overlay walk skipped')
}

// ── 6b. Overlay closed via UI → deferred unwind keeps the stack balanced ────
if (await visible(more)) {
  await more.click()
  await page.waitForTimeout(600)
  await page.getByRole('button', { name: 'Tutup', exact: true }).first().click({ timeout: 4000 })
    .catch(() => page.keyboard.press('Escape'))
  await page.waitForTimeout(600)
  await page.goBack()
  await page.waitForTimeout(600)
  check('back after UI-close is not swallowed (exit dialog)', await visible(dialogTitle('Keluar aplikasi?')))
  await page.getByRole('button', { name: 'Batal' }).click()
  await page.waitForTimeout(400)
}

// ── 7. Exit confirm in browser → fallback hint (no minimize bridge) ─────────
await page.goBack()
await page.waitForTimeout(600)
check('exit dialog again', await visible(dialogTitle('Keluar aplikasi?')))
await page.getByRole('button', { name: 'Keluar', exact: true }).click()
await page.waitForTimeout(600)
check('fallback hint shown', await visible(dialogTitle('Tekan kembali sekali lagi untuk keluar')))
await page.waitForTimeout(2600)
check('hint auto-hides', !(await visible(dialogTitle('Tekan kembali sekali lagi untuk keluar'))))

// ── 8. Guard invariant restored after the fallback window ───────────────────
await page.goBack()
await page.waitForTimeout(600)
check('guard re-arms after fallback window', await visible(dialogTitle('Keluar aplikasi?')))

await browser.close()
console.log(failures === 0 ? '\nSMOKE OK' : `\nSMOKE FAILED (${failures})`)
process.exit(failures === 0 ? 0 : 1)
