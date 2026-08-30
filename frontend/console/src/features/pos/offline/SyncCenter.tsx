/**
 * SyncCenter — a slide-in panel (mirrors features/pos/ParkedTray.tsx's layout/pattern) reachable
 * from a POS header button whose badge shows queued+rejected count. Lists every queued/replayed
 * offline sale (time, provisional total, status chip), a manual "retry now", and two review lists:
 * SYNCED_WITH_MISMATCH rows (provisional vs server total delta) and REJECTED rows (kept visible with
 * their server error, never silently dropped).
 *
 * Money rule (rule 8): every amount renders via formatMoney(). Strings rule (rule 9): every label is
 * an i18n key.
 */
import { useState, useSyncExternalStore } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Clock, RotateCw, TriangleAlert, X, XCircle } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { getSnapshot, subscribe } from './queue'
import { retryNow } from './sync'
import type { SaleQueueRow, SaleQueueStatus } from './db'

interface Props {
  locale: string
  onClose: () => void
}

export function SyncCenter({ locale, onClose }: Props) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  const rows = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
  const [retrying, setRetrying] = useState(false)

  const pending = rows.filter((r) => r.status === 'QUEUED' || r.status === 'SYNCING')
  const mismatches = rows.filter((r) => r.status === 'SYNCED_WITH_MISMATCH')
  const rejected = rows.filter((r) => r.status === 'REJECTED')

  async function handleRetry() {
    setRetrying(true)
    try {
      await retryNow()
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-black/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('offline.syncCenter.title')}
    >
      <div className="reveal flex h-full w-full max-w-sm flex-col bg-surface shadow-lg">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div className="flex items-center gap-2">
            <h2 className="font-display text-lg font-semibold text-ink">{t('offline.syncCenter.title')}</h2>
            {rows.length > 0 ? <Badge tone="neutral">{rows.length}</Badge> : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Retry action */}
        <div className="border-b border-line px-5 py-3">
          <Button
            variant="outline"
            className="w-full"
            disabled={retrying || pending.length === 0}
            onClick={handleRetry}
          >
            {retrying ? (
              <Spinner />
            ) : (
              <>
                <RotateCw className="size-4" aria-hidden="true" />
                {t('offline.syncCenter.retryNow')}
              </>
            )}
          </Button>
        </div>

        {/* List + summaries */}
        <div className="flex-1 overflow-y-auto overscroll-contain p-5">
          {rows.length === 0 ? (
            <p className="py-10 text-center text-sm text-ink-3">{t('offline.syncCenter.empty')}</p>
          ) : (
            <ul className="space-y-2">
              {rows.map((row) => (
                <QueueEntry key={row.idempotencyKey} row={row} locale={locale} />
              ))}
            </ul>
          )}

          {mismatches.length > 0 ? (
            <div className="mt-5 rounded-xl border border-amber/30 bg-amber-tint p-3">
              <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-amber-2">
                <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
                {t('offline.syncCenter.mismatchSummary', { count: mismatches.length })}
              </p>
              <ul className="space-y-1.5">
                {mismatches.map((row) => {
                  const server = row.serverGrandTotalMinor ?? row.provisional.grandTotalMinor
                  const delta = server - row.provisional.grandTotalMinor
                  return (
                    <li key={row.idempotencyKey} className="text-xs text-amber-2">
                      <div className="flex items-center justify-between">
                        <span>
                          {t('offline.syncCenter.provisionalLabel')}{' '}
                          {formatMoney(row.provisional.grandTotalMinor, row.provisional.currency, locale)}
                        </span>
                        <span className="tnum font-mono">
                          {formatMoney(server, row.provisional.currency, locale)}
                        </span>
                      </div>
                      <div className="tnum text-right font-mono opacity-80">
                        {t('offline.syncCenter.deltaLabel', {
                          delta: formatMoney(delta, row.provisional.currency, locale),
                        })}
                      </div>
                    </li>
                  )
                })}
              </ul>
            </div>
          ) : null}

          {rejected.length > 0 ? (
            <div className="mt-3 rounded-xl border border-loss/30 bg-tint-loss p-3">
              <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-loss">
                <XCircle className="size-3.5 shrink-0" aria-hidden="true" />
                {t('offline.syncCenter.rejectedSummary', { count: rejected.length })}
              </p>
              <ul className="space-y-1.5">
                {rejected.map((row) => (
                  <li key={row.idempotencyKey} className="text-xs text-loss">
                    <div className="tnum font-mono font-semibold">
                      {formatMoney(row.provisional.grandTotalMinor, row.provisional.currency, locale)}
                    </div>
                    <div>{row.lastError ?? t('offline.syncCenter.unknownError')}</div>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// QueueEntry
// ---------------------------------------------------------------------------

function QueueEntry({ row, locale }: { row: SaleQueueRow; locale: string }) {
  const { t } = useTranslation()
  const time = new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
    day: 'numeric',
    month: 'short',
  }).format(new Date(row.enqueuedAt))

  return (
    <li className="rounded-xl border border-line bg-surface px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-ink-3">{time}</span>
        <StatusChip status={row.status} />
      </div>
      <div className="mt-1 flex items-center justify-between gap-2">
        <span className="truncate text-sm font-semibold text-ink">
          {t(`offline.vertical.${row.vertical}`)}
        </span>
        <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
          {formatMoney(row.provisional.grandTotalMinor, row.provisional.currency, locale)}
        </span>
      </div>
    </li>
  )
}

function StatusChip({ status }: { status: SaleQueueStatus }) {
  const { t } = useTranslation()
  switch (status) {
    case 'QUEUED':
      return (
        <Badge tone="neutral" className="text-[10px] px-1.5 py-0">
          <Clock className="size-3" aria-hidden="true" />
          {t('offline.status.queued')}
        </Badge>
      )
    case 'SYNCING':
      return (
        <Badge tone="info" className="text-[10px] px-1.5 py-0">
          <RotateCw className="size-3 animate-spin motion-reduce:animate-none" aria-hidden="true" />
          {t('offline.status.syncing')}
        </Badge>
      )
    case 'SYNCED':
      return (
        <Badge tone="profit" className="text-[10px] px-1.5 py-0">
          <CheckCircle2 className="size-3" aria-hidden="true" />
          {t('offline.status.synced')}
        </Badge>
      )
    case 'SYNCED_WITH_MISMATCH':
      return (
        <Badge tone="amber" className="text-[10px] px-1.5 py-0">
          <TriangleAlert className="size-3" aria-hidden="true" />
          {t('offline.status.mismatch')}
        </Badge>
      )
    case 'REJECTED':
      return (
        <Badge tone="loss" className="text-[10px] px-1.5 py-0">
          <XCircle className="size-3" aria-hidden="true" />
          {t('offline.status.rejected')}
        </Badge>
      )
  }
}
