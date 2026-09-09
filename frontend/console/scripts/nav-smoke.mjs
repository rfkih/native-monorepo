/**
 * nav-smoke — the NAVIGATION CONTRACT walk (ADR 0075, rules N1/N3/N4/N6). Sibling of
 * backguard-smoke.mjs, which covers the hardware-Back *guard*; this one covers what an in-app
 * BACK CONTROL does and where a navigation leaves the scroll position.
 *
 *   VITE_AUTH_MODE=dev npm run dev   (terminal 1, in frontend/console)
 *   node scripts/nav-smoke.mjs       (terminal 2)
 *
 * N1 — a back control POPS, it never PUSHES a destination. Proven by going FORWARD after a back
 *      press: a pop leaves a forward entry, a push does not.
 * N4 — PUSH lands at the top of the new page, POP restores where you were, REPLACE moves nothing;
 *      and all of that survives the back guard's sentinel entries (section 5).
 */
import { chromium } from 'playwright-core'

const BASE = process.env.SHOT_BASE ?? 'http://localhost:5173'

const OUTLET_ID = 'b0000000-0000-0000-0000-000000000001'
const COMPANY = {
  companyId: 'c0000000-0000-0000-0000-000000000001',
  name: 'Warung Kemang',
  baseCurrency: 'IDR',
  defaultLanguage: 'id',
  businessId: OUTLET_ID,
  divisionId: null,
  planTier: 'FULL',
  vertical: 'restaurant',
}

/**
 * The walk visits /menu, which sits behind OutletGate — with an all-empty API mock the gate renders
 * its "no outlet" panel and the page's own header (the back control under test) never mounts. So
 * the outlet endpoints get a real one-outlet fixture; everything else stays empty.
 */
const OUTLETS = [{ id: OUTLET_ID, name: 'Outlet Kemang', type: 'OUTLET', vertical: null, parentId: null, active: true }]

let failures = 0
function check(name, cond, extra = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else {
    failures++
    console.log(`  FAIL  ${name} ${extra}`)
  }
}
/** Each section runs to completion independently — a missing control in section 1 must not hide
 *  what sections 2-4 would have reported (the whole point of running the walk before the fix). */
async function section(title, fn) {
  console.log(`\n${title}`)
  try {
    await fn()
  } catch (e) {
    failures++
    console.log(`  FAIL  section aborted — ${String(e.message).split('\n')[0]}`)
  }
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })

/** A seeded context. `guard` forces the hardware-Back guard on (mirrors the Android shell). */
async function makeContext({ phone, guard }) {
  const ctx = await browser.newContext(
    phone
      ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true }
      : { viewport: { width: 1280, height: 900 } },
  )
  await ctx.addInitScript(
    ([company, forceGuard]) => {
      localStorage.setItem('native.console.sessions', JSON.stringify([company]))
      localStorage.setItem('native.console.theme', 'light')
      localStorage.setItem('native.console.lang', 'id')
      if (forceGuard) sessionStorage.setItem('backGuardDev', '1')
    },
    [COMPANY, guard === true],
  )
  await ctx.route('**/api/v1/**', (route) => {
    // Default to an EMPTY ARRAY, not an empty object: the pages this walk visits fan out over many
    // list endpoints, and one `{}` where a component does `.map`/`.find` throws into the
    // ErrorBoundary — which replaces the whole screen and silently invalidates the rest of the
    // walk (it looks exactly like "the control is missing").
    const p = new URL(route.request().url()).pathname
    const json = (body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    if (p.endsWith('/users/me/outlets')) return json([]) // empty == unrestricted, not "none"
    if (p.endsWith('/outlets') || p.endsWith('/org-units')) return json(OUTLETS)
    return json([])
  })
  const page = await ctx.newPage()
  page.on('pageerror', (e) => console.log('PAGEERROR', String(e.message).slice(0, 200)))
  return { ctx, page }
}

const pathOf = (page) => new URL(page.url()).pathname
const idxOf = (page) => page.evaluate(() => window.history.state?.idx ?? null)
const visible = async (loc) => await loc.isVisible().catch(() => false)
const backArrow = (page) => page.getByRole('button', { name: 'Kembali', exact: true }).first()

// ═══ 1. Desktop: the catalog back arrow returns to where you came FROM ═══════
// The headline defect: /menu, /inventory, /kitchen and /catalog all hard-coded `to="/pos"`, so an
// owner who opened "Menu & harga" from the office sidebar was dropped into the cashier till.
await section('[1] desktop — catalog back arrow (N1)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1500)
  check('home loaded', pathOf(page) === '/', pathOf(page))
  const homeIdx = await idxOf(page)

  await page.getByRole('button', { name: /Menu & stok/ }).click({ timeout: 8000 })
  await page.getByRole('link', { name: 'Menu & harga', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1200)
  check('sidebar opened /menu', pathOf(page) === '/menu', pathOf(page))
  const menuIdx = await idxOf(page)
  check('forward navigation pushed one entry', menuIdx === homeIdx + 1, `${homeIdx} -> ${menuIdx}`)
  await backArrow(page).click({ timeout: 8000 })
  await page.waitForTimeout(1200)
  check('back arrow returns to the ORIGIN, not /pos', pathOf(page) === '/', pathOf(page))
  check('back arrow did not grow history', (await idxOf(page)) === homeIdx, String(await idxOf(page)))

  // The decisive proof of pop-vs-push: only a pop leaves a forward entry behind.
  await page.goForward()
  await page.waitForTimeout(900)
  check('back was a POP (forward re-enters /menu)', pathOf(page) === '/menu', pathOf(page))
  await ctx.close()
})

// ═══ 2. Desktop: deep link has nothing to pop → the declared fallback ════════
// Opening /menu cold (a bookmark, a shared link, an app cold-start on a sub-page) seeds idx 0, so
// popping would leave for the IdP page. The page's declared fallback must catch that.
await section('[2] desktop — deep-link fallback (N1)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/menu`, { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  check('deep-linked onto /menu', pathOf(page) === '/menu', pathOf(page))
  check('deep link is the first in-app entry', (await idxOf(page)) === 0, String(await idxOf(page)))

  await backArrow(page).click({ timeout: 8000 })
  await page.waitForTimeout(1200)
  check('falls back to the declared destination', pathOf(page) === '/pos', pathOf(page))
  await ctx.close()
})

// ═══ 3. Phone + guard: back arrow then hardware Back must not teleport ═══════
// The compound bug. A pushing back arrow left the real page in FORWARD history, so the guard's
// confirm (go(-2)) walked the user forward into the page they had just left.
await section('[3] phone + guard — back arrow composes with hardware Back (N1)', async () => {
  const { ctx, page } = await makeContext({ phone: true, guard: true })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  check('phone home loaded', pathOf(page) === '/', pathOf(page))

  // More is a routed screen since ADR 0078 — a LINK, and a history entry of its own.
  await page.getByRole('link', { name: 'Lainnya', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(900)
  await page.getByRole('link', { name: 'Menu & harga', exact: true }).first().click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  check('the More tile opened /menu', pathOf(page) === '/menu', pathOf(page))

  // Back returns to MORE, not home — More is now a place you came from rather than an overlay that
  // vanished (ADR 0078). This is the documented cost of the change, so the walk asserts it.
  await backArrow(page).click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  check('back arrow returned to More', pathOf(page) === '/more', pathOf(page))

  // Inside the shells More is now an ordinary page, so hardware Back offers the leave confirm here
  // exactly as it does anywhere else — where the sheet used to just vanish. Confirming reaches home.
  await page.goBack()
  await page.waitForTimeout(1200)
  check('hardware Back on More offers the leave confirm',
    await visible(page.getByText('Keluar dari halaman ini?', { exact: true })))
  await page.getByRole('button', { name: 'Keluar', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  check('confirming leave reaches home', pathOf(page) === '/', pathOf(page))

  // Home is the app root, so hardware Back here means "leave the app" — never "forward into /menu".
  await page.goBack()
  await page.waitForTimeout(900)
  check('hardware Back at home offers EXIT, not leave-page', await visible(page.getByText('Keluar aplikasi?', { exact: true })))
  check('still at home while the dialog is open', pathOf(page) === '/', pathOf(page))
  await page.getByRole('button', { name: 'Batal' }).click().catch(() => {})
  await ctx.close()
})

// ═══ 4. Scroll behaviour (phase 2 — registered, not yet delivered) ═══════════
await section('[4] desktop — scroll on navigate (N4)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1500)
  // With the API mocked empty every page is shorter than the viewport, so scrollY would be a
  // trivial 0 everywhere and the checks below would pass for the wrong reason. One style tag makes
  // EVERY page tall — it lives on the document, so it survives client-side navigation.
  await page.addStyleTag({ content: 'main::after{content:"";display:block;height:2000px}' })
  await page.waitForTimeout(200)
  await page.evaluate(() => window.scrollTo(0, 600))
  await page.waitForTimeout(200)
  const scrolled = await page.evaluate(() => window.scrollY)
  check('scroll fixture is tall enough to be meaningful', scrolled > 400, `scrollY=${scrolled}`)

  // Target a SHELL-wrapped page: the full-screen catalog pages pin themselves to `h-[100dvh]
  // overflow-hidden`, so window.scrollY is trivially 0 there and both checks below would pass for
  // the wrong reason.
  await page.getByRole('button', { name: /Organisasi/ }).click({ timeout: 8000 })
  await page.getByRole('link', { name: 'Tim', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1500)
  check('scroll walk reached a shell page', pathOf(page) === '/team', pathOf(page))
  // Scrolling to 0 on a page the browser would clamp to 0 anyway proves nothing, so assert the
  // destination is genuinely TALL at the moment we read the offset.
  const targetTall = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight > 800)
  check('destination is scrollable, so "at the top" is meaningful', targetTall)
  check('PUSH lands at the top of the new page', (await page.evaluate(() => window.scrollY)) === 0, `scrollY=${await page.evaluate(() => window.scrollY)}`)

  await page.goBack()
  await page.waitForTimeout(1500)
  const restored = await page.evaluate(() => window.scrollY)
  check('POP restores the previous scroll offset', Math.abs(restored - scrolled) < 40, `want ~${scrolled}, got ${restored}`)

  // A REPLACE must not move the page: /me/payslips bounces to /me at desktop width, and yanking
  // the viewport during a redirect the user never asked for reads as a glitch.
  await page.evaluate(() => window.scrollTo(0, 500))
  await page.waitForTimeout(200)
  const beforeReplace = await page.evaluate(() => window.scrollY)
  await page.evaluate(() => window.history.replaceState(window.history.state, '', window.location.pathname))
  await page.waitForTimeout(400)
  check('REPLACE leaves the offset alone', Math.abs((await page.evaluate(() => window.scrollY)) - beforeReplace) < 10, `was ${beforeReplace}, now ${await page.evaluate(() => window.scrollY)}`)
  await ctx.close()
})

// ═══ 5. Scroll survives the back guard's sentinel entries (N4 × the guard) ═══
// The guard parks an extra history entry above EVERY route. It writes that entry with a raw
// pushState, so react-router never sees it and `location.key` is unchanged — which is exactly why
// the scroll memory is keyed on that key and not on history.state. This proves it.
await section('[5] phone + guard — restore across a guarded Back (N4)', async () => {
  const { ctx, page } = await makeContext({ phone: true, guard: true })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  await page.addStyleTag({ content: 'main::after{content:"";display:block;height:2000px}' })
  await page.waitForTimeout(200)
  await page.evaluate(() => window.scrollTo(0, 600))
  await page.waitForTimeout(200)
  const scrolled = await page.evaluate(() => window.scrollY)
  check('home is scrolled before leaving', scrolled > 400, `scrollY=${scrolled}`)

  // Via the bottom tab bar — the phone's own navigation, and the control that used to carry the
  // app's only scroll-to-top (now removed in favour of the app-wide rule).
  await page.getByRole('link', { name: 'Tim', exact: true }).first().click({ timeout: 8000 })
  await page.waitForTimeout(1500)
  check('tab bar opened /team', pathOf(page) === '/team', pathOf(page))
  check('tab navigation still lands at the top', (await page.evaluate(() => window.scrollY)) === 0, `scrollY=${await page.evaluate(() => window.scrollY)}`)

  // Hardware Back on a non-home page asks first; confirming is the guard's own go(-2).
  await page.goBack()
  await page.waitForTimeout(900)
  check('guard asked before leaving the page', await visible(page.getByText('Keluar dari halaman ini?', { exact: true })))
  await page.getByRole('button', { name: 'Keluar', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1800)
  check('guarded Back returned home', pathOf(page) === '/', pathOf(page))
  const restored = await page.evaluate(() => window.scrollY)
  check('offset restored despite the sentinel entry', Math.abs(restored - scrolled) < 40, `want ~${scrolled}, got ${restored}`)
  await ctx.close()
})

// ═══ 6. A loading route chunk must not take the chrome with it (N6) ═════════
// The Suspense boundary used to sit at the app root, so any not-yet-prefetched route chunk
// replaced the ENTIRE screen with AppSkeleton — sidebar, topbar and the accordion's open group
// included — before painting the destination. The boundary now lives inside the Shell, so only
// the content column swaps. Proven by holding the destination's module in flight.
await section('[6] desktop — chrome survives a lazy route load (N6)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  let held = false
  // Hold the destination's module long enough to observe the fallback. If the route chunk was
  // already warmed by App's idle prefetcher the handler never fires — then this walk proves
  // nothing and says so, rather than passing for the wrong reason.
  await ctx.route('**/src/features/tax/**', async (route) => {
    held = true
    await new Promise((resolve) => setTimeout(resolve, 1800))
    return route.continue()
  })

  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1200)
  await page.getByRole('button', { name: /Kas & pajak/ }).click({ timeout: 8000 })
  await page.getByRole('link', { name: 'Pajak / PPN', exact: true }).click({ timeout: 8000 })

  // Mid-flight: the destination has not rendered yet.
  await page.waitForTimeout(600)
  if (!held) {
    console.log('  SKIP  route chunk was already prefetched — nothing was held in flight')
  } else {
    check('sidebar still painted while the chunk loads', await visible(page.getByRole('button', { name: /Kas & pajak/ })))
    check('company switcher still painted', await visible(page.getByRole('button', { name: 'Ganti perusahaan' })))
    check('a content ghost is showing', (await page.locator('main [aria-busy="true"]').count()) > 0)
  }

  await page.waitForTimeout(2500)
  check('destination finished rendering', pathOf(page) === '/tax', pathOf(page))
  await ctx.close()
})

// ═══ 7. An unknown path resolves without mounting the back office ═══════════
// Regression guard for moving the catch-all OUT of the Shell layout route: `*` must still lose to
// every real route and still land on the login's home. (The one-commit Shell flash the move
// removes is not observable from here — it is prevented by construction: a route outside a layout
// route cannot render that layout.)
await section('[7] desktop — unknown path redirects to home', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/no-such-page`, { waitUntil: 'load' })
  await page.waitForTimeout(2000)
  check('unknown path lands on home', pathOf(page) === '/', pathOf(page))
  // And a real route still wins over the catch-all after the move.
  await page.goto(`${BASE}/team`, { waitUntil: 'load' })
  await page.waitForTimeout(2000)
  check('a real route still outranks the catch-all', pathOf(page) === '/team', pathOf(page))
  await ctx.close()
})

// ═══ 8. The shared dialog behaves like one dialog everywhere (N3) ═══════════
// Six near-identical DialogOverlay copies collapsed into components/ui/Dialog. Each copy put
// `onKeyDown` on a div with no tabIndex, so Escape only fired if focus happened to be inside —
// which it never was, because nothing moved focus in. This walks the behaviours that were missing.
await section('[8] desktop — one dialog primitive (N3)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/customers`, { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  check('customers page loaded', pathOf(page) === '/customers', pathOf(page))

  const trigger = page.getByRole('button', { name: 'Pelanggan baru', exact: true })
  const dialog = page.getByRole('dialog')

  // — Escape closes it (the bug every copy shared) —
  await trigger.click({ timeout: 8000 })
  await page.waitForTimeout(500)
  check('dialog opened', await visible(dialog))
  check('focus moved into the dialog', await page.evaluate(() => {
    const panel = document.querySelector('[role="dialog"]')
    return !!panel && (panel === document.activeElement || panel.contains(document.activeElement))
  }))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)
  check('Escape closes the dialog', !(await visible(dialog)))
  check('focus returned to the trigger', await page.evaluate(() => document.activeElement?.textContent?.includes('Pelanggan baru') ?? false))

  // — the scrim closes it —
  await trigger.click({ timeout: 8000 })
  await page.waitForTimeout(500)
  check('dialog reopened', await visible(dialog))
  await page.mouse.click(20, 20) // outside the centred card
  await page.waitForTimeout(600)
  check('backdrop click closes the dialog', !(await visible(dialog)))

  // — browser/hardware Back closes it instead of navigating away —
  await trigger.click({ timeout: 8000 })
  await page.waitForTimeout(500)
  await page.goBack()
  await page.waitForTimeout(700)
  check('Back closes the dialog', !(await visible(dialog)))
  check('Back did not leave the page', pathOf(page) === '/customers', pathOf(page))
  await ctx.close()
})

// ═══ 9. The tab a page shows is the tab the URL names (N5) ══════════════════
// The sidebar has three People entries (/people?tab=…) and marks one active by matching pathname
// AND search — but the page's own Segmented control never wrote back, so the nav went on
// highlighting a tab the page was no longer showing, and the tab could not be linked.
await section('[9] desktop — tab state is two-directional (N5)', async () => {
  const { ctx, page } = await makeContext({ phone: false, guard: false })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1500)
  const homeIdx = await idxOf(page)

  await page.getByRole('button', { name: /SDM/ }).click({ timeout: 8000 })
  await page.getByRole('link', { name: 'Karyawan', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1500)
  check('sidebar opened the People employees tab', page.url().endsWith('/people?tab=employees'), page.url())

  // Switching tab IN THE PAGE must move the URL with it.
  await page.getByRole('tab', { name: 'Absensi', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(900)
  check('in-page tab switch updates the URL', page.url().endsWith('/people?tab=attendance'), page.url())
  check('the nav follows the page', await page.evaluate(() => {
    const link = [...document.querySelectorAll('a[href*="tab=attendance"]')].find((a) => a.closest('aside'))
    return link?.getAttribute('aria-current') === 'page'
  }))
  check('a tab switch does not grow history', (await idxOf(page)) === homeIdx + 1, `${homeIdx} -> ${await idxOf(page)}`)

  // Back leaves the page rather than walking the tabs one at a time.
  await page.goBack()
  await page.waitForTimeout(1200)
  check('Back leaves the page, it does not undo the tab', pathOf(page) === '/', pathOf(page))

  // A deep link opens on the tab it names.
  await page.goto(`${BASE}/people?tab=payroll`, { waitUntil: 'load' })
  await page.waitForTimeout(2000)
  check('a linked tab opens on that tab', await visible(page.getByRole('tab', { name: 'Penggajian', selected: true })))
  await ctx.close()
})

// ═══ 10. Phone: "More" is a screen, not a modal (ADR 0078) ══════════════════
// Below 640px the sidebar and its hamburger are both hidden, so More is the ONLY navigation an
// office login has. As a sheet it self-dismissed on every use, could not be backed into, and lost
// its scroll position between visits. Each check below is one of those three defects — and the
// forward-after-Back check is what proves it PUSHED rather than replaced, the same discriminator
// section [1] uses for back arrows.
await section('[10] phone — More is a routed screen (ADR 0078)', async () => {
  const { ctx, page } = await makeContext({ phone: true, guard: false })
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await page.waitForTimeout(1500)
  const homeIdx = await idxOf(page)
  const homePath = pathOf(page)

  await page.getByRole('link', { name: 'Lainnya', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1200)
  check('the More tab opens a route', pathOf(page) === '/more', pathOf(page))
  check('opening More PUSHES', (await idxOf(page)) === homeIdx + 1, `${homeIdx} -> ${await idxOf(page)}`)
  // N2 — one page, one chrome: More is a page of the same app, so the bar stays put.
  check('the tab bar stays mounted on More', await visible(page.getByRole('navigation').last()))

  await page.goBack()
  await page.waitForTimeout(1200)
  check('Back POPS out of More', pathOf(page) === homePath, pathOf(page))
  await page.goForward()
  await page.waitForTimeout(1200)
  check('More is still there to go forward to', pathOf(page) === '/more', pathOf(page))

  // The defect that motivated the change: a sheet remounted every time, so a persona whose group
  // sits low in the tree re-scrolled past everything above it on every visit.
  //
  // Leave via the bottom-bar tab, NOT a nav link: Playwright scrolls a link into view before
  // clicking it, so clicking one down the list moves the offset first and the walk then "proves"
  // a restore to a position the user never parked at. The tab bar is fixed and always in view.
  await page.evaluate(() => window.scrollTo(0, 600))
  await page.waitForTimeout(400)
  const parked = await page.evaluate(() => window.scrollY)
  check('More scrolls far enough to matter', parked >= 600, String(parked))

  await page.getByRole('link', { name: 'Beranda', exact: true }).click({ timeout: 8000 })
  await page.waitForTimeout(1400)
  check('leaving More lands on another page', pathOf(page) !== '/more', pathOf(page))
  await page.goBack()
  await page.waitForTimeout(1600)
  const restored = await page.evaluate(() => window.scrollY)
  check('returning to More restores the offset (N4)', Math.abs(restored - parked) <= 4, `${parked} -> ${restored}`)
  await ctx.close()
})

await browser.close()
console.log(failures === 0 ? '\nNAV SMOKE OK' : `\nNAV SMOKE FAILED (${failures})`)
process.exit(failures === 0 ? 0 : 1)
