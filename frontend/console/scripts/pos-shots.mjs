// POS visual verification with live restaurant-service: seed menu, build an order,
// capture the 3b states (menu grid, modifier UI, summary bar).
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const base = process.argv[2] ?? 'http://127.0.0.1:5199'
mkdirSync('shots', { recursive: true })

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const DEV_SESSION = JSON.stringify({
  companyId: '11111111-1111-1111-1111-111111111111',
  name: 'Sudirman Group',
  baseCurrency: 'IDR',
  defaultLanguage: 'en',
  businessId: '22222222-2222-2222-2222-222222222222',
  actor: 'ayu@warungsudirman.id',
})

const browser = await chromium.launch({ executablePath: CHROME })
const ctx = await browser.newContext({ viewport: { width: 834, height: 1194 } })
await ctx.addInitScript((s) => window.localStorage.setItem('native.console.session', s), DEV_SESSION)
const page = await ctx.newPage()

await page.goto(base + '/pos', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1200)

// Seed the sample menu if the empty state is showing
const seed = page.getByRole('button', { name: 'Load sample menu' })
if (await seed.isVisible().catch(() => false)) {
  await seed.click()
  await page.waitForTimeout(4000)
}
await page.screenshot({ path: 'shots/pos-menu.png' })
console.log('shot: pos-menu')

// Tap a few tiles to build an order (a modifier sheet may open for option items)
const tiles = page.locator('button:has(.tnum), [role="button"]:has(.tnum)')
const count = await tiles.count()
console.log('tiles found:', count)
for (let i = 0; i < Math.min(3, count); i++) {
  await tiles.nth(i).click().catch(() => {})
  await page.waitForTimeout(700)
  // If a modifier/option sheet opened, screenshot it once then confirm via the "Add" button
  const addBtn = page.getByRole('button', { name: /add/i }).first()
  if (await addBtn.isVisible().catch(() => false)) {
    if (i === 0) {
      await page.screenshot({ path: 'shots/pos-modifier.png' })
      console.log('shot: pos-modifier')
    }
    await addBtn.click().catch(() => {})
    await page.waitForTimeout(500)
  }
}
await page.waitForTimeout(800)
await page.screenshot({ path: 'shots/pos-order.png' })
console.log('shot: pos-order')

await browser.close()
console.log('done')
