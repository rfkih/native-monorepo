// Capture phone-sized screenshots of the LIVE UAT console for the Play Store listing.
//
// The console runs in OIDC mode against Keycloak on the funnel, so this drives the real login on the
// funnel origin (where the redirect URIs are registered) with system Chrome via playwright-core, then
// screenshots a few key screens at a phone viewport (360×720 @3x = 1080×2160, a Play-legal 9:18 ratio).
//
// Credentials come from the environment so the password is never written to disk or a script:
//   PowerShell:
//     $env:SHOT_USER = "<uat-username>"; $env:SHOT_PASS = "<uat-password>"
//     node scripts/render-store-shots.mjs
// Optional: SHOT_BASE_URL (default the UAT funnel), SHOT_ROUTES (comma-separated paths).
// Output: store-assets/shots/*.png  — pick the best 2–8 for the listing.

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const outDir = join(app, 'store-assets', 'shots')
mkdirSync(outDir, { recursive: true })

const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

const BASE = process.env.SHOT_BASE_URL ?? 'https://a8.tailbf9662.ts.net:8443'
const USER = process.env.SHOT_USER
const PASS = process.env.SHOT_PASS
if (!USER || !PASS) {
  console.error('render-store-shots: set SHOT_USER and SHOT_PASS (a UAT login) in the environment first.')
  process.exit(1)
}

// Screens worth showing a Play visitor. Reachable set depends on the login's role; unreachable ones
// just redirect to the login's home and are skipped by the "did the URL land?" check below.
const ROUTES = (process.env.SHOT_ROUTES ?? '/,/pos,/statements/income,/statements/balance-sheet,/people')
  .split(',')
  .map((r) => r.trim())
  .filter(Boolean)

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const executablePath = CHROME_CANDIDATES.find((p) => {
  try {
    readFileSync(p)
    return true
  } catch {
    return false
  }
})

const slug = (r) => (r === '/' ? 'home' : r.replace(/^\//, '').replace(/\//g, '-'))
const sleep = (ms) => new Promise((res) => setTimeout(res, ms))

const browser = await chromium.launch({ executablePath, headless: true })
try {
  const ctx = await browser.newContext({
    viewport: { width: 360, height: 720 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
  })
  const page = await ctx.newPage()

  // --- log in via Keycloak -----------------------------------------------------------------------
  console.log(`[shots] login at ${BASE}/login as ${USER}`)
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  // Keycloak's standard login form.
  await page.waitForSelector('#username', { timeout: 30000 })
  await page.fill('#username', USER)
  await page.fill('#password', PASS)
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'domcontentloaded' }).catch(() => {}),
    page.click('#kc-login, input[type=submit], button[type=submit]'),
  ])
  // Back on the app: wait until we're off any Keycloak page and the SPA has painted.
  await page.waitForFunction(() => !location.host.includes('auth') && !document.querySelector('#kc-login'), {
    timeout: 30000,
  }).catch(() => {})
  await sleep(2500)
  if (await page.$('#username')) {
    console.error('[shots] still on the login form — check SHOT_USER/SHOT_PASS. Aborting.')
    process.exit(2)
  }
  console.log('[shots] authenticated, landed at', page.url())

  // --- capture each route ------------------------------------------------------------------------
  for (const route of ROUTES) {
    try {
      await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle', timeout: 30000 })
    } catch {
      await page.goto(`${BASE}${route}`, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {})
    }
    await sleep(2200) // let charts/data render
    const landed = new URL(page.url()).pathname
    const file = join(outDir, `${slug(route)}.png`)
    await page.screenshot({ path: file, type: 'png' })
    const note = landed === route || (route === '/' && landed === '/') ? '' : `  (redirected → ${landed})`
    console.log(`[shots] ${route} → ${file}${note}`)
  }

  console.log('\n[shots] done. Review store-assets/shots/ and keep the best 2–8 for the listing.')
} finally {
  await browser.close()
}
