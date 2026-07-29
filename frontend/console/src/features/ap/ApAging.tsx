import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { downloadCsv } from '@/features/statements/parts'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, minorToMajor } from '@/lib/money'
import { useApAging } from './api'

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

/**
 * AP aging report — outstanding payables per vendor, bucketed by how overdue they are
 * (current / 1–30 / 31–60 / 61–90 / 90+ days), plus a totals row and a CSV export. Owner/manager
 * only.
 */
export function ApAging() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const [asOf, setAsOf] = useState(todayIso())

  const query = useApAging({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    asOf,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ap.aging.noCompany')} hint={t('ap.aging.noCompanyHint')} />
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const rows = data?.rows ?? []

  function exportCsv() {
    if (!data) return
    downloadCsv(`ap-aging-${asOf}.csv`, [
      [t('ap.aging.title'), asOf, currency],
      [
        t('ap.aging.colVendor'),
        t('ap.aging.colCurrent'),
        t('ap.aging.col1To30'),
        t('ap.aging.col31To60'),
        t('ap.aging.col61To90'),
        t('ap.aging.col90Plus'),
        t('ap.aging.colOutstanding'),
      ],
      ...data.rows.map((r) => [
        r.vendorName,
        minorToMajor(r.currentMinor, currency),
        minorToMajor(r.overdue1To30Minor, currency),
        minorToMajor(r.overdue31To60Minor, currency),
        minorToMajor(r.overdue61To90Minor, currency),
        minorToMajor(r.overdue90PlusMinor, currency),
        minorToMajor(r.outstandingMinor, currency),
      ]),
      [
        t('ap.aging.totalsRow'),
        minorToMajor(data.totals.currentMinor, currency),
        minorToMajor(data.totals.overdue1To30Minor, currency),
        minorToMajor(data.totals.overdue31To60Minor, currency),
        minorToMajor(data.totals.overdue61To90Minor, currency),
        minorToMajor(data.totals.overdue90PlusMinor, currency),
        minorToMajor(data.totals.outstandingMinor, currency),
      ],
    ])
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('ap.aging.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ap.aging.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2.5">
          <Field label={t('ap.aging.asOfLabel')} htmlFor="aging-asof">
            <TextInput
              id="aging-asof"
              type="date"
              value={asOf}
              onChange={(e) => setAsOf(e.target.value)}
              className="h-11"
            />
          </Field>
          <Button type="button" variant="outline" onClick={exportCsv} disabled={!data}>
            <Download className="size-[15px]" aria-hidden />
            {t('ap.aging.export')}
          </Button>
        </div>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ap.aging.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : rows.length === 0 ? (
        <EmptyState title={t('ap.aging.empty')} hint={t('ap.aging.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('ap.aging.colVendor')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.colCurrent')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.col1To30')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.col31To60')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.col61To90')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.col90Plus')}</th>
                <th className="px-4 py-3 text-right">{t('ap.aging.colOutstanding')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.vendorId} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">{r.vendorName}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(r.currentMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(r.overdue1To30Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(r.overdue31To60Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(r.overdue61To90Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-loss">
                    {formatMoney(r.overdue90PlusMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(r.outstandingMinor, currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
            {data ? (
              <tfoot>
                <tr className="border-t-[1.5px] border-line-strong">
                  <td className="px-4 py-3 font-semibold text-ink">{t('ap.aging.totalsRow')}</td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.currentMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.overdue1To30Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.overdue31To60Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.overdue61To90Minor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.overdue90PlusMinor, currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(data.totals.outstandingMinor, currency, locale)}
                  </td>
                </tr>
              </tfoot>
            ) : null}
          </table>
        </Card>
      )}
    </div>
  )
}
