import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowRight, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import type { CompanySession } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import { useOverdueSources } from './platformApi'

/**
 * The Beranda nudge (ADR 0076 phase 3): money a platform has been holding longer than its usual
 * payout cycle.
 *
 * <p>Renders NOTHING when nothing is overdue — which is most days. That is the whole design: cycles
 * differ per platform (QRIS commonly H+1, Shopee weekly, all shifting around holidays), so a card
 * that appeared daily would be dismissed unread by the time it mattered.
 *
 * <p>Wording is deliberately "not recorded as settled", never "not in your bank": the money reaches
 * the bank account in the books only once the statement line is reconciled (ADR 0016).
 */
export function OverdueSettlementCard({
  session,
  locale,
  enabled = true,
}: {
  session: CompanySession
  locale: string
  enabled?: boolean
}) {
  const { t } = useTranslation()
  const query = useOverdueSources(session, enabled)
  const overdue = query.data ?? []

  // Silent on error too: a nudge that cannot load is not worth an error card on the home page.
  if (query.isLoading || query.isError || overdue.length === 0) {
    return null
  }

  return (
    <Card className="border-amber-tint bg-amber-tint p-5">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid size-[22px] shrink-0 place-items-center rounded-full bg-warning text-white">
          <TriangleAlert className="size-3.5" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-[14px] font-bold text-ink">{t('platform.overdue.title')}</h2>
          <p className="mt-0.5 max-w-[62ch] text-[13px] text-ink-2">{t('platform.overdue.body')}</p>

          <div className="mt-3 flex flex-col gap-1.5">
            {overdue.map((row) => (
              <div key={row.sourceCode} className="flex items-baseline gap-3 text-[13px]">
                <span className="min-w-0 flex-1 truncate">
                  <span className="font-semibold text-ink">{row.sourceCode}</span>
                  <span className="ml-1.5 text-ink-3">
                    {row.daysSinceLastPayout == null
                      ? t('platform.overdue.never')
                      : t('platform.overdue.days', { count: row.daysSinceLastPayout })}
                  </span>
                </span>
                <span className="tnum shrink-0 font-mono font-semibold text-ink">
                  {formatMoney(row.outstandingMinor, row.currency, locale)}
                </span>
              </div>
            ))}
          </div>

          <Link
            to="/platform-settlements"
            className="mt-3 inline-flex items-center gap-1 text-[13px] font-semibold text-emerald-2 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            {t('platform.overdue.action')}
            <ArrowRight className="size-3.5" aria-hidden />
          </Link>
        </div>
      </div>
    </Card>
  )
}
