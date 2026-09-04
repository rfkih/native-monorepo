// Doctor the illustrative Laba-Rugi screenshot so the demo shows a PROFIT instead of a loss:
//   Pendapatan 682.000 → 1.582.000 (now above Beban 1.047.141), Beban subtext 153,5% → 66,2%.
// The figures are already flagged "Angka ilustratif" (demo data), so this only makes the DEMO more
// flattering for the store listing — the app genuinely renders this screen; only the sample numbers
// change. Overlays the app's own JetBrains Mono / Plus Jakarta Sans fonts so the edit is seamless.
//
// Run:  node scripts/edit-labarugi.mjs
// Out:  store-assets/edited/laba-rugi-profit.png  (738×1600, same as the source)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const repo = resolve(app, '..', '..')
const outDir = join(app, 'store-assets', 'edited')
mkdirSync(outDir, { recursive: true })
const OUT = join(outDir, 'laba-rugi-profit.png')

const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

const W = 738, H = 1600
const img = readFileSync(join(repo, 'screenshotproof', 'e5911937-7554-485f-930d-25dad488b73d.jpg')).toString('base64')
const mono = readFileSync(resolve(app, '../console/src/assets/fonts/JetBrainsMono-var-latin.woff2')).toString('base64')
const sans = readFileSync(resolve(app, '../console/src/assets/fonts/PlusJakartaSans-var-latin.woff2')).toString('base64')

const html = `<!doctype html><html><head><meta charset=utf-8><style>
@font-face{font-family:'JBM';font-weight:100 800;src:url(data:font/woff2;base64,${mono}) format('woff2')}
@font-face{font-family:'PJS';font-weight:200 800;src:url(data:font/woff2;base64,${sans}) format('woff2')}
*{margin:0;padding:0;box-sizing:border-box}
body{width:${W}px;height:${H}px}
.stage{position:relative;width:${W}px;height:${H}px}
.stage>img{width:${W}px;height:${H}px;display:block}
.patch{position:absolute;background:#ffffff}
.num{position:absolute;font-family:'JBM',monospace;font-weight:500;color:#0e1116;font-size:55px;line-height:1;white-space:pre}
.sub{position:absolute;font-family:'PJS',sans-serif;font-weight:600;color:#7c8896;font-size:27px;line-height:1;white-space:pre}
</style></head><body>
<div class="stage">
  <img src="data:image/jpeg;base64,${img}">
  <div class="patch" style="left:78px;top:884px;width:410px;height:82px"></div>
  <div class="num" style="left:86px;top:894px">Rp 1.582.000</div>
  <div class="patch" style="left:80px;top:1205px;width:340px;height:44px"></div>
  <div class="sub" style="left:84px;top:1210px">66,2% dari pendapatan</div>
</div>
</body></html>`

const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const p = await b.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 })
await p.setContent(html, { waitUntil: 'networkidle' })
await p.waitForTimeout(120)
await p.screenshot({ path: OUT })
await b.close()
console.log('✓ wrote', OUT)
