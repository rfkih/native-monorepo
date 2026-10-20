// Stamp the offline page's retry origin from the resolved Capacitor config (field bug 2026-10-19).
//
// www/error.html (server.errorPath) is served from the LOCAL WebView origin (https://localhost) and
// retries by navigating to its ORIGIN. That used to be a hand-kept literal — the UAT funnel origin —
// so every PROD install that cold-started offline (or hit a Cloudflare 5xx) retried
// to the UAT host. That host is neither server.url's nor in allowNavigation, so Capacitor's
// Bridge.launchIntent threw it into the SYSTEM BROWSER, and the page's 8s auto-retry kept re-opening
// Chrome on every return to the still-alive app ("the app keeps redirecting to the web; a reinstall
// fixes it, then it comes back").
//
// Runs as the `capacitor:copy:after` hook (package.json), so EVERY `cap sync` / `cap copy` — through
// build-app.mjs or not — rewrites the SYNCED copy (android/app/src/main/assets/public, gitignored)
// from the same config that produced server.url. The tracked www/error.html keeps the UAT default.
// build-app.mjs re-reads the stamped page and refuses to build if the two ever disagree. The page
// itself prefers Capacitor's runtime WEBVIEW_SERVER_URL, so a path that skips this hook (an OTA bundle
// zipped from www/) still retries home; the stamped value is the fallback.

import { existsSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ORIGIN_ASSIGNMENT = /var STAMPED_ORIGIN = '([^'\n]*)'/g

/** The origin the error page must retry: server.url's origin, else the local (bundled) origin. */
export function errorPageOrigin(config) {
  const server = config?.server ?? {}
  if (server.url) return new URL(server.url).origin
  return `${server.androidScheme ?? 'https'}://${server.hostname ?? 'localhost'}`
}

function onlyMatch(html) {
  const matches = [...html.matchAll(ORIGIN_ASSIGNMENT)]
  if (matches.length !== 1) {
    throw new Error(
      `error page must contain exactly one \`var STAMPED_ORIGIN = '…'\` assignment, found ${matches.length}`,
    )
  }
  return matches[0]
}

/** The origin currently baked into an error page. */
export function readErrorOrigin(html) {
  return onlyMatch(html)[1]
}

/** The error page with its retry origin replaced. */
export function stampErrorOrigin(html, origin) {
  if (!/^https?:\/\/[A-Za-z0-9.-]+(:\d+)?$/.test(origin)) {
    throw new Error(`refusing to stamp a malformed origin into the error page: ${origin}`)
  }
  const match = onlyMatch(html)
  return html.slice(0, match.index) + `var STAMPED_ORIGIN = '${origin}'` + html.slice(match.index + match[0].length)
}

/** Where `cap copy` put the error page, or null when the config has no errorPath. */
export function syncedErrorPage(rootDir, config) {
  const errorPath = config?.server?.errorPath
  return errorPath ? join(rootDir, 'android', 'app', 'src', 'main', 'assets', 'public', errorPath) : null
}

/** Read the config `cap copy` just wrote next to the synced web assets. */
export function syncedConfig(rootDir) {
  return JSON.parse(
    readFileSync(join(rootDir, 'android', 'app', 'src', 'main', 'assets', 'capacitor.config.json'), 'utf8'),
  )
}

// --- hook entry point ---------------------------------------------------------------------------
// realpath on BOTH sides: Node resolves the main module through realpath, so a junction / subst drive
// in the path would otherwise make the guard silently skip the stamp.
const isMain = (() => {
  try {
    return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
})()
if (isMain) {
  const platform = process.env.CAPACITOR_PLATFORM_NAME
  if (platform && platform !== 'android') {
    console.log(`[stamp-error-origin] platform ${platform} — nothing to stamp`)
    process.exit(0)
  }
  const rootDir = process.env.CAPACITOR_ROOT_DIR ?? resolve(fileURLToPath(import.meta.url), '..', '..')
  const config = process.env.CAPACITOR_CONFIG ? JSON.parse(process.env.CAPACITOR_CONFIG) : syncedConfig(rootDir)
  const page = syncedErrorPage(rootDir, config)
  if (!page) {
    console.log('[stamp-error-origin] no server.errorPath configured — nothing to stamp')
    process.exit(0)
  }
  if (!existsSync(page)) {
    console.error(`[stamp-error-origin] synced error page not found: ${page}`)
    process.exit(1)
  }
  const origin = errorPageOrigin(config)
  writeFileSync(page, stampErrorOrigin(readFileSync(page, 'utf8'), origin))
  console.log(`[stamp-error-origin] ${page} → retry origin ${origin}`)
}
