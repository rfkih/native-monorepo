/**
 * AppUpdatePrompt — a slim, app-global SOFT prompt shown when a newer build has been deployed than
 * the one this client is running (ADR 0062). Never blocks: a dismissible bar with an Update action
 * that self-heals the cache and reloads to the latest bundle. Re-appears if an even newer build
 * ships after a dismiss (the dismissed id no longer matches the latest).
 *
 * Mounted once per app inside the fixed top banner rail (console app/App.tsx, employee App.tsx —
 * below OfflineBanner there, so simultaneous banners stack) covering every screen, including the
 * full-screen POS. Checks on mount, whenever the app returns to the foreground, and every 15 min —
 * so a long-open till (which never relaunches, so the shell-refetch fix can't help it) still learns
 * of a new deploy. A failed/offline check reads as "up to date" (fails closed — never nags).
 *
 * Strings: i18n keys only (rule 9).
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowUpCircle, X } from 'lucide-react'
import { APP_BUILD, fetchLatestBuild, isOutdated, reloadToLatest } from '@/lib/appVersion'

const CHECK_INTERVAL_MS = 15 * 60 * 1000

export function AppUpdatePrompt() {
  const { t } = useTranslation()
  const [latest, setLatest] = useState<string | null>(null)
  const [dismissed, setDismissed] = useState<string | null>(null)
  const [reloading, setReloading] = useState(false)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    async function check() {
      const build = await fetchLatestBuild(controller.signal)
      if (!cancelled && build) setLatest(build)
    }
    void check()
    const onVisible = () => {
      if (document.visibilityState === 'visible') void check()
    }
    document.addEventListener('visibilitychange', onVisible)
    const interval = window.setInterval(() => void check(), CHECK_INTERVAL_MS)
    return () => {
      cancelled = true
      controller.abort()
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(interval)
    }
  }, [])

  const show = !reloading && isOutdated(APP_BUILD, latest) && latest !== dismissed
  if (!show) return null

  return (
    <div
      role="status"
      className="flex items-center justify-center gap-3 border-b border-line bg-surface px-4 py-2 text-[13px] text-ink shadow-sm print:hidden"
    >
      <ArrowUpCircle className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
      <span className="font-semibold">{t('appUpdate.available')}</span>
      <button
        type="button"
        onClick={() => {
          setReloading(true)
          void reloadToLatest()
        }}
        className="rounded-lg bg-emerald px-3 py-1 text-[12px] font-bold text-on-emerald transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        {t('appUpdate.action')}
      </button>
      <button
        type="button"
        onClick={() => setDismissed(latest)}
        aria-label={t('common.close')}
        className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        <X className="size-4" aria-hidden="true" />
      </button>
    </div>
  )
}
