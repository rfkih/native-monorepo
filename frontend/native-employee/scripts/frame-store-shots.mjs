// Turn the raw /me screenshots (store-assets/screenshots/) into polished, Play-ready listing images:
// a branded cyan background + a short caption + the screenshot floating in a rounded phone frame,
// rendered at 1080×1920 (a clean 9:16 — Play's ideal phone ratio).
//
// This is the Employee app's twin of native-till/scripts/frame-store-shots.mjs — same visual
// language on purpose, so the two Play listings read as one product family. The only structural
// difference: the Till app frames raw camera-roll captures out of the repo's screenshotproof/ dir,
// while this app's shots are already clean 1080×1920 renders committed under store-assets/.
//
// Run:  node scripts/frame-store-shots.mjs      (npm run store:shots)
// Out:  store-assets/framed/NN-<name>.png       (upload all four to the Play listing)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const shotsDir = join(app, 'store-assets', 'screenshots')
const outDir = join(app, 'store-assets', 'framed')
mkdirSync(outDir, { recursive: true })

// playwright-core + the brand font live in the console workspace; this app has neither as a dep.
const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

const fontB64 = readFileSync(
  resolve(app, '../console/src/assets/fonts/PlusJakartaSans-var-latin.woff2'),
).toString('base64')

const dataUri = (file) =>
  `data:image/png;base64,${readFileSync(join(shotsDir, file)).toString('base64')}`

// Ordered slides: file → caption + subcaption (Indonesian, the primary market). Best hero first.
// Wording tracks frontend/native-employee/PLAY-STORE.md §4 so the listing and the shots agree.
const SLIDES = [
  { file: '01-beranda.png', title: 'Semua soal kerjamu', sub: 'Slip gaji, cuti, dan klaim dalam satu layar' },
  { file: '02-slip-gaji.png', title: 'Slip gaji tiap periode', sub: 'Lengkap dengan ringkasan tahun berjalan' },
  { file: '03-cuti-lembur.png', title: 'Ajukan cuti & lembur', sub: 'Sisa saldo cuti terlihat kapan saja' },
  { file: '04-klaim.png', title: 'Klaim pengeluaran', sub: 'Ajukan lalu pantau statusnya sampai cair' },
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
    <div class="phone"><img src="${dataUri(s.file)}"/></div>
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
    const name = s.file.replace(/^\d+-/, '').replace(/\.[^.]+$/, '')
    const outFile = join(outDir, `${String(i + 1).padStart(2, '0')}-${name}.png`)
    await page.screenshot({ path: outFile, type: 'png' })
    await page.close()
    console.log(`[frame] ${s.title} → ${outFile}`)
  }
  console.log('\n[frame] done →', outDir)
} finally {
  await browser.close()
}
