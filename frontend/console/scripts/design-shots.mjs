// Design-verification screenshots — playwright-core driving the system Chrome.
// Usage: node scripts/design-shots.mjs [baseUrl]
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const base = process.argv[2] ?? 'http://127.0.0.1:5199'
const outDir = 'shots'
mkdirSync(outDir, { recursive: true })

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

// Dev-mode session injection: binds a fake company so the authenticated shell renders.
// Backend queries fail → the pages show their (designed) error/empty states, which is
// enough to verify the chrome: sidebar, header, page scaffolding, POS frame.
const DEV_SESSION = JSON.stringify({
  companyId: '11111111-1111-1111-1111-111111111111',
  name: 'Sudirman Group',
  baseCurrency: 'IDR',
  defaultLanguage: 'en',
  businessId: '22222222-2222-2222-2222-222222222222',
  actor: 'ayu@warungsudirman.id',
})

const pages = [
  { name: 'landing', path: '/', width: 1440, height: 1024 },
  { name: 'signup', path: '/signup', width: 1440, height: 1024 },
  { name: 'signup-phone', path: '/signup', width: 390, height: 844 },
  { name: 'dashboard', path: '/', width: 1440, height: 1024, session: true },
  { name: 'income', path: '/statements/income', width: 1440, height: 1024, session: true },
  { name: 'balance', path: '/statements/balance-sheet', width: 1440, height: 1024, session: true },
  { name: 'team', path: '/team', width: 1440, height: 1024, session: true },
  { name: 'pos-tablet', path: '/pos', width: 834, height: 1194, session: true },
  { name: 'pos-phone', path: '/pos', width: 390, height: 844, session: true },
]

const browser = await chromium.launch({ executablePath: CHROME })
for (const p of pages) {
  const ctx = await browser.newContext({ viewport: { width: p.width, height: p.height } })
  if (p.session) {
    await ctx.addInitScript((session) => {
      window.localStorage.setItem('native.console.session', session)
    }, DEV_SESSION)
  }
  const page = await ctx.newPage()
  try {
    await page.goto(base + p.path, { waitUntil: 'networkidle', timeout: 20000 })
  } catch {
    // networkidle can hang on polling queries — settle for load + a beat
    await page.waitForTimeout(1500)
  }
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${outDir}/${p.name}.png`, fullPage: false })
  console.log(`shot: ${p.name}`)
  await ctx.close()
}
await browser.close()
console.log('done')
