// Render the LEGACY raster launcher icons for the Employee app, at every density.
//
// WHY THIS EXISTS: `mipmap-anydpi-v26/ic_launcher.xml` (the adaptive icon) is only consulted on
// API 26+. This app's `minSdkVersion` is 24, so Android 7.0/7.1 launchers fall back to the PNGs in
// `mipmap-<density>/` — which Capacitor scaffolds with ITS OWN logo. Until this script ran, every
// Android 7 device showed the blue Capacitor mark instead of Native. Re-run it whenever the glyph
// or the brand gradient changes, and keep it in step with the vectors in
// `android/app/src/main/res/drawable/ic_launcher_{bg_brand,fg_employee}.xml`.
//
// Three files per density, matching what the manifest and the launcher look for:
//   ic_launcher.png             rounded-square brand tile + glyph   (48/72/96/144/192)
//   ic_launcher_round.png       circular variant, same artwork      (48/72/96/144/192)
//   ic_launcher_foreground.png  glyph only, transparent, 108dp grid (108/162/216/324/432)
//
// Each size is rendered at its NATIVE pixel size rather than downscaled from one large master:
// Chrome hints the vector strokes per size, which keeps the 48px mdpi icon crisp.
//
// Run from frontend/native-employee:  node scripts/render-launcher-icons.mjs   (npm run icons)

import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '..')
const resDir = join(app, 'android', 'app', 'src', 'main', 'res')

// playwright-core lives in the sibling console workspace (not this app's deps) — resolve it there.
const requireFromConsole = createRequire(resolve(app, '../console/package.json'))
const { chromium } = requireFromConsole('playwright-core')

// Brand truth (frontend/console/src/index.css @theme): cyan ramp 500 #0E8FAB → 800 #064654.
const GRADIENT = 'linear-gradient(135deg,#0E8FAB 0%,#0B7F99 42%,#064654 100%)'

// The EMPLOYEE glyph — lucide `user`, mirroring drawable/ic_launcher_fg_employee.xml. The Business
// app uses the trend mark instead; that difference is the whole point (see the drawable's comment).
const GLYPH = `<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>`

const glyphSvg = (px, stroke) =>
  `<svg width="${px}" height="${px}" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round">${GLYPH}</svg>`

// Densities. `icon` = the full launcher bitmap; `fg` = the 108dp adaptive-foreground grid.
const DENSITIES = [
  { dir: 'mipmap-mdpi', icon: 48, fg: 108 },
  { dir: 'mipmap-hdpi', icon: 72, fg: 162 },
  { dir: 'mipmap-xhdpi', icon: 96, fg: 216 },
  { dir: 'mipmap-xxhdpi', icon: 144, fg: 324 },
  { dir: 'mipmap-xxxhdpi', icon: 192, fg: 432 },
]

// Stroke weight is specified in the 24-unit viewport, so it scales with the glyph automatically.
// 2.4 matches the vector drawables; nudged up at mdpi where a 2.4 stroke lands on ~1.2 physical px.
const strokeFor = (px) => (px <= 48 ? 2.8 : 2.4)

/** Full launcher bitmap: brand tile + centred glyph. `round` swaps the rounded square for a circle. */
const iconHtml = (px, round) => `<!doctype html><html><head><meta charset="utf-8"><style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:${px}px;height:${px}px;overflow:hidden;background:transparent}
  .tile{width:${px}px;height:${px}px;display:grid;place-items:center;position:relative;
    overflow:hidden;background:${GRADIENT};
    border-radius:${round ? '50%' : `${Math.round(px * 0.22)}px`}}
  /* the same top-left sheen the store icon and the in-app Wordmark tile carry */
  .tile::before{content:'';position:absolute;inset:0;
    background:radial-gradient(120% 120% at 22% 18%, rgba(255,255,255,.22), rgba(255,255,255,0) 55%)}
  .mark{position:relative;line-height:0}
</style></head><body>
  <div class="tile"><div class="mark">${glyphSvg(Math.round(px * 0.56), strokeFor(px))}</div></div>
</body></html>`

/**
 * Adaptive foreground: transparent canvas, glyph only. The launcher masks and scales this against
 * the background layer, so the glyph must stay inside the 66/108 guaranteed-visible circle. 0.51 of
 * the canvas ≈ 55/108 — the same optical size as the `scaleX 2.3` group in the vector drawables.
 */
const foregroundHtml = (px) => `<!doctype html><html><head><meta charset="utf-8"><style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:${px}px;height:${px}px;overflow:hidden;background:transparent}
  .canvas{width:${px}px;height:${px}px;display:grid;place-items:center;line-height:0}
</style></head><body>
  <div class="canvas">${glyphSvg(Math.round(px * 0.51), strokeFor(px * 0.51))}</div>
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
  for (const d of DENSITIES) {
    const dir = join(resDir, d.dir)
    mkdirSync(dir, { recursive: true })

    const shots = [
      { file: 'ic_launcher.png', px: d.icon, html: iconHtml(d.icon, false) },
      { file: 'ic_launcher_round.png', px: d.icon, html: iconHtml(d.icon, true) },
      { file: 'ic_launcher_foreground.png', px: d.fg, html: foregroundHtml(d.fg) },
    ]

    for (const s of shots) {
      const page = await browser.newPage({
        viewport: { width: s.px, height: s.px },
        deviceScaleFactor: 1,
      })
      await page.setContent(s.html, { waitUntil: 'networkidle' })
      await page.waitForTimeout(60)
      // omitBackground keeps the round icon's corners and the foreground canvas transparent.
      await page.screenshot({ path: join(dir, s.file), type: 'png', omitBackground: true })
      await page.close()
    }
    console.log(`[icons] ${d.dir} → ic_launcher ${d.icon}px · round ${d.icon}px · foreground ${d.fg}px`)
  }
  console.log('\n[icons] done →', resDir)
} finally {
  await browser.close()
}
