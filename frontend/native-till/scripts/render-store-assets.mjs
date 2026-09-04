// Render the Google Play graphic assets for the Native (Business) app from the in-app brand:
//   store-assets/icon-512.png            — 512×512 app icon (deep-cyan gradient + white trend mark)
//   store-assets/feature-1024x500.jpg    — 1024×500 feature graphic (opaque, JPEG = no alpha)
//
// Brand truth (frontend/console/src/index.css @theme + components/Wordmark.tsx):
//   cyan ramp 500 #0e8fab → 800 #064654, mark path "M4 18 L10 10 L14 14 L20 5", font Plus Jakarta Sans.
// Renders with playwright-core driving the system Chrome (no extra download; same recipe as the
// landing-shot harness). Run: node scripts/render-store-assets.mjs   (from frontend/native-till)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const outDir = join(app, 'store-assets')
mkdirSync(outDir, { recursive: true })

// playwright-core lives in the sibling console workspace (not native-till's deps) — resolve it there.
const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

// Embed Plus Jakarta Sans so the wordmark renders identically regardless of system fonts.
const fontPath = resolve(app, '../console/src/assets/fonts/PlusJakartaSans-var-latin.woff2')
const fontB64 = readFileSync(fontPath).toString('base64')
const fontFace = `@font-face{font-family:'Plus Jakarta Sans';font-weight:200 800;font-style:normal;src:url(data:font/woff2;base64,${fontB64}) format('woff2');}`

// The brand glyph — the upward trend line, white stroke, rounded caps (Wordmark.tsx BrandMark).
const mark = (size, stroke = 2.5) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round"><path d="M4 18 L10 10 L14 14 L20 5"/></svg>`

const ICON_HTML = `<!doctype html><html><head><meta charset="utf-8"><style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:512px;height:512px;overflow:hidden}
  .icon{width:512px;height:512px;display:grid;place-items:center;position:relative;
    background:linear-gradient(135deg,#0e8fab 0%,#0b7f99 42%,#064654 100%)}
  /* soft top-left sheen for depth, like the in-app gradient tile */
  .icon::before{content:'';position:absolute;inset:0;
    background:radial-gradient(120% 120% at 22% 18%, rgba(255,255,255,.22), rgba(255,255,255,0) 55%)}
  .mark{position:relative;filter:drop-shadow(0 8px 22px rgba(0,0,0,.22))}
</style></head><body>
  <div class="icon"><div class="mark">${mark(300, 2.6)}</div></div>
</body></html>`

const FEATURE_HTML = `<!doctype html><html><head><meta charset="utf-8"><style>
  ${fontFace}
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:1024px;height:500px;overflow:hidden;font-family:'Plus Jakarta Sans',sans-serif}
  .stage{width:1024px;height:500px;position:relative;display:flex;align-items:center;
    background:linear-gradient(120deg,#0b7f99 0%,#075165 55%,#053b47 100%)}
  /* oversized faint trend motif on the right */
  .motif{position:absolute;right:-40px;top:-30px;opacity:.10}
  .lockup{position:relative;padding-left:84px;color:#fff}
  .row{display:flex;align-items:center;gap:26px}
  .tile{width:104px;height:104px;border-radius:26px;display:grid;place-items:center;
    background:linear-gradient(135deg,#0e8fab,#064654);box-shadow:0 10px 30px rgba(0,0,0,.28)}
  .name{font-size:104px;font-weight:800;letter-spacing:-.03em;line-height:1}
  .tag{margin-top:30px;font-size:34px;font-weight:600;color:#bfe9f1;letter-spacing:.005em}
  .url{margin-top:16px;font-size:23px;font-weight:600;color:#6bc2d6;letter-spacing:.04em}
</style></head><body>
  <div class="stage">
    <div class="motif"><svg width="620" height="560" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 18 L10 10 L14 14 L20 5"/></svg></div>
    <div class="lockup">
      <div class="row">
        <div class="tile">${mark(58, 2.5)}</div>
        <div class="name">Native</div>
      </div>
      <div class="tag">Kasir · Akuntansi · Penggajian</div>
      <div class="url">app.native-app.my.id</div>
    </div>
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
  // Icon — PNG (alpha allowed).
  const iconPage = await browser.newPage({ viewport: { width: 512, height: 512 }, deviceScaleFactor: 1 })
  await iconPage.setContent(ICON_HTML, { waitUntil: 'networkidle' })
  await iconPage.waitForTimeout(150)
  await iconPage.screenshot({ path: join(outDir, 'icon-512.png'), type: 'png' })
  await iconPage.close()

  // Feature graphic — JPEG so it is guaranteed opaque (Play forbids alpha here).
  const fgPage = await browser.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 })
  await fgPage.setContent(FEATURE_HTML, { waitUntil: 'networkidle' })
  await fgPage.waitForTimeout(250)
  await fgPage.screenshot({ path: join(outDir, 'feature-1024x500.jpg'), type: 'jpeg', quality: 92 })
  await fgPage.close()

  console.log('✓ store assets written to', outDir)
  console.log('  - icon-512.png            (app icon, 512×512)')
  console.log('  - feature-1024x500.jpg    (feature graphic, 1024×500)')
} finally {
  await browser.close()
}
