import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight, Info, TriangleAlert } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, XAxis } from 'recharts'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, minorToMajor } from '@/lib/money'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { usePnl, type PnlResponse } from './api'

const EMERALD = '#0d6a4a'
const ROSE = '#8f322b'

export function Dashboard() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [period, setPeriod] = useState(currentPeriod())
  const [lens, setLens] = useState('native')

  const otherCurrency = company?.baseCurrency === 'IDR' ? 'USD' : 'IDR'
  const presentation = lens === 'native' ? undefined : lens

  const query = usePnl({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    baseCurrency: company?.baseCurrency ?? 'USD',
    period,
    presentation,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('dashboard.noCompany')} hint={t('dashboard.noCompanyHint')} />
  }

  const data = query.data ?? null
  const converted = presentation != null && data?.presentationCurrency != null
  const displayCurrency = converted ? data!.presentationCurrency! : company.baseCurrency

  const figures = readFigures(data, converted)

  return (
    <div className="space-y-7">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
            {t('dashboard.title')}
          </h1>
          <p className="mt-1 text-sm text-ink-3">{t('dashboard.subtitle')}</p>
        </div>
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          prevLabel={t('dashboard.prevPeriod')}
          nextLabel={t('dashboard.nextPeriod')}
        />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          {data?.usesIllustrativeRules ? (
            <Badge tone="amber">
              <TriangleAlert className="size-3" /> {t('dashboard.illustrative')}
            </Badge>
          ) : null}
          {converted && data?.usesStubFx ? (
            <Badge tone="amber">
              <TriangleAlert className="size-3" /> {t('dashboard.stubFx')}
              {data.fxAsOf ? (
                <span className="font-normal opacity-80">
                  · {t('dashboard.asOf', { date: data.fxAsOf })}
                </span>
              ) : null}
            </Badge>
          ) : null}
        </div>
        <label className="flex items-center gap-2.5">
          <span className="text-xs text-ink-3">{t('dashboard.viewIn')}</span>
          <Segmented
            ariaLabel={t('dashboard.viewIn')}
            value={lens}
            onChange={setLens}
            options={[
              { value: 'native', label: company.baseCurrency },
              { value: otherCurrency, label: otherCurrency },
            ]}
          />
        </label>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-rose">{t('dashboard.error')}</Card>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <KpiTile
              label={t('dashboard.revenue')}
              minor={figures.revenue}
              currency={displayCurrency}
              locale={locale}
              loading={query.isLoading}
            />
            <KpiTile
              label={t('dashboard.expense')}
              minor={figures.expense}
              currency={displayCurrency}
              locale={locale}
              loading={query.isLoading}
            />
            <KpiTile
              label={figures.net >= 0 ? t('dashboard.netProfit') : t('dashboard.netLoss')}
              minor={figures.net}
              currency={displayCurrency}
              locale={locale}
              loading={query.isLoading}
              tone={figures.net >= 0 ? 'text-emerald-2' : 'text-rose'}
              emphatic
            />
          </div>

          <Card className="p-6">
            <div className="flex items-center justify-between">
              <h2 className="font-display text-lg font-semibold text-ink">
                {t('dashboard.breakdown')}
              </h2>
              {converted ? (
                <span className="inline-flex items-center gap-1.5 text-xs text-ink-3">
                  <Info className="size-3.5" /> {t('dashboard.presentationNote')}
                </span>
              ) : null}
            </div>
            <div className="mt-5 h-56">
              {query.isLoading ? (
                <div className="grid h-full place-items-center text-ink-3">
                  <Spinner className="text-emerald" />
                </div>
              ) : (
                <BreakdownChart
                  revenue={figures.revenue}
                  expense={figures.expense}
                  currency={displayCurrency}
                  revenueLabel={t('dashboard.revenue')}
                  expenseLabel={t('dashboard.expense')}
                />
              )}
            </div>
          </Card>
        </>
      )}
    </div>
  )
}

interface Figures {
  revenue: number
  expense: number
  net: number
}

function readFigures(data: PnlResponse | null, converted: boolean): Figures {
  if (!data) return { revenue: 0, expense: 0, net: 0 }
  if (converted) {
    return {
      revenue: data.presentationRevenueMinor ?? 0,
      expense: data.presentationExpenseMinor ?? 0,
      net: data.presentationNetMinor ?? 0,
    }
  }
  return { revenue: data.revenueMinor, expense: data.expenseMinor, net: data.netMinor }
}

function PeriodNav({
  period,
  locale,
  onPrev,
  onNext,
  prevLabel,
  nextLabel,
}: {
  period: string
  locale: string
  onPrev: () => void
  onNext: () => void
  prevLabel: string
  nextLabel: string
}) {
  return (
    <div className="inline-flex items-center gap-1 rounded-lg border border-line-strong bg-surface p-1">
      <button
        type="button"
        aria-label={prevLabel}
        onClick={onPrev}
        className="grid size-8 place-items-center rounded-md text-ink-3 transition-colors hover:bg-paper hover:text-ink"
      >
        <ChevronLeft className="size-4" />
      </button>
      <span className="min-w-[8.5rem] text-center text-sm font-medium text-ink">
        {formatPeriod(period, locale)}
      </span>
      <button
        type="button"
        aria-label={nextLabel}
        onClick={onNext}
        className="grid size-8 place-items-center rounded-md text-ink-3 transition-colors hover:bg-paper hover:text-ink"
      >
        <ChevronRight className="size-4" />
      </button>
    </div>
  )
}

function KpiTile({
  label,
  minor,
  currency,
  locale,
  loading,
  tone,
  emphatic,
}: {
  label: string
  minor: number
  currency: string
  locale: string
  loading: boolean
  tone?: string
  emphatic?: boolean
}) {
  return (
    <Card className={cn('p-5', emphatic && 'ring-1 ring-emerald/15')}>
      <div className="text-[11px] uppercase tracking-wider text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-3 h-7 w-28 animate-pulse rounded bg-line" />
      ) : (
        <div className={cn('tnum mt-2 font-mono text-2xl font-medium', tone ?? 'text-ink')}>
          {formatMoney(minor, currency, locale)}
        </div>
      )}
    </Card>
  )
}

function BreakdownChart({
  revenue,
  expense,
  currency,
  revenueLabel,
  expenseLabel,
}: {
  revenue: number
  expense: number
  currency: string
  revenueLabel: string
  expenseLabel: string
}) {
  const chartData = [
    { label: revenueLabel, major: minorToMajor(revenue, currency), fill: EMERALD },
    { label: expenseLabel, major: minorToMajor(expense, currency), fill: ROSE },
  ]
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }} barCategoryGap="35%">
        <CartesianGrid vertical={false} stroke="#e6dfd1" />
        <XAxis
          dataKey="label"
          tickLine={false}
          axisLine={{ stroke: '#d2c9b6' }}
          tick={{ fill: '#847c6e', fontSize: 12 }}
        />
        <Bar dataKey="major" radius={[6, 6, 0, 0]} maxBarSize={120} isAnimationActive>
          {chartData.map((entry) => (
            <Cell key={entry.label} fill={entry.fill} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <h2 className="font-display text-xl font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-sm text-ink-3">{hint}</p>
    </Card>
  )
}
