/**
 * Expenses tab of the org-unit hub (ADR 0030, Track E Phase E8) — replaces the ComingSoon stub.
 * Summary tiles (recognised APPROVED+REIMBURSED spend + pending-decision count), a by-category
 * breakdown (a simple bar list — mirrors OrgUnitDetail's `ContributionRow`; no chart library is
 * used anywhere in the org hub, so this doesn't introduce one), the 5 most recent claims scoped to
 * this unit (reusing the E7 manager list hook, `useClaims`), and a link to the full `/expenses`
 * console. BU scope = the unit + its child outlets — the SAME org-unit descendant-ids composition
 * `EmployeesTab`/`PayrollTab` already use (features/hr/EmployeesTab.tsx).
 */

import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowUpRight, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { EmptyState, KpiTile, PeriodNav } from '@/features/_shared/financeUi'
import type { OrgUnit } from '@/features/org/api'
import { formatMoney } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { localeOf } from '@/i18n'
import { formatCount, formatDate } from './format'
import { ClaimStatusBadge } from './parts'
import {
  useClaims,
  useOrgUnitExpenseSummary,
  type ExpenseCategoryTotal,
  type ExpenseClaimSummary,
} from './api'

const RECENT_SIZE = 5

export function OrgUnitExpensesTab({
  unit,
  companyId,
  actor,
  baseCurrency,
}: {
  unit: OrgUnit
  companyId: string
  actor: string
  baseCurrency: string
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [period, setPeriod] = useState(currentPeriod())

  // ADR 0070: flat tree — a unit IS an outlet, so its scope is just itself.
  const unitIds = useMemo(() => [unit.id], [unit])
  const scope = unitIds.join(',')

  const summary = useOrgUnitExpenseSummary({
    companyId,
    actor,
    orgUnitIds: unitIds,
    period,
    enabled: true,
  })
  const recent = useClaims({
    companyId,
    actor,
    orgUnitId: scope,
    page: 0,
    size: RECENT_SIZE,
    enabled: true,
  })

  const data = summary.data
  const pendingCount = data?.byStatus.find((s) => s.status === 'SUBMITTED')?.count ?? 0
  const currency = data?.currency ?? baseCurrency
  const grandTotal = data?.approvedReimbursedTotalMinor ?? 0

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          prevLabel={t('orgHub.overview.prevMonth')}
          nextLabel={t('orgHub.overview.nextMonth')}
        />
        <Link
          to="/expenses"
          className="inline-flex items-center gap-1 text-sm font-semibold text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-brand-500"
        >
          {t('orgHub.expensesTab.viewAll')}
          <ArrowUpRight className="size-4" aria-hidden="true" />
        </Link>
      </div>

      {/* The spend/category tiles reconcile to the GL on the APPROVAL period (matches finance's
          ExpenseClaimPostingWriter); the pending count is an operational view on the EXPENSE
          date — the two can legitimately disagree for the same selected period (E8 review W1). */}
      <p className="text-xs text-ink-3">{t('orgHub.expensesTab.periodHint')}</p>

      <div className="grid gap-4 sm:grid-cols-2">
        <KpiTile
          label={t('orgHub.expensesTab.recognisedSpend')}
          minor={grandTotal}
          currency={currency}
          locale={locale}
          loading={summary.isLoading}
        />
        <CountTile
          label={t('orgHub.expensesTab.pending')}
          value={pendingCount}
          locale={locale}
          loading={summary.isLoading}
        />
      </div>

      {summary.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('orgHub.expensesTab.error')}
        </Card>
      ) : !summary.isLoading && data && data.byCategory.length === 0 ? (
        <p className="text-sm text-ink-3">{t('orgHub.expensesTab.noPostings')}</p>
      ) : data && data.byCategory.length > 0 ? (
        <Card className="p-5">
          <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('orgHub.expensesTab.byCategory')}
          </h2>
          <div className="mt-4 flex flex-col gap-3.5">
            {data.byCategory.map((c) => (
              <CategoryRow
                key={c.categoryName}
                category={c}
                grandTotal={grandTotal}
                locale={locale}
              />
            ))}
          </div>
        </Card>
      ) : null}

      <RecentClaimsCard query={recent} locale={locale} />
    </div>
  )
}

/** A single headline figure (a plain count, no currency) — mirrors `KpiTile`'s shape/skeleton. */
function CountTile({
  label,
  value,
  locale,
  loading,
}: {
  label: string
  value: number
  locale: string
  loading: boolean
}) {
  return (
    <Card className="p-5">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-3 h-7 w-16 animate-pulse rounded bg-ink-100" />
      ) : (
        <div className="tnum mt-2 font-mono text-[25px] font-semibold text-ink">
          {formatCount(value, locale)}
        </div>
      )}
    </Card>
  )
}

/** One category's recognised spend, with a slim progress bar — mirrors OrgUnitDetail's `ContributionRow`. */
function CategoryRow({
  category,
  grandTotal,
  locale,
}: {
  category: ExpenseCategoryTotal
  grandTotal: number
  locale: string
}) {
  const { t } = useTranslation()
  const share = grandTotal > 0 ? category.totalMinor / grandTotal : 0
  return (
    <div>
      <div className="flex items-center justify-between gap-3">
        <span className="min-w-0 truncate text-sm font-semibold text-ink">
          {category.categoryName}
        </span>
        <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
          {formatMoney(category.totalMinor, category.currency, locale)}
          <span className="ml-2 text-[11px] font-normal text-ink-3">
            {t('orgHub.expensesTab.shareOfTotal', {
              percent: new Intl.NumberFormat(locale, {
                style: 'percent',
                minimumFractionDigits: 1,
                maximumFractionDigits: 1,
              }).format(share),
            })}
          </span>
        </span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-ink-50">
        <div
          className="h-full rounded-full bg-emerald"
          style={{ width: `${Math.max(0, Math.min(1, share)) * 100}%` }}
        />
      </div>
    </div>
  )
}

/** The 5 most recent claims raised against this unit's employees (any status, SUBMITTED-first). */
function RecentClaimsCard({
  query,
  locale,
}: {
  query: ReturnType<typeof useClaims>
  locale: string
}) {
  const { t } = useTranslation()
  const rows = query.data?.content ?? []

  return (
    <Card className="p-5">
      <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
        {t('orgHub.expensesTab.recentTitle')}
      </h2>
      {query.isError ? (
        <p className="mt-3 text-sm text-loss">{t('orgHub.expensesTab.error')}</p>
      ) : !query.isLoading && rows.length === 0 ? (
        <EmptyState
          title={t('orgHub.expensesTab.recentEmpty')}
          hint={t('orgHub.expensesTab.recentEmptyHint')}
        />
      ) : (
        <div className="mt-3 flex flex-col">
          {rows.map((claim) => (
            <RecentClaimRow key={claim.id} claim={claim} locale={locale} />
          ))}
        </div>
      )}
    </Card>
  )
}

function RecentClaimRow({ claim, locale }: { claim: ExpenseClaimSummary; locale: string }) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl px-2.5 py-2.5 transition-colors hover:bg-hover">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[14.5px] font-semibold text-ink">{claim.employeeName}</span>
          <ClaimStatusBadge status={claim.status} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {claim.categoryName} · {formatDate(claim.expenseDate, locale)}
        </p>
      </div>
      <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
        {formatMoney(claim.amountMinor, claim.currency, locale)}
      </span>
    </div>
  )
}
