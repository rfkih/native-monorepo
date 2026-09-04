/**
 * OfflineBanner — a slim, app-global banner (mounted once in app/App.tsx's fixed top banner rail,
 * shared with AppUpdatePrompt so simultaneous banners STACK instead of covering each other; the
 * rail makes it visible on every screen, including the full-screen POS surfaces which render
 * outside the dashboard Shell). Two states only:
 *   - offline right now → "selling continues — cash only, prices provisional" + the queued count.
 *   - back online with pending queue items → a sync-in-progress line.
 * Renders nothing once there is nothing to say (online, empty queue) — never adds a permanent
 * layout reservation.
 *
 * A11y: `role="status"` (polite live region — the offline/online transition is announced without
 * stealing focus). Motion: the syncing spinner honours prefers-reduced-motion via Tailwind's
 * `motion-reduce:animate-none`.
 */
import { useTranslation } from 'react-i18next'
import { RefreshCw, WifiOff } from 'lucide-react'
import { useOffline } from './useOffline'

export function OfflineBanner() {
  const { t } = useTranslation()
  const { offline, queuedCount, syncingCount } = useOffline()

  if (!offline && queuedCount === 0 && syncingCount === 0) return null

  if (offline) {
    return (
      <div
        role="status"
        className="flex items-center justify-center gap-2 border-b border-amber/30 bg-amber-tint px-4 py-2 text-center text-[13px] font-semibold text-amber-2 print:hidden"
      >
        <WifiOff className="size-4 shrink-0" aria-hidden="true" />
        <span>{t('offline.banner.offline')}</span>
        {queuedCount > 0 ? (
          <span className="tnum font-mono text-[12px] font-normal opacity-90">
            {t('offline.banner.queuedCount', { count: queuedCount })}
          </span>
        ) : null}
      </div>
    )
  }

  // Back online with items still syncing — a brief, less urgent confirmation bar.
  return (
    <div
      role="status"
      className="flex items-center justify-center gap-2 border-b border-emerald-line bg-emerald-tint px-4 py-2 text-center text-[13px] font-semibold text-emerald-2 print:hidden"
    >
      <RefreshCw className="size-4 shrink-0 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      <span>{t('offline.banner.syncing', { count: queuedCount + syncingCount })}</span>
    </div>
  )
}
