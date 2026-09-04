// Doctor the illustrative Beranda (dashboard) screenshot so the demo shows a PROFIT, consistent with
// the edited Laba-Rugi (scripts/edit-labarugi.mjs):
//   RUGI BERSIH -Rp 377.141  →  LABA BERSIH Rp 534.859
//   Pendapatan 670.000 → 1.582.000, Margin bersih -56,3% → 33,8%  (Beban 1.047.141 unchanged)
// Figures are flagged "Angka ilustratif" (demo data); this only makes the DEMO flattering for the
// listing. Patches use the card's exact bg (#0e1116) + the app's own JBM/PJS fonts, so it's seamless.
//
// Run:  node scripts/edit-beranda.mjs
// Out:  store-assets/edited/beranda-profit.png  (738×1600)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const repo = resolve(app, '..', '..')
const outDir = join(app, 'store-assets', 'edited')
mkdirSync(outDir, { recursive: true })
const OUT = join(outDir, 'beranda-profit.png')

const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

const W = 738, H = 1600, CARD = '#0e1116'
const img = readFileSync(join(repo, 'screenshotproof', '1ed674f0-766d-4798-9fd8-e711fa563b86.jpg')).toString('base64')
const mono = readFileSync(resolve(app, '../console/src/assets/fonts/JetBrainsMono-var-latin.woff2')).toString('base64')
const sans = readFileSync(resolve(app, '../console/src/assets/fonts/PlusJakartaSans-var-latin.woff2')).toString('base64')

const html = `<!doctype html><html><head><meta charset=utf-8><style>
@font-face{font-family:'JBM';font-weight:100 800;src:url(data:font/woff2;base64,${mono}) format('woff2')}
@font-face{font-family:'PJS';font-weight:200 800;src:url(data:font/woff2;base64,${sans}) format('woff2')}
*{margin:0;padding:0;box-sizing:border-box}
body{width:${W}px;height:${H}px}
.stage{position:relative;width:${W}px;height:${H}px}
.stage>img{width:${W}px;height:${H}px;display:block}
.patch{position:absolute;background:${CARD}}
.lbl{position:absolute;font-family:'PJS',sans-serif;font-weight:700;color:#8a93a0;white-space:pre}
.big{position:absolute;font-family:'JBM',monospace;font-weight:500;color:#fff;white-space:pre;line-height:1}
.stat{position:absolute;font-family:'JBM',monospace;font-weight:500;color:#fff;white-space:pre;line-height:1}
</style></head><body>
<div class="stage">
  <img src="data:image/jpeg;base64,${img}">
  <div class="patch" style="left:70px;top:626px;width:432px;height:36px"></div>
  <div class="lbl" style="left:74px;top:632px;font-size:20px;letter-spacing:2.5px">LABA BERSIH · AGUSTUS 2026</div>
  <div class="patch" style="left:90px;top:660px;width:412px;height:92px"></div>
  <div class="big" style="left:100px;top:672px;font-size:58px">Rp 534.859</div>
  <div class="patch" style="left:70px;top:836px;width:200px;height:54px"></div>
  <div class="stat" style="left:74px;top:846px;font-size:33px">1.582.000</div>
  <div class="patch" style="left:462px;top:836px;width:150px;height:54px"></div>
  <div class="stat" style="left:468px;top:846px;font-size:33px">33,8%</div>
</div>
</body></html>`

const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const p = await b.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 })
await p.setContent(html, { waitUntil: 'networkidle' })
await p.waitForTimeout(120)
await p.screenshot({ path: OUT })
await b.close()
console.log('✓ wrote', OUT)
