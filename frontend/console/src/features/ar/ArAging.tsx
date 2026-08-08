import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { AgingPhoneList } from '@/features/_shared/AgingPhoneList'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { downloadCsv } from '@/lib/csv'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatAmount, formatMoney, minorToMajor } from '@/lib/money'
import { useArAging } from './api'

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

/** The five aging buckets in display order; index 4 (90+) is the danger bucket. */
const BUCKET_KEYS = [
  'currentMinor',
  'overdue1To30Minor',
  'overdue31To60Minor',
  'overdue61To90Minor',
  'overdue90PlusMinor',
] as const

/** Distribution-bar segment tone: 90+ red, 31–90 amber, current/1–30 brand. */
function segmentClass(bucketIndex: number): string {
  if (bucketIndex === 4) return 'bg-loss'
  if (bucketIndex >= 2) return 'bg-warning'
  return 'bg-emerald'
}

/**
 * AR aging report — outstanding receivables per customer, bucketed by how overdue they are
 * (current / 1–30 / 31–60 / 61–90 / 90+ days), plus a totals row and a CSV export. Owner/manager
 * only.
 *
 * Native Console Web design: seven columns stay seven columns (the right shape for an aging
 * report), but zeros render as dashes so the eye lands on the cells that carry a figure, each
 * customer carries a bucket-distribution bar, and a totals strip leads the table.
 */
export function ArAging() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const isPhone = useIsPhone()
  const locale = localeOf(i18n.language)
  const [asOf, setAsOf] = useState(todayIso())

  const query = useArAging({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    asOf,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ar.aging.noCompany')} hint={t('ar.aging.noCompanyHint')} />
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const rows = data?.rows ?? []

  function exportCsv() {
    if (!data) return
    downloadCsv(`ar-aging-${asOf}.csv`, [
      [t('ar.aging.title'), asOf, currency],
      [
        t('ar.aging.colCustomer'),
        t('ar.aging.colCurrent'),
        t('ar.aging.col1To30'),
        t('ar.aging.col31To60'),
        t('ar.aging.col61To90'),
        t('ar.aging.col90Plus'),
        t('ar.aging.colOutstanding'),
      ],
      ...data.rows.map((r) => [
        r.customerName,
        minorToMajor(r.currentMinor, currency),
        minorToMajor(r.overdue1To30Minor, currency),
        minorToMajor(r.overdue31To60Minor, currency),
        minorToMajor(r.overdue61To90Minor, currency),
        minorToMajor(r.overdue90PlusMinor, currency),
        minorToMajor(r.outstandingMinor, currency),
      ]),
      [
        t('ar.aging.totalsRow'),
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
            {t('ar.aging.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('ar.aging.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2.5">
          <Field label={t('ar.aging.asOfLabel')} htmlFor="aging-asof">
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
            {t('ar.aging.export')}
          </Button>
        </div>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('ar.aging.error')}
        </Card>
      ) : query.isLoading ? (
        <>
          <StatCardsSkeleton cards={4} />
          <ListSkeleton rows={6} />
        </>
      ) : rows.length === 0 ? (
        <EmptyState title={t('ar.aging.empty')} hint={t('ar.aging.emptyHint')} />
      ) : isPhone ? (
        /* Phone (Native Console Android): totals strip scrolls sideways; the table becomes
           per-customer cards with bucket chips. */
        <>
          {data ? <BucketStrip totals={data.totals} currency={currency} locale={locale} /> : null}
          <AgingPhoneList
            rows={rows.map((r) => ({
              id: r.customerId,
              name: r.customerName,
              buckets: BUCKET_KEYS.map((k) => r[k]),
              outstandingMinor: r.outstandingMinor,
            }))}
            labels={[
              t('ar.aging.colCurrent'),
              t('ar.aging.col1To30'),
              t('ar.aging.col31To60'),
              t('ar.aging.col61To90'),
              t('ar.aging.col90Plus'),
            ]}
            currency={currency}
            locale={locale}
          />
        </>
      ) : (
        <>
          {data ? <BucketStrip totals={data.totals} currency={currency} locale={locale} /> : null}

          <Card className="overflow-x-auto rounded-[20px]">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('ar.aging.colCustomer')}</th>
                  <th className="px-4 py-3 text-right">{t('ar.aging.colCurrent')}</th>
                  <th className="px-4 py-3 text-right">{t('ar.aging.col1To30')}</th>
                  <th className="px-4 py-3 text-right">{t('ar.aging.col31To60')}</th>
                  <th className="px-4 py-3 text-right">{t('ar.aging.col61To90')}</th>
                  <th className="px-4 py-3 text-right text-loss">{t('ar.aging.col90Plus')}</th>
                  <th className="px-4 py-3 text-right">{t('ar.aging.colOutstanding')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const bucketVals = BUCKET_KEYS.map((k) => r[k])
                  const bucketSum = bucketVals.reduce((a, v) => a + v, 0)
                  return (
                    <tr
                      key={r.customerId}
                      className="border-b border-ink-50 last:border-0 hover:bg-hover"
                    >
                      <td className="px-4 py-3">
                        <div className="font-semibold text-ink">{r.customerName}</div>
                        {bucketSum > 0 ? (
                          <div
                            className="mt-1.5 flex h-1 max-w-[150px] overflow-hidden rounded-full bg-ink-50"
                            aria-hidden="true"
                          >
                            {bucketVals.map((v, i) =>
                              v > 0 ? (
                                <span
                                  key={BUCKET_KEYS[i]}
                                  className={segmentClass(i)}
                                  style={{ width: `${(v / bucketSum) * 100}%` }}
                                />
                              ) : null,
                            )}
                          </div>
                        ) : null}
                      </td>
                      {bucketVals.map((v, i) => (
                        <AgingCell
                          key={BUCKET_KEYS[i]}
                          minor={v}
                          danger={i === 4}
                          currency={currency}
                          locale={locale}
                        />
                      ))}
                      <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                        {formatMoney(r.outstandingMinor, currency, locale)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
              {data ? (
                <tfoot>
                  <tr className="border-t-[1.5px] border-line-strong bg-paper">
                    <td className="px-4 py-3 font-semibold text-ink">{t('ar.aging.totalsRow')}</td>
                    {BUCKET_KEYS.map((k, i) => (
                      <AgingCell
                        key={k}
                        minor={data.totals[k]}
                        danger={i === 4}
                        currency={currency}
                        locale={locale}
                        bold
                      />
                    ))}
                    <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                      {formatMoney(data.totals.outstandingMinor, currency, locale)}
                    </td>
                  </tr>
                </tfoot>
              ) : null}
            </table>
          </Card>
        </>
      )}
    </div>
  )
}

/** One money cell of the aging grid — zero renders as a faint dash so filled cells pop. */
function AgingCell({
  minor,
  danger,
  currency,
  locale,
  bold,
}: {
  minor: number
  danger: boolean
  currency: string
  locale: string
  bold?: boolean
}) {
  if (minor === 0) {
    return <td className="tnum px-4 py-3 text-right font-mono text-line-strong">—</td>
  }
  return (
    <td
      className={cn(
        'tnum px-4 py-3 text-right font-mono',
        bold && 'font-semibold',
        danger ? 'text-loss' : bold ? 'text-ink' : 'text-ink-2',
      )}
    >
      {formatMoney(minor, currency, locale)}
    </td>
  )
}

/** The bucket-totals strip that leads the table — 90+ carries the loss tint, each cell a mini
 *  bar scaled to the largest bucket. */
function BucketStrip({
  totals,
  currency,
  locale,
}: {
  totals: Record<(typeof BUCKET_KEYS)[number], number>
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const labels = [
    t('ar.aging.colCurrent'),
    t('ar.aging.col1To30'),
    t('ar.aging.col31To60'),
    t('ar.aging.col61To90'),
    t('ar.aging.col90Plus'),
  ]
  const values = BUCKET_KEYS.map((k) => totals[k])
  const max = Math.max(...values)
  return (
    <div className="flex overflow-hidden rounded-[14px] border border-line bg-surface max-sm:overflow-x-auto">
      {values.map((v, i) => (
        <div
          key={BUCKET_KEYS[i]}
          className={cn(
            'min-w-0 flex-1 px-4 py-3.5 max-sm:min-w-[104px] max-sm:flex-none',
            i < 4 && 'border-r border-line',
            i === 4 ? 'bg-tint-loss text-loss' : 'text-ink',
          )}
        >
          <div className="truncate text-[10.5px] font-semibold uppercase tracking-[0.06em] opacity-70">
            {labels[i]}
          </div>
          <div className="tnum mt-1.5 truncate font-mono text-[17px] font-bold">
            {formatAmount(v, currency, locale)}
          </div>
          <div
            className="mt-1.5 h-1 rounded-full bg-current opacity-20"
            style={{ width: `${max > 0 ? (v / max) * 100 : 0}%` }}
            aria-hidden="true"
          />
        </div>
      ))}
    </div>
  )
}
