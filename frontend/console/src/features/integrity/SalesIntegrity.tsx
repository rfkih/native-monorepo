/**
 * Deteksi Kebocoran — the sales-leak report (ADR 0074).
 *
 * Answers the question no other Native report can: how much was sold and never rung up. Every other
 * report is GL-derived, so all of them are blind to a sale that was never an input; this one reads
 * the PHYSICAL evidence instead — stock that moved without a sale, and tills that nobody watched.
 *
 * Three things about this page are deliberate and should survive any redesign:
 *
 *  1. The headline is a RANGE, and it says so. The low bound is what is tightly quantified; the high
 *     bound includes an ingredient estimate with innocent explanations Native cannot yet record.
 *  2. The disclaimer is not fine print. This page can end with somebody being accused of theft, and
 *     it is a signal to investigate, not evidence. It sits directly under the number.
 *  3. Coverage sits ABOVE the findings, not below them. At 30% recipe coverage a reassuring total
 *     means almost nothing, and a reader who sees the number first has already drawn a conclusion.
 *
 * Owner-only — routed behind `isOwner` and gated again at the gateway (OWNER_ROLES), because
 * findings can name an individual and a manager may be the subject of one.
 *
 * Strings rule (rule 9): every label is an i18n key, en+id, keyed off the server's machine signal
 * type. Money rule 8: minor units in, `formatMoney` (Intl) out.
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Info, ShieldAlert, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { EmptyState, PeriodNav } from '@/features/_shared/financeUi'
import { OutletGate } from '@/components/OutletGate'
import { OutletPicker } from '@/components/OutletPicker'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { cn } from '@/lib/cn'
import {
  SEVERITY_ORDER,
  useSalesIntegrityReport,
  type LeakCoverage,
  type LeakDetail,
  type LeakSeverity,
  type LeakSignal,
} from './salesIntegrityApi'

export function SalesIntegrity() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return (
      <EmptyState
        title={t('salesIntegrity.noCompany')}
        hint={t('salesIntegrity.noCompanyHint')}
      />
    )
  }
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(outletSession) => <SalesIntegrityInner session={outletSession} />}
    </OutletGate>
  )
}

function SalesIntegrityInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [period, setPeriod] = useState(currentPeriod())

  const query = useSalesIntegrityReport(session, session.businessId, period)
  const report = query.data ?? null

  const signals = useMemo(
    () =>
      [...(report?.signals ?? [])].sort(
        (a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity],
      ),
    [report?.signals],
  )

  const currency = report?.currency ?? session.baseCurrency
  const isLoading = query.isLoading && !report

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('salesIntegrity.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('salesIntegrity.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <OutletPicker />
          <PeriodNav
            period={period}
            locale={locale}
            onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
            onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
            prevLabel={t('salesIntegrity.prevPeriod')}
            nextLabel={t('salesIntegrity.nextPeriod')}
          />
        </div>
      </div>

      {query.isError && (
        <Card className="flex items-center gap-3 border-loss/30 bg-loss/5 p-4 text-sm text-loss">
          <TriangleAlert className="size-4 shrink-0" />
          {t('salesIntegrity.error')}
        </Card>
      )}

      {isLoading ? (
        <ListSkeleton rows={4} />
      ) : report ? (
        <>
          <HeadlineCard
            low={report.estimatedLeakMinorLow}
            high={report.estimatedLeakMinorHigh}
            confirmedCost={report.confirmedMissingCostMinor}
            currency={currency}
            locale={locale}
          />
          <CoverageCard report={report} />
          {signals.length === 0 ? (
            <EmptyState title={t('salesIntegrity.empty')} hint={t('salesIntegrity.emptyHint')} />
          ) : (
            <div className="flex flex-col gap-3">
              {signals.map((signal) => (
                <SignalCard
                  key={signal.type}
                  signal={signal}
                  fallbackCurrency={currency}
                  locale={locale}
                />
              ))}
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}

/**
 * The headline. Renders a RANGE when the two bounds differ and a single figure when they agree —
 * showing "Rp 0 – Rp 0" or a spurious range would be noise, and showing only one number when they
 * genuinely differ would be a claim the evidence does not support.
 */
function HeadlineCard({
  low,
  high,
  confirmedCost,
  currency,
  locale,
}: {
  low: number
  high: number
  confirmedCost: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const isRange = high > low
  return (
    <Card className="p-5">
      <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">
        {t('salesIntegrity.headline.label')}
      </p>
      <p
        className={cn(
          'mt-2 font-display text-[32px] font-bold leading-tight tracking-[-0.02em]',
          high > 0 ? 'text-loss' : 'text-ink',
        )}
      >
        {isRange
          ? `${formatMoney(low, currency, locale)} – ${formatMoney(high, currency, locale)}`
          : formatMoney(high, currency, locale)}
      </p>
      {isRange && (
        <p className="mt-1 text-xs text-ink-3">{t('salesIntegrity.headline.rangeHint')}</p>
      )}
      {confirmedCost > 0 && (
        <p className="mt-3 text-sm text-ink-2">
          {t('salesIntegrity.headline.confirmedCost', {
            amount: formatMoney(confirmedCost, currency, locale),
          })}
        </p>
      )}
      {/* Not fine print. This page can end with an accusation, and the reader has to know what the
          number is and is not before acting on it. */}
      <div className="mt-4 flex items-start gap-2 rounded-lg bg-surface-2 p-3 text-xs text-ink-2">
        <ShieldAlert className="mt-0.5 size-4 shrink-0 text-ink-3" />
        <span>{t('salesIntegrity.disclaimer')}</span>
      </div>
    </Card>
  )
}

/** What the report could not see — placed above the findings, on purpose. */
function CoverageCard({
  report,
}: {
  report: { coverage: LeakCoverage }
}) {
  const { t } = useTranslation()
  const { coverage } = report
  const pct =
    coverage.totalSoldQty > 0
      ? Math.round((coverage.recipeBackedSoldQty / coverage.totalSoldQty) * 100)
      : null

  return (
    <Card className="flex flex-col gap-2 p-4 text-sm">
      <div className="flex items-center gap-2 text-ink-2">
        <Info className="size-4 shrink-0 text-ink-3" />
        <span className="font-semibold">{t('salesIntegrity.coverage.title')}</span>
      </div>
      <ul className="ml-6 list-disc space-y-1 text-ink-3">
        <li>
          {pct === null
            ? t('salesIntegrity.coverage.noSales')
            : t('salesIntegrity.coverage.recipe', { pct })}
        </li>
        <li>
          {coverage.daysSinceIngredientCount === null
            ? t('salesIntegrity.coverage.neverCountedIngredients')
            : t('salesIntegrity.coverage.lastIngredientCount', {
                days: coverage.daysSinceIngredientCount,
              })}
        </li>
        <li>
          {coverage.daysSinceItemCount === null
            ? t('salesIntegrity.coverage.neverCountedItems')
            : t('salesIntegrity.coverage.lastItemCount', { days: coverage.daysSinceItemCount })}
        </li>
        <li>
          {t('salesIntegrity.coverage.manualCorrections', {
            count: coverage.manualStockCorrections,
          })}
        </li>
      </ul>
    </Card>
  )
}

const SEVERITY_CLASS: Record<LeakSeverity, string> = {
  HIGH: 'bg-loss/10 text-loss border-loss/30',
  MEDIUM: 'bg-amber-500/10 text-amber-600 border-amber-500/30 dark:text-amber-400',
  LOW: 'bg-surface-2 text-ink-3 border-line',
}

function SignalCard({
  signal,
  fallbackCurrency,
  locale,
}: {
  signal: LeakSignal
  fallbackCurrency: string
  locale: string
}) {
  const { t } = useTranslation()
  const currency = signal.currency ?? fallbackCurrency

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-semibold text-ink">
              {t(`salesIntegrity.signal.${signal.type}.title`)}
            </h2>
            <span
              className={cn(
                'rounded-full border px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide',
                SEVERITY_CLASS[signal.severity],
              )}
            >
              {t(`salesIntegrity.severity.${signal.severity}`)}
            </span>
          </div>
          <p className="mt-1 text-sm text-ink-3">
            {t(`salesIntegrity.signal.${signal.type}.body`, { count: signal.occurrences })}
          </p>
        </div>
        {signal.estimatedValueMinor !== null && (
          <p className="shrink-0 font-display text-lg font-bold text-ink">
            {formatMoney(signal.estimatedValueMinor, currency, locale)}
          </p>
        )}
      </div>

      {signal.details.length > 0 && (
        <ul className="mt-3 divide-y divide-line border-t border-line text-sm">
          {signal.details.map((detail, index) => (
            <li
              key={`${signal.type}-${index}`}
              className="flex flex-wrap items-center justify-between gap-2 py-2"
            >
              <span className="min-w-0 truncate text-ink-2">{describe(detail, locale, t)}</span>
              {detail.valueMinor !== null && (
                <span className="shrink-0 tabular-nums text-ink-3">
                  {formatMoney(detail.valueMinor, detail.currency ?? currency, locale)}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      <p className="mt-3 text-xs text-ink-3">
        {t(`salesIntegrity.signal.${signal.type}.advice`)}
      </p>
    </Card>
  )
}

/**
 * Renders one evidence row's identity from whichever fields the signal filled.
 *
 * Every part is either DATA (a stored name) or locale-formatted through `Intl` — no date or number
 * is ever concatenated by hand (rule 9).
 */
function describe(
  detail: LeakDetail,
  locale: string,
  t: (key: string, opts?: Record<string, unknown>) => string,
): string {
  const parts: string[] = []
  if (detail.subjectName) parts.push(detail.subjectName)
  if (detail.businessDate) {
    parts.push(
      new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(
        new Date(`${detail.businessDate}T00:00:00`),
      ),
    )
  }
  if (detail.hourOfDay !== null) {
    parts.push(t('salesIntegrity.detail.hour', { hour: String(detail.hourOfDay).padStart(2, '0') }))
  }
  if (detail.quantity !== null) {
    parts.push(
      t('salesIntegrity.detail.quantity', {
        qty: new Intl.NumberFormat(locale).format(detail.quantity),
      }),
    )
  }
  return parts.length > 0 ? parts.join(' · ') : t('salesIntegrity.detail.unnamed')
}
