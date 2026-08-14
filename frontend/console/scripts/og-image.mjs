// Renders public/og-image.svg → public/og-image.png at the Open Graph card size (1200×630).
// Social scrapers (WhatsApp, Facebook, LinkedIn, Slack, X) do NOT render SVG share previews, so the
// PNG is the file index.html points og:image / twitter:image at. Re-run whenever the SVG changes:
//   node scripts/og-image.mjs
// playwright-core drives the system Chrome (same recipe as scripts/design-shots.mjs).
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const W = 1200
const H = 630

const svg = readFileSync('public/og-image.svg', 'utf8')
const browser = await chromium.launch({ executablePath: CHROME })
try {
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 })
  // Reset default margins so the 1200×630 SVG fills the viewport exactly.
  await page.setContent(
    `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0}svg{display:block}</style></head><body>${svg}</body></html>`,
    { waitUntil: 'networkidle' },
  )
  await page.screenshot({ path: 'public/og-image.png', clip: { x: 0, y: 0, width: W, height: H } })
  console.log('wrote public/og-image.png')
} finally {
  await browser.close()
}
