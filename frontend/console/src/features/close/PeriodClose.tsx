import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, TriangleAlert, X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { currentPeriod, formatPeriod } from '@/lib/period'
import { useCloseHistory, useClosePeriod, type CloseResponse } from './api'

/**
 * Close-period confirmation dialog.
 * Sends POST /api/v1/closes with the current period. baseCurrency is sent as an optional
 * cross-check from the session; the ledger is still the source of truth on the backend.
 */
function ConfirmCloseDialog({
  period,
  baseCurrency,
  companyId,
  actor,
  onClose,
  onResult,
}: {
  period: string
  baseCurrency: string
  companyId: string
  actor: string
  onClose: () => void
  onResult: (r: CloseResponse) => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const mutation = useClosePeriod({ companyId, actor })

  function handleConfirm() {
    mutation.mutate(
      { period, baseCurrency },
      {
        onSuccess: (data) => {
          if (data) onResult(data)
          onClose()
        },
      },
    )
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm sm:items-center"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-md p-6 max-sm:sheet-up max-sm:max-h-[92dvh] max-sm:max-w-full max-sm:overflow-y-auto max-sm:rounded-b-none max-sm:rounded-t-[26px]">
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('close.confirmDialog.title')}
          </h2>
          <p className="text-sm text-ink-2">
            {t('close.confirmDialog.body', { period: formatPeriod(period, locale) })}
          </p>

          {mutation.isError ? (
            <p className="text-sm text-loss">{t('close.confirmDialog.errorTitle')}</p>
          ) : null}

          <div className="flex justify-end gap-3">
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="button" onClick={handleConfirm} disabled={mutation.isPending}>
              {mutation.isPending
                ? t('close.confirmDialog.submitting')
                : t('close.confirmDialog.confirm')}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  )
}

/**
 * Period Close page — restyled to the Native design system.
 * "Open period" gradient banner + history table with Closed/Reconciled/First-close columns.
 * All data hooks, query calls, loading/empty/error branches, and the confirm dialog are intact.
 */
export function PeriodClose() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const isPhone = useIsPhone()
  const locale = localeOf(i18n.language)
  const [showConfirm, setShowConfirm] = useState(false)
  const [lastResult, setLastResult] = useState<CloseResponse | null>(null)

  const query = useCloseHistory({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('close.noCompany')} hint={t('close.noCompanyHint')} />
  }

  const items = query.data ?? []
  const period = currentPeriod()

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('close.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('close.subtitle')}</p>
        </div>
      </div>

      {/* Open period banner */}
      <Card className="bg-gradient-to-br from-surface to-brand-50 border-brand-100 flex flex-wrap items-center justify-between gap-4 rounded-[20px] border p-7 max-sm:p-5">
        <div className="space-y-2">
          <span className="inline-flex rounded-full bg-emerald px-2.5 py-1 text-[11px] font-bold text-on-emerald">
            {t('close.openPeriod')}
          </span>
          <div className="font-display text-[22px] font-bold text-ink">
            {formatPeriod(period, locale)}
          </div>
          <p className="text-sm text-ink-3">{t('close.subtitle')}</p>
        </div>
        <Button
          type="button"
          className="max-sm:h-[52px] max-sm:w-full max-sm:rounded-[15px]"
          onClick={() => setShowConfirm(true)}
        >
          {t('close.closePeriod')}
        </Button>
      </Card>

      {/* Result toast */}
      {lastResult ? (
        <Card className="flex items-start gap-3 border-profit/25 bg-tint-profit p-4">
          <Check className="mt-0.5 size-4 shrink-0 text-profit-ink" />
          <p className="text-sm text-ink">
            {lastResult.firstClose
              ? t('close.result.success', { period: formatPeriod(lastResult.period, locale) })
              : t('close.result.idempotent', { period: formatPeriod(lastResult.period, locale) })}
          </p>
          <button
            type="button"
            aria-label={t('common.cancel')}
            className="ml-auto text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            onClick={() => setLastResult(null)}
          >
            <X className="size-4" />
          </button>
        </Card>
      ) : null}

      {/* Error / loading / history table */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('close.error')}</Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : items.length === 0 ? (
        <EmptyState title={t('close.empty')} hint={t('close.emptyHint')} />
      ) : isPhone ? (
        /* Phone (Native Console Android): the 4-column history grid becomes stacked cards —
           period + currency on top, then a badge row (Closed / Reconciled / First close /
           Illustrative) with the exact tone mapping of the desktop table. */
        <div className="flex flex-col gap-2">
          {items.map((item) => (
            <Card key={item.closeId} className="p-3.5">
              <div className="flex items-center justify-between gap-3">
                <span className="text-[14.5px] font-bold text-ink">
                  {formatPeriod(item.period, locale)}
                </span>
                <span className="font-mono text-[12px] font-semibold text-ink-3">
                  {item.baseCurrency}
                </span>
              </div>
              <div className="mt-2.5 flex flex-wrap gap-1.5">
                <span className="inline-flex items-center gap-1 rounded-full bg-tint-profit px-2.5 py-1 text-xs font-semibold text-profit-ink">
                  <Check className="size-3" aria-hidden />
                  {t('close.badge.closed')}
                </span>
                {item.reconciled ? (
                  <Badge tone="profit">
                    <Check className="size-3" /> {t('close.badge.reconciled')}
                  </Badge>
                ) : (
                  <Badge tone="neutral">{t('close.badge.notReconciled')}</Badge>
                )}
                {item.firstClose ? <Badge tone="info">{t('close.badge.firstClose')}</Badge> : null}
                {item.usesIllustrativeRules ? (
                  <Badge tone="amber">
                    <TriangleAlert className="size-3" /> {t('close.illustrative')}
                  </Badge>
                ) : null}
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <Card className="overflow-hidden">
          {/* History table */}
          <div className="overflow-x-auto">
            {/* Header */}
            <div className="grid grid-cols-[2fr_1.5fr_1.5fr_1.5fr] border-b border-line bg-paper/60 px-5 py-3">
              <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('close.period')}
              </span>
              <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('close.baseCurrency')}
              </span>
              <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('close.reconciled')}
              </span>
              <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('close.firstClose')}
              </span>
            </div>

            {/* Rows */}
            {items.map((item) => (
              <div
                key={item.closeId}
                className="grid grid-cols-[2fr_1.5fr_1.5fr_1.5fr] items-center border-b border-line/60 px-5 py-3 last:border-0 hover:bg-hover"
              >
                {/* Period + Closed state pill */}
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-ink">
                    {formatPeriod(item.period, locale)}
                  </span>
                  <span className="inline-flex items-center gap-1 rounded-full bg-tint-profit px-2.5 py-0.5 text-xs font-semibold text-profit-ink">
                    <Check className="size-3" aria-hidden />
                    {t('close.yes')}
                  </span>
                  {item.usesIllustrativeRules ? (
                    <Badge tone="amber">
                      <TriangleAlert className="size-3" /> {t('close.illustrative')}
                    </Badge>
                  ) : null}
                </div>

                {/* Base currency */}
                <span className="tnum font-mono text-sm text-ink">{item.baseCurrency}</span>

                {/* Reconciled */}
                <div>
                  {item.reconciled ? (
                    <Badge tone="profit">
                      <Check className="size-3" /> {t('close.yes')}
                    </Badge>
                  ) : (
                    <Badge tone="neutral">{t('close.no')}</Badge>
                  )}
                </div>

                {/* First close */}
                <div>
                  {item.firstClose ? (
                    <Badge tone="profit">
                      <Check className="size-3" /> {t('close.yes')}
                    </Badge>
                  ) : (
                    <Badge tone="neutral">{t('close.no')}</Badge>
                  )}
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}

      {/* Confirm close dialog */}
      {showConfirm ? (
        <ConfirmCloseDialog
          period={period}
          baseCurrency={company.baseCurrency}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setShowConfirm(false)}
          onResult={(r) => setLastResult(r)}
        />
      ) : null}
    </div>
  )
}
