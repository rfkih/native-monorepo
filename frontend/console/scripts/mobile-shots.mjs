/**
 * mobile-shots — visual verification of the Native Console Android phone re-fit (390×844).
 *
 * Runs against a LOCAL `npm run dev` server in dev-auth mode with every /api/v1/** call
 * intercepted and answered from fixtures below (no backend needed): seeds a dev company
 * session in localStorage, then walks every phone screen in two passes (light/en and
 * dark/id), including the More screen and the claim-decision sheet.
 *
 *   npm run dev        (terminal 1)
 *   node scripts/mobile-shots.mjs [outDir]   (terminal 2)
 *
 * SHOT_ONLY=screens,more,pos,stocktake,laporan,inventory,menu,bills (comma list) restricts the walk to those sections — the
 * whole pass is a couple of minutes, and a redesign of one screen only needs its own section.
 * SHOT_FULL=1 makes the `screens` pass capture full scroll height (review a long screen whole).
 *
 * Dev-auth grants all four roles, so the MANAGER tab bar renders everywhere; the
 * employee-only tab set differs only in tab items (same component) and is covered by the
 * shouldMountTabBar/persona unit + manual matrix instead.
 */
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'
import { COMPANY, PARKED_ORDER, resolveFixture } from './mobile-fixtures.mjs'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const OUT = process.argv[2] ?? 'shots-mobile'
const ONLY = (process.env.SHOT_ONLY ?? '').split(',').map((s) => s.trim()).filter(Boolean)
const want = (section) => ONLY.length === 0 || ONLY.includes(section)
// SHOT_FULL=1 captures the whole scroll height of each screen instead of the first viewport —
// for reviewing a long home against its design, not for the matrix.
const FULL = process.env.SHOT_FULL === '1'

/** The emptied deck must read ZERO on both the due figure and the Charge label. */
async function assertEmptiedDeck(page, passName, where) {
  const due = (await page.getByTestId('pos-dock-due').textContent())?.trim() ?? ''
  const pay = (await page.getByTestId('pos-pay').textContent())?.trim() ?? ''
  if (!/^(Rp|IDR)\s?0$/.test(due)) throw new Error(`[${passName}] ${where}: due still shows "${due}"`)
  if (!/(Rp|IDR)\s?0$/.test(pay)) throw new Error(`[${passName}] ${where}: Charge label still shows "${pay}"`)
}

// ── Walk ─────────────────────────────────────────────────────────────────────

const SCREENS = [
  ['home', '/'],
  ['income', '/statements/income'],
  ['team', '/team'],
  ['inbox', '/expenses'],
  ['close', '/close'],
  ['ar-aging', '/ar/aging'],
  ['ap-aging', '/ap/aging'],
  ['invoices', '/invoices'],
  ['bills', '/bills'],
  ['me-home', '/me'],
  ['me-payslips', '/me/payslips'],
  ['me-timeoff', '/me/timeoff'],
  ['me-claims', '/me/expenses'],
]

const browser = await chromium.launch({ channel: 'chrome', headless: true })

for (const pass of [
  { name: 'light-en', theme: 'light', lang: 'en', moreLabel: 'More', ordersLabel: 'Orders',
    parkedLabel: 'Parked orders',
    lineChart: 'Line chart', tabs: { bs: 'Balance', cf: 'Cash flow', exp: 'Expenses' } },
  { name: 'dark-id', theme: 'dark', lang: 'id', moreLabel: 'Lainnya', ordersLabel: 'Pesanan',
    parkedLabel: 'Pesanan tertahan',
    lineChart: 'Grafik garis', tabs: { bs: 'Neraca', cf: 'Arus kas', exp: 'Biaya' } },
]) {
  const dir = `${OUT}/${pass.name}`
  mkdirSync(dir, { recursive: true })
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  await ctx.addInitScript(
    ([company, theme, lang]) => {
      // Runtime config beats import.meta.env (lib/config.ts), so an untracked
      // .env.development.local that forces oidc cannot land the walk on the marketing page.
      window.__NATIVE_CONFIG__ = { authMode: 'dev' }
      localStorage.setItem('native.console.sessions', JSON.stringify([company]))
      localStorage.setItem('native.console.theme', theme)
      localStorage.setItem('native.console.lang', lang)
    },
    [COMPANY, pass.theme, pass.lang],
  )
  await ctx.route('**/api/v1/**', async (route) => {
    const fx = resolveFixture(route.request().url(), route.request())
    // A NUMERIC status is an HTTP answer (404, 500); domain rows carry string statuses ('ACTIVE').
    if (fx && typeof fx.status === 'number') return route.fulfill({ status: fx.status, contentType: 'application/json', body: '{}' })
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fx) })
  })
  const page = await ctx.newPage()
  page.on('pageerror', (e) => console.log(`[${pass.name}] PAGEERROR`, String(e.message).slice(0, 160)))

  if (want('screens')) for (const [name, path] of SCREENS) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    await page.screenshot({ path: `${dir}/${name}.png`, fullPage: FULL })
    console.log(`[${pass.name}] ${name} ok (${page.url().replace(BASE, '') || '/'})`)
  }

  if (want('more')) {
  // More SCREEN (manager persona) — a route since ADR 0078, not a sheet.
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1000)
  // A LINK now, not a button — More is a routed screen since ADR 0078.
  await page.getByRole('link', { name: pass.moreLabel, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${dir}/more-screen.png` })
  console.log(`[${pass.name}] more-screen ok`)

  // Claim decision sheet
  await page.goto(`${BASE}/expenses`, { waitUntil: 'load' })
  await page.waitForTimeout(1200)
  await page.getByText('Rina Puspita').first().click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.screenshot({ path: `${dir}/decision-sheet.png` })
  console.log(`[${pass.name}] decision-sheet ok`)

  // Expanded payslip (sign-flip lines + Cetak)
  await page.goto(`${BASE}/me/payslips`, { waitUntil: 'load' })
  await page.waitForTimeout(1200)
  // The row label is `periodLabel(period, locale)` ("July 2026" / "Juli 2026"), not the raw
  // `2026-07` this used to match — so it broke in BOTH passes the moment the label was localised.
  // Click the first payslip row instead: locale-agnostic, and it is the row we want either way.
  await page.locator('button:has-text("2026")').first().click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.screenshot({ path: `${dir}/payslip-open.png`, fullPage: true })
  console.log(`[${pass.name}] payslip-open ok`)
  }

  // ── Stock opname (Native Opname Stok) ──────────────────────────────────────
  // "Filled in is not checked": the shots have to show a row LEAVING pending both ways — the check
  // mark and a typed count — the count sheet with its own keypad, the summary the POST comes back
  // as, the variance guard writing out its reasons on the ADR 0068 incident figure, and the
  // discard confirm naming how many rows would be lost. Labels match either language.
  if (want('stocktake')) {
    const digit = (d) => page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) })
    const decimal = () => page.getByRole('button', { name: /^(Pemisah desimal|Decimal separator)$/ })
    const typeCount = async (keys) => {
      for (const k of keys) {
        await (k === ',' ? decimal() : digit(k)).click({ timeout: 8000 })
        await page.waitForTimeout(120)
      }
    }
    const openOpname = async () => {
      await page.getByRole('button', { name: /^(Opname stok|Stocktake)$/ }).click({ timeout: 8000 })
      await page.waitForTimeout(1400)
    }
    // The row's figure button is named "<item>: <value> <unit>, …" (the count is IN the label).
    const openSheetFor = async (name) => {
      await page.getByRole('button', { name: new RegExp(`^${name}.*: `) }).click({ timeout: 8000 })
      await page.waitForTimeout(700)
    }

    await page.goto(`${BASE}/`, { waitUntil: 'load' })
    await page.waitForTimeout(1000)
    await page.getByRole('link', { name: pass.moreLabel, exact: true }).click({ timeout: 8000 })
    await page.waitForTimeout(600)
    await openOpname()
    await page.screenshot({ path: `${dir}/opname-count.png` })
    console.log(`[${pass.name}] opname-count ok`)

    // Three rows checked by the mark alone — the progress strip is the point of this shot.
    const marks = page.getByRole('button', { name: /^(Tandai|Mark) / })
    for (const i of [1, 2, 3]) {
      await marks.nth(i).click({ timeout: 8000 })
      await page.waitForTimeout(150)
    }
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${dir}/opname-progress.png` })
    console.log(`[${pass.name}] opname-progress ok`)

    await openSheetFor('Daging Kebab')
    await page.screenshot({ path: `${dir}/opname-sheet.png` })
    console.log(`[${pass.name}] opname-sheet ok`)
    await typeCount(['3', ',', '3', '2'])
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${dir}/opname-sheet-typed.png` })
    console.log(`[${pass.name}] opname-sheet-typed ok`)
    await page.getByTestId('stocktake-count-save').click({ timeout: 8000 })
    await page.waitForTimeout(600)

    await openSheetFor('Keju Mozarella')
    await typeCount(['2', ',', '2', '8'])
    await page.getByTestId('stocktake-count-save').click({ timeout: 8000 })
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${dir}/opname-changed.png` })
    console.log(`[${pass.name}] opname-changed ok`)

    await page.getByTestId('stocktake-submit').click({ timeout: 8000 })
    await page.waitForTimeout(1200)
    await page.screenshot({ path: `${dir}/opname-summary.png` })
    console.log(`[${pass.name}] opname-summary ok`)
    await page.getByRole('button', { name: /^(Selesai|Done)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(600)

    // The ADR 0068 incident, typed as it happened: 2 640 (kg field) against 3,411 kg in the system.
    await openOpname()
    await openSheetFor('Daging Kebab')
    await typeCount(['2', '6', '4', '0'])
    await page.getByTestId('stocktake-count-save').click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await page.getByTestId('stocktake-submit').click({ timeout: 8000 })
    await page.waitForTimeout(800)
    await page.screenshot({ path: `${dir}/opname-guard.png` })
    console.log(`[${pass.name}] opname-guard ok`)
    await page.getByTestId('stocktake-variance-recount').click({ timeout: 8000 })
    await page.waitForTimeout(500)

    // Header Back with a worked row — the confirm, not a silent discard. `.last()`: the More
    // screen underneath has a Back of its own, and the opname host renders after it.
    await page.getByRole('button', { name: /^(Kembali|Back)$/ }).last().click({ timeout: 8000 })
    await page.waitForTimeout(600)
    await page.screenshot({ path: `${dir}/opname-discard.png` })
    console.log(`[${pass.name}] opname-discard ok`)
    await page.getByTestId('stocktake-discard-leave').click({ timeout: 8000 })
    await page.waitForTimeout(500)
  }

  // Laporan (Native Laporan, ADR 0080) — gated like every other section since the opname walk.
  if (want('laporan')) {
    // ── Laporan: the three statements as one phone screen (Native Laporan) ─────
    // The chart IS the period control — a tapped column moves the whole report — so the shots pair
    // the first tab with a month change and the line variant, then the two sheets, then each tab.
    await page.goto(`${BASE}/statements/income`, { waitUntil: 'load' })
    await page.waitForTimeout(1800)
    await page.screenshot({ path: `${dir}/laporan-pnl.png`, fullPage: true })
    console.log(`[${pass.name}] laporan-pnl ok`)
    // Chart columns are the only pressed-state buttons that carry a title (month · value).
    await page.locator('button[title][aria-pressed="false"]').nth(2).click({ timeout: 8000 })
    await page.waitForTimeout(1200)
    await page.screenshot({ path: `${dir}/laporan-pnl-month.png` })
    console.log(`[${pass.name}] laporan-pnl-month ok`)
    await page.getByRole('button', { name: pass.lineChart, exact: true }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${dir}/laporan-pnl-line.png` })
    console.log(`[${pass.name}] laporan-pnl-line ok`)
    // Drill-down sheet from the hero figure; BACK closes it (ADR 0075), not a tap on the scrim.
    await page.getByTestId('laporan-hero').click({ timeout: 8000 })
    await page.waitForTimeout(800)
    await page.screenshot({ path: `${dir}/laporan-detail-sheet.png` })
    console.log(`[${pass.name}] laporan-detail-sheet ok`)
    await page.goBack()
    await page.waitForTimeout(600)
    // The tapped month and the line chart travel with the tab (they live in the URL) — the shot of
    // each tab must show the SAME earlier month, or a tab switch has silently reset the reader.
    for (const [key, label] of Object.entries(pass.tabs)) {
      await page.getByRole('tab', { name: label, exact: true }).click({ timeout: 8000 })
      // The failed month (cash flow, March) settles after the query client's one retry.
      await page.waitForTimeout(key === 'cf' ? 3200 : 1800)
      if (key === 'cf' && (await page.locator('button[data-failed]').count()) !== 1) throw new Error('cash-flow March should show as a failed month')
      const url = page.url().replace(BASE, '')
      if (!/period=\d{4}-\d{2}/.test(url) || !url.includes('chart=line')) throw new Error(`tab ${key} lost the selection: ${url}`)
      await page.screenshot({ path: `${dir}/laporan-${key}.png`, fullPage: true })
      console.log(`[${pass.name}] laporan-${key} ok (${url})`)
    }
    // Export sheet — the title row's action.
    await page.getByTestId('laporan-export').click({ timeout: 8000 })
    await page.waitForTimeout(800)
    await page.screenshot({ path: `${dir}/laporan-export-sheet.png` })
    console.log(`[${pass.name}] laporan-export-sheet ok`)
    await page.goBack()
    await page.waitForTimeout(400)
  }

  // ── Inventory (Native Persediaan, ADR 0081) ────────────────────────────────
  // The catalog reads in days: the shots have to show the value hero, the chips with counts, a
  // row's days-left figure in its class colour, the Terima keypad sheet from a row, the item's
  // own screen, the form as a screen, the history and its lines (8,4 kg — not 8.400 g), and the
  // owner's inventory-method page with the catalog value handed over.
  if (want('inventory')) {
    const shot = async (name) => {
      await page.screenshot({ path: `${dir}/${name}.png` })
      console.log(`[${pass.name}] ${name} ok`)
    }
    await page.goto(`${BASE}/inventory`, { waitUntil: 'load' })
    await page.waitForTimeout(1400)
    await shot('inventory-catalog')
    await page.getByRole('button', { name: /^(Hampir habis|Running low)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await shot('inventory-catalog-low')
    if (!page.url().includes('filter=low')) throw new Error('the filter chip must live in the URL (N5)')
    await page.getByRole('button', { name: /^(Hampir habis|Running low)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(300)

    // Terima from the row — the one action that stays on the list.
    await page.getByRole('button', { name: /^(Terima|Receive) Daging Kebab/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    for (const d of ['4', ',', '5']) {
      await page.getByRole('button', { name: d === ',' ? /^(Pemisah desimal|Decimal separator)$/ : new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 })
      await page.waitForTimeout(120)
    }
    await page.waitForTimeout(300)
    await shot('inventory-receive')
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)

    // The item's screen — a route: Back must pop to the list, filter and all.
    await page.getByRole('button', { name: /^Daging Kebab/ }).first().click({ timeout: 8000 })
    await page.waitForTimeout(900)
    if (!/\/inventory\/daging-kebab$/.test(page.url())) throw new Error(`detail is a route, got ${page.url()}`)
    await shot('inventory-detail')
    await page.getByRole('button', { name: /^(Atur jumlah|Set quantity)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    await shot('inventory-set')
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(400)
    await page.getByRole('button', { name: /^(Ubah barang|Edit item)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(900)
    await shot('inventory-edit')

    await page.goto(`${BASE}/inventory/saus-pouch`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    await shot('inventory-detail-pack')
    await page.getByRole('button', { name: /(Ubah satuannya|Change the unit)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(900)
    await page.getByRole('textbox').first().fill('1000')
    await page.waitForTimeout(300)
    await shot('inventory-convert')

    await page.goto(`${BASE}/inventory/new`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    await shot('inventory-new')

    await page.goto(`${BASE}/inventory/history`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    await shot('inventory-history')
    await page.getByRole('button', { name: /2026/ }).first().click({ timeout: 8000 })
    await page.waitForTimeout(900)
    await shot('inventory-history-lines')

    // The owner's door: the value hero hands the catalog figure to the method page.
    await page.goto(`${BASE}/inventory`, { waitUntil: 'load' })
    await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /(belum berbiaya|no cost — counted)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(900)
    await shot('inventory-method')
    await page.getByRole('button', { name: /^(Aktifkan persediaan perpetual|Activate perpetual inventory)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await shot('inventory-method-form')
  }

  if (want('pos')) {
  // ── POS: the phone bill deck (Native Till Android v2) ──────────────────────
  // The deck is the redesign's whole thesis — the bill lives on screen instead of inside a sheet —
  // so the shots have to show it in all four states it can be in, for BOTH data owners.
  await page.goto(`${BASE}/pos`, { waitUntil: 'load' })
  await page.waitForTimeout(1600)
  await page.screenshot({ path: `${dir}/pos-deck-empty.png` })
  console.log(`[${pass.name}] pos-deck-empty ok`)

  // Ring three items — the catalog stays visible the whole time, which is the point.
  for (const item of ['Nasi Goreng Spesial', 'Es Teh Manis', 'Kopi Susu Gula Aren']) {
    await page.getByRole('button', { name: new RegExp(item) }).first().click({ timeout: 8000 })
    await page.waitForTimeout(350)
  }
  // The live quote is debounced; shooting before it settles catches the dimmed pending figure.
  await page.waitForTimeout(1600)
  // Clicking tiles auto-scrolls the catalog — put it back at the top so the shot shows the grid
  // the way a cashier opens it.
  await page.mouse.move(195, 350)
  await page.mouse.wheel(0, -3000)
  await page.waitForTimeout(500)
  await page.screenshot({ path: `${dir}/pos-deck-peek.png` })
  console.log(`[${pass.name}] pos-deck-peek ok`)

  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${dir}/pos-deck-expanded.png` })
  console.log(`[${pass.name}] pos-deck-expanded ok`)

  // Ring, then take everything off again: the last non-empty quote used to linger as placeholder
  // data once the query was disabled, so the emptied deck kept showing the old total (and
  // "Charge Rp 45.000") under an empty list. The due figure must read zero — this is asserted,
  // not just photographed, because a stale amount is invisible unless you know what it should be.
  for (const item of ['Nasi Goreng Spesial', 'Es Teh Manis', 'Kopi Susu Gula Aren']) {
    await page.getByRole('button', { name: new RegExp(`^(Decrease quantity of|Kurangi jumlah) ${item}$`) }).click({ timeout: 8000 })
    await page.waitForTimeout(250)
  }
  // Read IMMEDIATELY (well inside the quote's 400 ms debounce): the total has to drop the instant
  // the last line goes, not once the debounced query settles. Both halves of the symptom are
  // checked — the due figure and the Charge label that repeats it.
  await page.waitForTimeout(100)
  await assertEmptiedDeck(page, pass.name, 'emptied deck')
  await page.screenshot({ path: `${dir}/pos-deck-emptied.png` })
  console.log(`[${pass.name}] pos-deck-emptied ok`)

  // Collapse the (still expanded) deck — its scrim covers the catalog — then ring the three again
  // for the bill shots below.
  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(500)
  for (const item of ['Nasi Goreng Spesial', 'Es Teh Manis', 'Kopi Susu Gula Aren']) {
    await page.getByRole('button', { name: new RegExp(item) }).first().click({ timeout: 8000 })
    await page.waitForTimeout(350)
  }
  await page.waitForTimeout(1200)

  // Same component, other data owner: a partially-paid open bill (BillDetail renders the deck).
  // The switcher lives in the till menu now (and, in bill mode, as the deck's first chip).
  await page.getByTestId('pos-till-menu').click({ timeout: 8000 })
  await page.waitForTimeout(400)
  await page.getByRole('menuitem', { name: pass.ordersLabel, exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(600)
  await page.getByText('Meja 07').first().click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  await page.screenshot({ path: `${dir}/pos-bill-peek.png` })
  console.log(`[${pass.name}] pos-bill-peek ok`)

  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${dir}/pos-bill-expanded.png` })
  console.log(`[${pass.name}] pos-bill-expanded ok`)

  // The same stale-total bug through its second door: a RESUMED parked order carries its own
  // breakdown and total, which the quote mask cannot reach. Resume the fixture's parked order from
  // the till menu, take its lines off one by one, and the deck must read zero all the same.
  await page.goto(`${BASE}/pos`, { waitUntil: 'load' })
  await page.waitForTimeout(1600)
  // On the phone the tray is the header's "Incoming" button (PosPhoneHeader), not a menu item.
  await page.getByTestId('pos-parked').click({ timeout: 8000 })
  await page.waitForTimeout(800)
  await page.getByRole('dialog', { name: pass.parkedLabel }).locator('li button').first().click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 })
  await page.waitForTimeout(700)
  for (const item of PARKED_ORDER.lines.map((l) => l.name)) {
    await page.getByRole('button', { name: new RegExp(`^(Decrease quantity of|Kurangi jumlah) ${item}$`) }).click({ timeout: 8000 })
    await page.waitForTimeout(250)
  }
  await page.waitForTimeout(100)
  await assertEmptiedDeck(page, pass.name, 'emptied resumed order')
  await page.screenshot({ path: `${dir}/pos-resumed-emptied.png` })
  console.log(`[${pass.name}] pos-resumed-emptied ok`)
  }

  if (want('bills')) {
    // ADR 0084 — the phone "Tagihan baru" form refuses a bill the books cannot defend.
    const shot = async (name) => {
      await page.screenshot({ path: `${dir}/${name}.png` })
      console.log(`[${pass.name}] ${name} ok`)
    }
    await page.goto(`${BASE}/bills/new`, { waitUntil: 'load' })
    await page.waitForTimeout(1400)
    await shot('bills-new')
    // Vendor list → pick Sumber Pangan (terms 30 preselect).
    await page.getByRole('button', { name: /(Pilih vendor|Choose a vendor)/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await shot('bills-new-vendors')
    await page.getByRole('button', { name: /CV Sumber Pangan Jaya/ }).first().click({ timeout: 8000 })
    await page.waitForTimeout(500)
    // A duplicate number for that vendor lights the red line.
    const invoice = page.getByPlaceholder('INV/2026/09/…')
    await invoice.fill('INV/2026/08/2214')
    await page.waitForTimeout(700)
    await shot('bills-new-duplicate')
    await invoice.fill('INV/2026/09/2290')
    // A line: name, qty 24, price 34.500 → the amount reads on the card.
    await page.getByPlaceholder(/^(Nama barang atau jasa|Item or service name)$/).first().fill('Ayam broiler segar')
    const qty = page.getByRole('textbox', { name: /^(Jml|Qty)$/ }).first()
    await qty.fill('24')
    await page.getByRole('textbox', { name: /^(Harga satuan|Unit price)$/ }).first().fill('34500')
    await page.waitForTimeout(400)
    // Printed total that does NOT match → red reconciliation, then the exact figure → green.
    const printed = page.getByRole('textbox', { name: /^(Nilai tercetak di faktur|Amount printed on the invoice)$/ })
    await printed.fill('900000')
    await page.waitForTimeout(500)
    await page.mouse.wheel(0, 700)
    await page.waitForTimeout(500)
    await shot('bills-new-mismatch')
    await printed.fill('919080')
    await page.waitForTimeout(500)
    await shot('bills-new-match')
    await page.mouse.wheel(0, 900)
    await page.waitForTimeout(500)
    await shot('bills-new-checklist')
    // The evidence: a PDF staged through the hidden file input → the checklist turns green and
    // Save opens. Save = create → upload → post → the bill's own page.
    await page.locator('input[type=file]').setInputFiles({
      name: 'faktur-sumber-pangan-0911.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 mock'),
    })
    await page.waitForTimeout(600)
    await shot('bills-new-ready')
    await page.getByRole('button', { name: /^(Simpan tagihan|Save bill)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(1500)
    if (!page.url().endsWith('/bills/b-new')) throw new Error(`save must open the bill, got ${page.url()}`)
    await shot('bills-detail')
  }

  if (want('menu')) {
    // ADR 0083 — the phone menu is a work page: one row opens into the item's whole editor.
    const shot = async (name) => {
      await page.screenshot({ path: `${dir}/${name}.png` })
      console.log(`[${pass.name}] ${name} ok`)
    }
    await page.goto(`${BASE}/menu`, { waitUntil: 'load' })
    await page.waitForTimeout(1400)
    await shot('menu-list')
    await page.getByRole('button', { name: /^(Buka|Open) Nasi Goreng Spesial$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(900)
    await shot('menu-item-open')
    await page.getByRole('button', { name: /^(Tambah bahan|Add ingredient)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    await shot('menu-pick-ingredient')
    await page.getByRole('button', { name: /^Susu UHT/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    for (const d of ['1', '5', '0']) {
      await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 })
      await page.waitForTimeout(100)
    }
    await page.waitForTimeout(300)
    await shot('menu-qty')
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^(Hapus item|Delete item)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    await shot('menu-delete')
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^Item$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(700)
    await shot('menu-new')
    await page.getByRole('button', { name: /^(Tutup|Close)$/ }).click({ timeout: 8000 })
    await page.waitForTimeout(400)
    // Search reaches into the recipes: "susu" finds the coffee that uses it.
    await page.getByRole('searchbox').first().fill('susu')
    await page.waitForTimeout(900)
    if (!page.url().includes('q=susu')) throw new Error('the query must live in the URL (N5)')
    await shot('menu-search')
  }

  await ctx.close()
}

await browser.close()
console.log('DONE — shots in', OUT)
