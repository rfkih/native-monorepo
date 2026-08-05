import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Download, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { downloadCsv } from '@/lib/csv'
import { SummaryCard } from '@/features/statements/parts'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, minorToMajor } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import { useBudgetActuals } from './api'

/** favorable = doing better than plan (more revenue, or less expense); unfavorable = worse. */
function favorability(accountType: string, variance: number): 'favorable' | 'unfavorable' | 'neutral' {
  const type = accountType.toUpperCase()
  if (type === 'REVENUE') return variance >= 0 ? 'favorable' : 'unfavorable'
  if (type === 'EXPENSE') return variance <= 0 ? 'favorable' : 'unfavorable'
  return 'neutral'
}

function varianceClass(tone: 'favorable' | 'unfavorable' | 'neutral'): string {
  if (tone === 'favorable') return 'text-profit-ink'
  if (tone === 'unfavorable') return 'text-loss'
  return 'text-ink-2'
}

/**
 * Budget-vs-actual variance report for one budget — each account's planned amount vs its GL actual
 * for the budget's month, and the variance (colored favorable/unfavorable by account type).
 */
export function BudgetDetail() {
  const { t, i18n } = useTranslation()
  const { id = '' } = useParams()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const query = useBudgetActuals({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    id,
    enabled: !!company && !!id,
  })

  if (!company) {
    return <EmptyState title={t('budget.noCompany')} hint={t('budget.noCompanyHint')} />
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const rows = data?.lines ?? []

  function exportCsv() {
    if (!data) return
    downloadCsv(`budget-${data.name}-${data.period}.csv`, [
      [t('budget.detailTitle'), data.name, data.period, currency],
      [
        t('budget.colAccount'),
        t('budget.colPlannedShort'),
        t('budget.colActual'),
        t('budget.colVariance'),
      ],
      ...data.lines.map((l) => [
        `${l.accountCode} ${l.accountName}`,
        minorToMajor(l.plannedMinor, currency),
        minorToMajor(l.actualMinor, currency),
        minorToMajor(l.varianceMinor, currency),
      ]),
      [
        t('budget.totalsRow'),
        minorToMajor(data.totalPlannedMinor, currency),
        minorToMajor(data.totalActualMinor, currency),
        minorToMajor(data.totalVarianceMinor, currency),
      ],
    ])
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <Link
          to="/budgets"
          className="inline-flex items-center gap-1.5 text-sm text-ink-3 transition-colors hover:text-ink"
        >
          <ArrowLeft className="size-4" />
          {t('budget.backToList')}
        </Link>
      </div>

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {data?.name ?? t('budget.detailTitle')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">
            {data ? formatPeriod(data.period, locale) : ''}
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          {data?.usesIllustrativeRules ? (
            <Badge tone="amber">
              <TriangleAlert className="size-3" /> {t('statements.illustrative')}
            </Badge>
          ) : null}
          <Button type="button" variant="outline" onClick={exportCsv} disabled={!data}>
            <Download className="size-[15px]" aria-hidden />
            {t('budget.export')}
          </Button>
        </div>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('budget.error')}
        </Card>
      ) : query.isLoading || !data ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <SummaryCard
              chipClass="bg-ink-300"
              label={t('budget.totalPlanned')}
              value={formatMoney(data.totalPlannedMinor, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-brand-500"
              label={t('budget.totalActual')}
              value={formatMoney(data.totalActualMinor, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-profit"
              label={t('budget.totalVariance')}
              value={formatMoney(data.totalVarianceMinor, currency, locale)}
              emphatic
            />
          </div>

          <Card className="overflow-x-auto rounded-[20px]">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('budget.colAccount')}</th>
                  <th className="px-4 py-3 text-right">{t('budget.colPlannedShort')}</th>
                  <th className="px-4 py-3 text-right">{t('budget.colActual')}</th>
                  <th className="px-4 py-3 text-right">{t('budget.colVariance')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((l) => {
                  const tone = favorability(l.accountType, l.varianceMinor)
                  return (
                    <tr
                      key={l.accountCode}
                      className="border-b border-ink-50 last:border-0 hover:bg-hover"
                    >
                      <td className="px-4 py-3">
                        <span className="font-mono text-ink-3">{l.accountCode}</span>{' '}
                        <span className="font-semibold text-ink">{l.accountName}</span>
                      </td>
                      <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                        {formatMoney(l.plannedMinor, currency, locale)}
                      </td>
                      <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                        {formatMoney(l.actualMinor, currency, locale)}
                      </td>
                      <td
                        className={`tnum px-4 py-3 text-right font-mono font-semibold ${varianceClass(tone)}`}
                      >
                        {formatMoney(l.varianceMinor, currency, locale)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
              <tfoot>
                <tr className="border-t-[1.5px] border-line-strong">
                  <td className="px-4 py-3 font-semibold text-ink">{t('budget.totalsRow')}</td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totalPlannedMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totalActualMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totalVarianceMinor, currency, locale)}
                  </td>
                </tr>
              </tfoot>
            </table>
          </Card>
        </>
      )}
    </div>
  )
}
