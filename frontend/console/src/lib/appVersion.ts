/**
 * appVersion.ts — the running bundle's build identity + the "is a newer deploy live?" check that
 * powers the soft update prompt (ADR 0062).
 *
 * `APP_BUILD` is stamped into the bundle at build time (vite `define`, see vite.config.ts). The
 * deployed origin advertises its CURRENT build as `/version.json`; when the two differ, the running
 * client is behind the latest deploy. This is the reliable signal on the Native shell, which has NO
 * service worker (ADR 0043) and otherwise silently keeps a stale HTTP-cached shell across launches.
 */

// Injected by vite `define` at build time (see vite.config.ts). Declared here (module-local) rather
// than as a global ambient so it resolves under `tsc -b` regardless of d.ts include order.
declare const __APP_BUILD__: string

/** The build id baked into THIS bundle. `'dev'` only in a non-defined context (never in a build). */
export const APP_BUILD: string = typeof __APP_BUILD__ === 'string' ? __APP_BUILD__ : 'dev'

/**
 * Pure: is `current` behind `latest`? True only when we actually learned a DIFFERENT latest build.
 * A null/absent/equal latest (offline, fetch failed, dev with no version.json) is treated as
 * up-to-date — the prompt fails CLOSED (never nags on a failed check).
 */
export function isOutdated(current: string, latest: string | null | undefined): boolean {
  return latest != null && latest !== '' && latest !== current
}

/**
 * Fetch the deployed origin's current build id from /version.json. `cache: 'no-store'` so a stale
 * HTTP cache (native shell) or the browser can never hand back an old copy. Returns null on any
 * failure (offline, 404 in dev, malformed) — the caller treats null as "up to date".
 */
export async function fetchLatestBuild(signal?: AbortSignal): Promise<string | null> {
  try {
    const res = await fetch('/version.json', { cache: 'no-store', signal })
    if (!res.ok) return null
    const data: unknown = await res.json()
    const build = (data as { build?: unknown } | null)?.build
    return typeof build === 'string' && build !== '' ? build : null
  } catch {
    return null
  }
}

/**
 * Self-healing reload to the latest bundle: tear down the service worker + its caches (browser
 * only; the native shell has none, so these are no-ops there), then reload. The reload re-fetches
 * the no-store shell → the latest immutable /assets/ chunks. Best-effort — any teardown failure
 * still falls through to the reload.
 */
export async function reloadToLatest(): Promise<void> {
  try {
    if ('serviceWorker' in navigator) {
      const regs = await navigator.serviceWorker.getRegistrations()
      await Promise.all(regs.map((r) => r.unregister()))
    }
    if ('caches' in window) {
      const keys = await caches.keys()
      await Promise.all(keys.map((k) => caches.delete(k)))
    }
  } catch {
    /* best-effort teardown — reload anyway */
  }
  window.location.reload()
}
