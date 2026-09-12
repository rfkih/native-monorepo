/**
 * overflow-audit — the TEXT-OVERFLOW walk for the phone apps. Visits every console route and the
 * mobile-shots interactive scenes (sheets, dialogs, the POS deck, the opname) at a phone width,
 * in both languages, against the mobile-fixtures data, and measures every element in the DOM:
 * text that is wider than the box it sits in, boxes that run past the right edge of the screen,
 * a money figure that broke onto two lines, and every `truncate` that actually fired.
 *
 *   npm run dev                                   (terminal 1 — console, :5173)
 *   node scripts/overflow-audit.mjs               (terminal 2 — 360px, id + en)
 *   W=320 node scripts/overflow-audit.mjs         (the stress width: a phone on Android's
 *                                                  "large display size" is ~320 CSS px)
 *   APP=employee SHOT_BASE=http://localhost:5174 node scripts/overflow-audit.mjs
 *                                                 (the Native Karyawan app, `npm run dev` in
 *                                                  frontend/employee with --port 5174)
 *   LANGS=id ONLY=/pos,menu- …                    restrict the walk (scene-name prefixes)
 *
 * Exit code 1 when anything OTHER than an ellipsis was found — an ellipsis is a design decision
 * (listed in the JSON so the important ones can be reviewed), everything else is a defect.
 * Screenshots with the offenders outlined (red = overflow, orange = ellipsis, magenta = wrapped
 * money) and a findings.json land in shots-overflow/<width>/.
 *
 * Kinds:
 *   text-spill      own text pokes out of an overflow:visible box (overlaps neighbours / off-card)
 *   child-spill     a block/flex child pokes out of its overflow:visible parent
 *   clipped         own text is cut off by overflow:hidden WITHOUT an ellipsis
 *   child-clipped   a block child is cut off by an overflow:hidden parent
 *   beyond-viewport element extends past the right edge of the screen (page scrolls sideways)
 *   page-scrolls-sideways  the document itself is wider than the viewport
 *   money-wrapped   a currency figure broke onto two lines
 *   ellipsis        `truncate` fired (by design — reported, not failed)
 *
 * On Git Bash, set MSYS_NO_PATHCONV=1 when passing ONLY=/… — MSYS rewrites a leading "/" into a
 * Windows path.
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'
import { COMPANY, resolveFixture } from './mobile-fixtures.mjs'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'
const WIDTH = Number(process.env.W ?? 360)
const HEIGHT = 780
const LANGS = (process.env.LANGS ?? 'id,en').split(',')
const ONLY = (process.env.ONLY ?? '').split(',').map((s) => s.trim()).filter(Boolean)
const OUT = `shots-overflow/${WIDTH}`
mkdirSync(OUT, { recursive: true })

// ── The detector (runs in the page) ─────────────────────────────────────────
const DETECT = `(() => {
  const vw = document.documentElement.clientWidth
  const out = []
  const reported = new Set()
  const isInline = (cs) => cs.display.startsWith('inline') || cs.display === 'contents'
  const clipsX = (cs) => cs.overflowX !== 'visible'
  const scrollerAncestor = (el) => {
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      const o = getComputedStyle(p).overflowX
      if (o === 'auto' || o === 'scroll') return p
    }
    return null
  }
  const hiddenAncestor = (el) => {
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      const o = getComputedStyle(p).overflowX
      if (o === 'hidden' || o === 'clip') return p
    }
    return null
  }
  const path = (el) => {
    const parts = []
    for (let p = el; p && p !== document.body; p = p.parentElement) {
      const id = p.getAttribute('data-testid')
      if (id) { parts.unshift('[' + id + ']'); if (parts.length >= 2) break; continue }
      const role = p.getAttribute('role')
      if (role && role !== 'presentation') { parts.unshift(role); if (parts.length >= 2) break }
    }
    return parts.join(' > ')
  }
  const cls = (el) => (typeof el.className === 'string' ? el.className : '').split(/\\s+/).filter(Boolean).slice(0, 10).join(' ')
  const ownTextMetrics = (el) => {
    // rects of the element's own text: direct text nodes + inline-level children
    let right = -Infinity, tops = new Set(), text = '', any = false
    for (const n of el.childNodes) {
      if (n.nodeType === 3) {
        if (!n.textContent.trim()) continue
        const r = document.createRange(); r.selectNodeContents(n)
        for (const rc of r.getClientRects()) { if (rc.width === 0) continue; any = true; right = Math.max(right, rc.right); tops.add(Math.round(rc.top)) }
        text += n.textContent
      } else if (n.nodeType === 1) {
        const ccs = getComputedStyle(n)
        if (!isInline(ccs) || ccs.display === 'none') continue
        if (ccs.display === 'inline-block' || ccs.display === 'inline-flex' || ccs.display === 'inline-grid') {
          const rc = n.getBoundingClientRect(); if (rc.width) { any = true; right = Math.max(right, rc.right); tops.add(Math.round(rc.top)) }
        } else {
          for (const rc of n.getClientRects()) { if (rc.width === 0) continue; any = true; right = Math.max(right, rc.right); tops.add(Math.round(rc.top)) }
        }
        text += n.textContent
      }
    }
    return { any, right, lines: tops.size, text: text.replace(/\\s+/g, ' ').trim() }
  }
  const push = (kind, el, extra) => {
    if (reported.has(el)) return
    reported.add(el)
    const r = el.getBoundingClientRect()
    out.push({ kind, tag: el.tagName.toLowerCase(), cls: cls(el), path: path(el),
      text: (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 90),
      box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)], ...extra })
    el.setAttribute('data-ovf', kind)
  }

  for (const el of document.querySelectorAll('body *')) {
    if (el.closest('svg') || ['SCRIPT','STYLE','CANVAS','NOSCRIPT'].includes(el.tagName)) continue
    const cs = getComputedStyle(el)
    if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue
    if (isInline(cs)) continue
    const r = el.getBoundingClientRect()
    if (r.width < 1 || r.height < 1) continue
    if (r.right <= 0 || r.left >= vw) continue // parked off-screen (a closed drawer)
    if (r.top >= window.innerHeight && el.closest('[style*="fixed"], .fixed')) continue // a sheet translated below the fold
    if (r.bottom <= 0 && el.closest('.fixed')) continue

    const contentRight = r.left + el.clientLeft + el.clientWidth
    const own = ownTextMetrics(el)

    // 1. own text vs the box it lives in
    if (own.any && own.right > contentRight + 1.5) {
      const need = Math.round(own.right - r.left - el.clientLeft), have = el.clientWidth
      if (!clipsX(cs)) push('text-spill', el, { need, have, over: need - have })
      else if (cs.overflowX === 'auto' || cs.overflowX === 'scroll') { /* designed scroller */ }
      else if (cs.textOverflow === 'ellipsis') push('ellipsis', el, { need, have, over: need - have })
      else push('clipped', el, { need, have, over: need - have })
    }
    // 2. block children vs this box
    if (el.scrollWidth > el.clientWidth + 1 && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll') {
      for (const c of el.children) {
        const ccs = getComputedStyle(c)
        if (isInline(ccs) || ccs.display === 'none' || ccs.position === 'absolute' || ccs.position === 'fixed') continue
        const cr = c.getBoundingClientRect()
        if (cr.width < 1) continue
        if (cr.right > contentRight + 1.5) {
          const over = Math.round(cr.right - contentRight)
          // A deliberate bleed (-mr-2.5 on an icon button, -mx-5 on a full-width strip) explains itself.
          const bleed = Math.max(0, -parseFloat(ccs.marginRight)) + Math.max(0, -parseFloat(ccs.marginLeft))
          if (over <= bleed + 1) continue
          push(clipsX(cs) ? 'child-clipped' : 'child-spill', c, { over, parent: el.tagName.toLowerCase() + '.' + cls(el).split(' ').slice(0, 4).join('.') })
        }
      }
    }
    // 3. past the right edge of the screen
    if (r.right > vw + 1.5 && (own.any || ['BUTTON','A','INPUT','IMG','TABLE'].includes(el.tagName)) && !scrollerAncestor(el) && !hiddenAncestor(el)) {
      push('beyond-viewport', el, { over: Math.round(r.right - vw) })
    }
    // 4. a money figure that wrapped
    // A bare figure only ("Rp 348.000", "−IDR 1,320,000/month") — a sentence that starts with an
    // amount is allowed to wrap.
    if (own.any && own.lines > 1 && /^[+−-]?\\s?(Rp|IDR|US\\$|USD|\\$)\\s?-?[\\d.,]+(\\s?\\/\\S+)?$/.test(own.text)) {
      push('money-wrapped', el, { lines: own.lines })
    }
  }
  return { vw, pageScrollWidth: document.documentElement.scrollWidth, bodyScrollWidth: document.body.scrollWidth, findings: out }
})()`

// ── Scenes ──────────────────────────────────────────────────────────────────
const ROUTES = [
  '/', '/more', '/statements/income', '/statements/balance-sheet', '/statements/cash-flow',
  '/budgets', '/assets', '/deferrals', '/promotions', '/loyalty', '/channels', '/platform-settlements',
  '/marketplace', '/sales-integrity', '/opening-balances', '/expenses', '/expenses/categories',
  '/expenses/record', '/invoices', '/invoices/new', '/invoices/i1', '/customers', '/ar/aging', '/bills',
  '/bills/new', '/bills/b1', '/vendors', '/ap/aging', '/bank', '/tax', '/org', '/groups', '/close', '/team',
  '/people?tab=employees', '/people?tab=attendance', '/people?tab=payroll',
  '/menu', '/inventory', '/inventory/new', '/inventory/history', '/inventory/daging-kebab',
  '/inventory/saus-pouch', '/catalog', '/kitchen', '/me', '/me/expenses', '/me/payslips', '/me/timeoff',
  '/me/account', '/settings/printer', '/settings/features', '/settings/payments', '/settings/inventory',
  '/pos', '/onboarding', '/signup',
]

/** Interactive scenes — copied from mobile-shots so the same sheets/dialogs get measured. */
const SCENES = [
  ['inbox-decision', async (page) => {
    await page.goto(`${BASE}/expenses`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.getByText('Rina Puspita').first().click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['payslip-open', async (page) => {
    await page.goto(`${BASE}/me/payslips`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.locator('button:has-text("2026")').first().click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['opname-count', async (page, lang) => {
    await page.goto(`${BASE}/more`, { waitUntil: 'load' }); await page.waitForTimeout(1000)
    await page.getByRole('button', { name: /^(Opname stok|Stocktake)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(1400)
  }],
  ['opname-sheet', async (page) => {
    await page.getByRole('button', { name: /^Daging Kebab.*: / }).click({ timeout: 8000 }); await page.waitForTimeout(700)
    for (const d of ['3']) { await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 }) }
    await page.getByRole('button', { name: /^(Pemisah desimal|Decimal separator)$/ }).click({ timeout: 8000 })
    for (const d of ['3', '2']) { await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 }) }
    await page.waitForTimeout(400)
  }],
  ['opname-summary', async (page) => {
    await page.getByTestId('stocktake-count-save').click({ timeout: 8000 }); await page.waitForTimeout(600)
    await page.getByTestId('stocktake-submit').click({ timeout: 8000 }); await page.waitForTimeout(1200)
  }],
  ['opname-guard', async (page) => {
    await page.getByRole('button', { name: /^(Selesai|Done)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(600)
    await page.getByRole('button', { name: /^(Opname stok|Stocktake)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /^Daging Kebab.*: / }).click({ timeout: 8000 }); await page.waitForTimeout(700)
    for (const d of ['2', '6', '4', '0']) { await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 }) }
    await page.getByTestId('stocktake-count-save').click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByTestId('stocktake-submit').click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
  ['opname-discard', async (page) => {
    await page.getByTestId('stocktake-variance-recount').click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^(Kembali|Back)$/ }).last().click({ timeout: 8000 }); await page.waitForTimeout(600)
  }],
  ['laporan-month', async (page) => {
    await page.goto(`${BASE}/statements/income`, { waitUntil: 'load' }); await page.waitForTimeout(1800)
    await page.locator('button[title][aria-pressed="false"]').nth(2).click({ timeout: 8000 }); await page.waitForTimeout(1200)
  }],
  ['laporan-detail-sheet', async (page) => {
    await page.getByTestId('laporan-hero').click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
  ['laporan-export-sheet', async (page) => {
    await page.goBack(); await page.waitForTimeout(600)
    await page.getByTestId('laporan-export').click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
  ['inventory-receive', async (page) => {
    await page.goto(`${BASE}/inventory`, { waitUntil: 'load' }); await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /^(Terima|Receive) Daging Kebab/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
    for (const d of ['4']) { await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 }) }
    await page.getByRole('button', { name: /^(Pemisah desimal|Decimal separator)$/ }).click({ timeout: 8000 })
    await page.getByRole('button', { name: /^(Angka|Digit) 5$/ }).click({ timeout: 8000 }); await page.waitForTimeout(300)
  }],
  ['inventory-set', async (page) => {
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^Daging Kebab/ }).first().click({ timeout: 8000 }); await page.waitForTimeout(900)
    await page.getByRole('button', { name: /^(Atur jumlah|Set quantity)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
  ['inventory-edit', async (page) => {
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(400)
    await page.getByRole('button', { name: /^(Ubah barang|Edit item)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['inventory-convert', async (page) => {
    await page.goto(`${BASE}/inventory/saus-pouch`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.getByRole('button', { name: /(Ubah satuannya|Change the unit)/ }).click({ timeout: 8000 }); await page.waitForTimeout(900)
    await page.getByRole('textbox').first().fill('1000'); await page.waitForTimeout(300)
  }],
  ['inventory-history-lines', async (page) => {
    await page.goto(`${BASE}/inventory/history`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.getByRole('button', { name: /2026/ }).first().click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['inventory-method-form', async (page) => {
    await page.goto(`${BASE}/inventory`, { waitUntil: 'load' }); await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /(belum berbiaya|no cost — counted)/ }).click({ timeout: 8000 }); await page.waitForTimeout(900)
    await page.getByRole('button', { name: /^(Aktifkan persediaan perpetual|Activate perpetual inventory)/ }).click({ timeout: 8000 }); await page.waitForTimeout(500)
  }],
  ['pos-deck-peek', async (page) => {
    await page.goto(`${BASE}/pos`, { waitUntil: 'load' }); await page.waitForTimeout(1600)
    for (const item of ['Nasi Goreng Spesial', 'Es Teh Manis', 'Kopi Susu Gula Aren']) {
      await page.getByRole('button', { name: new RegExp(item) }).first().click({ timeout: 8000 }); await page.waitForTimeout(350)
    }
    await page.waitForTimeout(1600)
  }],
  ['pos-deck-expanded', async (page) => {
    await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
  ['pos-till-menu', async (page) => {
    await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByTestId('pos-till-menu').click({ timeout: 8000 }); await page.waitForTimeout(400)
  }],
  ['pos-bill-expanded', async (page, lang) => {
    await page.getByRole('menuitem', { name: lang === 'id' ? 'Pesanan' : 'Orders', exact: true }).click({ timeout: 8000 }); await page.waitForTimeout(600)
    await page.getByText('Meja 07').first().click({ timeout: 8000 }); await page.waitForTimeout(1400)
    await page.getByTestId('pos-dock-toggle').click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
  ['pos-parked-tray', async (page) => {
    await page.goto(`${BASE}/pos`, { waitUntil: 'load' }); await page.waitForTimeout(1600)
    await page.getByTestId('pos-parked').click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
  ['bills-new-filled', async (page) => {
    await page.goto(`${BASE}/bills/new`, { waitUntil: 'load' }); await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /(Pilih vendor|Choose a vendor)/ }).click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByRole('button', { name: /CV Sumber Pangan Jaya/ }).first().click({ timeout: 8000 }); await page.waitForTimeout(500)
    const invoice = page.getByPlaceholder('INV/2026/09/…')
    await invoice.fill('INV/2026/08/2214'); await page.waitForTimeout(700)
    await page.getByPlaceholder(/^(Nama barang atau jasa|Item or service name)$/).first().fill('Ayam broiler segar kampung ukuran besar')
    await page.getByRole('textbox', { name: /^(Jml|Qty)$/ }).first().fill('24')
    await page.getByRole('textbox', { name: /^(Harga satuan|Unit price)$/ }).first().fill('34500')
    const printed = page.getByRole('textbox', { name: /^(Nilai tercetak di faktur|Amount printed on the invoice)$/ })
    await printed.fill('900000'); await page.waitForTimeout(500)
  }],
  ['menu-item-open', async (page) => {
    await page.goto(`${BASE}/menu`, { waitUntil: 'load' }); await page.waitForTimeout(1400)
    await page.getByRole('button', { name: /^(Buka|Open) Nasi Goreng Spesial$/ }).click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['menu-pick-ingredient', async (page) => {
    await page.getByRole('button', { name: /^(Tambah bahan|Add ingredient)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
  ['menu-qty', async (page) => {
    await page.getByRole('button', { name: /^Susu UHT/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
    for (const d of ['1', '5', '0']) { await page.getByRole('button', { name: new RegExp(`^(Angka|Digit) ${d}$`) }).click({ timeout: 8000 }) }
    await page.waitForTimeout(300)
  }],
  ['menu-delete', async (page) => {
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^(Hapus item|Delete item)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
  ['menu-new', async (page) => {
    await page.getByRole('button', { name: /^(Batal|Cancel)$/ }).click({ timeout: 8000 }); await page.waitForTimeout(500)
    await page.getByRole('button', { name: /^Item$/ }).click({ timeout: 8000 }); await page.waitForTimeout(700)
  }],
]

// ── Employee app (Native Karyawan) — same fixtures, its own routes ───────────
const EMPLOYEE = process.env.APP === 'employee'
const EMP_ROUTES = ['/me', '/me/timeoff', '/me/expenses', '/me/payslips', '/me/expenses/cl1', '/me/profile', '/me/account', '/me/approvals']
const EMP_SCENES = [
  ['emp-payslip-open', async (page) => {
    await page.goto(`${BASE}/me/payslips`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.locator('button:has-text("2026")').first().click({ timeout: 8000 }); await page.waitForTimeout(900)
  }],
  ['emp-timeoff-new', async (page) => {
    await page.goto(`${BASE}/me/timeoff`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.getByRole('button').filter({ hasText: /(Ajukan cuti|Request leave|Cuti|Leave)/ }).first().click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
  ['emp-claim-new', async (page) => {
    await page.goto(`${BASE}/me/expenses`, { waitUntil: 'load' }); await page.waitForTimeout(1200)
    await page.getByRole('button').filter({ hasText: /(Klaim baru|New claim|Ajukan|Submit)/ }).first().click({ timeout: 8000 }); await page.waitForTimeout(800)
  }],
]
const ROUTE_LIST = EMPLOYEE ? EMP_ROUTES : ROUTES
const SCENE_LIST = EMPLOYEE ? EMP_SCENES : SCENES
const TAG = EMPLOYEE ? 'emp-' : ''

// ── Run ─────────────────────────────────────────────────────────────────────
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const all = []
// A route or scene that throws (server down, a renamed button, a selector timeout) measured
// NOTHING — it must fail the walk, or a dead dev server would print OVERFLOW AUDIT OK.
const failed = []
const want = (name) => ONLY.length === 0 || ONLY.some((o) => name.startsWith(o))

for (const lang of LANGS) {
  const ctx = await browser.newContext({ viewport: { width: WIDTH, height: HEIGHT }, isMobile: true, hasTouch: true })
  await ctx.addInitScript(([company, lang]) => {
    window.__NATIVE_CONFIG__ = { authMode: 'dev' }
    localStorage.setItem('native.console.sessions', JSON.stringify([company]))
    localStorage.setItem('native.console.theme', 'light')
    localStorage.setItem('native.console.lang', lang)
  }, [COMPANY, lang])
  await ctx.route('**/api/v1/**', async (route) => {
    const fx = resolveFixture(route.request().url(), route.request())
    if (fx && typeof fx.status === 'number') return route.fulfill({ status: fx.status, contentType: 'application/json', body: '{}' })
    // Unknown endpoints: an empty LIST (nav-smoke's choice) — `{}` throws into the ErrorBoundary.
    const body = fx && typeof fx === 'object' && !Array.isArray(fx) && Object.keys(fx).length === 0 ? [] : fx
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 160)))

  const measure = async (scene) => {
    const res = await page.evaluate(DETECT)
    const url = page.url().replace(BASE, '') || '/'
    for (const f of res.findings) all.push({ lang, width: WIDTH, scene, url, ...f })
    const pageWide = res.pageScrollWidth > res.vw + 1
    if (pageWide) all.push({ lang, width: WIDTH, scene, url, kind: 'page-scrolls-sideways', over: res.pageScrollWidth - res.vw })
    await page.addStyleTag({ content: '[data-ovf]{outline:2px solid red!important;outline-offset:-1px}[data-ovf="ellipsis"]{outline-color:orange!important}[data-ovf="money-wrapped"]{outline-color:magenta!important}' })
    await page.screenshot({ path: `${OUT}/${TAG}${lang}-${scene.replace(/[^a-z0-9]+/gi, '_')}.png`, fullPage: true }).catch(() => {})
    console.log(`[${lang}] ${scene.padEnd(28)} ${String(res.findings.length).padStart(3)} findings${pageWide ? '  PAGE-WIDE +' + (res.pageScrollWidth - res.vw) : ''}${errors.length ? '  ERR ' + errors.splice(0).join(' | ') : ''}`)
  }

  for (const r of ROUTE_LIST) {
    if (!want(r)) continue
    try {
      await page.goto(`${BASE}${r}`, { waitUntil: 'load' })
      await page.waitForTimeout(1500)
      await measure(r)
    } catch (e) {
      failed.push(`${lang}:${r}`)
      console.log(`[${lang}] ${r} FAILED ${String(e.message).split('\n')[0]}`)
    }
  }
  for (const [name, fn] of SCENE_LIST) {
    if (!want(name)) continue
    try {
      await fn(page, lang)
      await measure(name)
    } catch (e) {
      failed.push(`${lang}:${name}`)
      console.log(`[${lang}] ${name} FAILED ${String(e.message).split('\n')[0]}`)
    }
  }
  await ctx.close()
}
await browser.close()

writeFileSync(`${OUT}/${TAG}findings.json`, JSON.stringify(all, null, 1))
const byKind = {}
for (const f of all) byKind[f.kind] = (byKind[f.kind] ?? 0) + 1
console.log('\nTOTAL', all.length, byKind)

// Every defect, once — same element signature across scenes/languages collapses to one line.
const defects = all.filter((f) => f.kind !== 'ellipsis')
const seen = new Map()
for (const f of defects) {
  const key = `${f.kind}|${f.tag}|${(f.cls ?? '').split(' ').slice(0, 6).join(' ')}|${(f.text ?? '').replace(/[\d.,]+/g, '#').slice(0, 40)}`
  const g = seen.get(key) ?? { n: 0, scenes: new Set(), ex: f }
  g.n++
  g.scenes.add(`${f.lang}:${f.scene}`)
  seen.set(key, g)
}
for (const g of seen.values()) {
  const f = g.ex
  console.log(`  ${f.kind.padEnd(22)} ${f.tag ?? ''}.${(f.cls ?? '').split(' ').slice(0, 5).join('.')}  "${(f.text ?? '').slice(0, 50)}"  over=${f.over ?? '-'}  [${[...g.scenes].slice(0, 4).join(', ')}${g.scenes.size > 4 ? ', …' : ''}]`)
}
if (failed.length) console.log(`\nNOT MEASURED (${failed.length}): ${failed.join(', ')}`)
const ok = defects.length === 0 && failed.length === 0
console.log(
  ok
    ? '\nOVERFLOW AUDIT OK'
    : `\nOVERFLOW AUDIT FAILED (${defects.length} defects, ${seen.size} distinct, ${failed.length} scenes not measured) — see ${OUT}/`,
)
process.exit(ok ? 0 : 1)
