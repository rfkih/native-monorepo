// Turn the raw phone screenshots (screenshotproof/) into polished, Play-ready listing images:
// a branded cyan background + a short caption + the screenshot floating in a rounded phone frame,
// rendered at 1080×1920 (a clean 9:16 — Play's ideal phone ratio).
//
// Run:  node scripts/frame-store-shots.mjs
// Out:  store-assets/framed/NN-<name>.png   (upload the best 2–8 to the Play listing)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const repo = resolve(app, '..', '..')
const shotsDir = join(repo, 'screenshotproof')
const outDir = join(app, 'store-assets', 'framed')
mkdirSync(outDir, { recursive: true })

const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

const fontB64 = readFileSync(
  resolve(app, '../console/src/assets/fonts/PlusJakartaSans-var-latin.woff2'),
).toString('base64')
// A slide's image is either a raw screenshot in screenshotproof/ (`file`) or an absolute path to an
// edited version (`abs`, e.g. the profit-edited Laba-Rugi). PNG vs JPEG is inferred from the ext.
const dataUri = (s) => {
  const path = s.abs ?? join(shotsDir, s.file)
  const mime = path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg'
  return `data:${mime};base64,${readFileSync(path).toString('base64')}`
}

// Ordered slides: file → caption + subcaption (Indonesian, the primary market). Best hero first.
const SLIDES = [
  { file: '7af78839-3fe0-4b19-8634-790577c6bec3.jpg', title: 'Kasir cepat, satu ketuk', sub: 'Menu, varian & diskon dalam sekali sentuh' },
  { file: '79fb7756-9ec5-45ab-959b-613a3abbeb2b.jpg', title: 'Pantau penjualan real-time', sub: 'Rekap tunai & non-tunai tiap hari' },
  { file: '79db9258-c343-41b2-b424-1acc1bb57af5.jpg', title: 'Cetak struk dari HP', sub: 'Printer Bluetooth atau USB, tanpa aplikasi lain' },
  // Uses the profit-edited version (scripts/edit-labarugi.mjs) instead of the raw loss screenshot.
  { abs: join(app, 'store-assets', 'edited', 'laba-rugi-profit.png'), title: 'Laporan keuangan otomatis', sub: 'Laba-rugi langsung dari transaksi' },
  // Uses the profit-edited version (scripts/edit-beranda.mjs) instead of the raw loss screenshot.
  { abs: join(app, 'store-assets', 'edited', 'beranda-profit.png'), title: 'Bisnismu dalam satu layar', sub: 'Multi-outlet, semua terkonsolidasi' },
  { file: '68f3685d-861e-42d5-af05-1c891272c562.jpg', title: 'Atur tim, peran & akses', sub: 'Pemilik, manajer, kasir — terpisah rapi' },
]

const slideHtml = (s) => `<!doctype html><html><head><meta charset="utf-8"><style>
  @font-face{font-family:'PJS';font-weight:200 800;src:url(data:font/woff2;base64,${fontB64}) format('woff2')}
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:1080px;height:1920px;overflow:hidden;font-family:'PJS',sans-serif}
  .slide{width:1080px;height:1920px;position:relative;overflow:hidden;
    background:linear-gradient(160deg,#0e8fab 0%,#0b7f99 40%,#064654 100%);
    display:flex;flex-direction:column;align-items:center}
  .slide::before{content:'';position:absolute;right:-160px;top:-120px;width:900px;height:900px;
    background:radial-gradient(circle at center, rgba(255,255,255,.10), rgba(255,255,255,0) 60%)}
  .cap{position:relative;margin-top:120px;width:900px;text-align:center;color:#fff}
  .title{font-size:66px;line-height:1.12;font-weight:800;letter-spacing:-.02em}
  .sub{margin-top:22px;font-size:32px;font-weight:600;color:#cdeef5;line-height:1.3}
  .phone{position:relative;margin-top:88px;width:560px;border-radius:52px;padding:14px;
    background:#0a1417;box-shadow:0 50px 110px rgba(0,0,0,.5);border:1px solid rgba(255,255,255,.12)}
  .phone img{display:block;width:100%;border-radius:40px}
</style></head><body>
  <div class="slide">
    <div class="cap"><div class="title">${s.title}</div><div class="sub">${s.sub}</div></div>
    <div class="phone"><img src="${dataUri(s)}"/></div>
  </div>
</body></html>`

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

const browser = await chromium.launch({ executablePath, headless: true })
try {
  for (let i = 0; i < SLIDES.length; i++) {
    const s = SLIDES[i]
    const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 })
    await page.setContent(slideHtml(s), { waitUntil: 'networkidle' })
    await page.waitForTimeout(150)
    const name = s.file ? s.file.slice(0, 8) : s.abs.split(/[\\/]/).pop().replace(/\.[^.]+$/, '')
    const outFile = join(outDir, `${String(i + 1).padStart(2, '0')}-${name}.png`)
    await page.screenshot({ path: outFile, type: 'png' })
    await page.close()
    console.log(`[frame] ${s.title} → ${outFile}`)
  }
  console.log('\n[frame] done →', outDir)
} finally {
  await browser.close()
}
