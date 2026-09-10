/**
 * IncomeDetailBody — the Income Statement drill-down's CONTENT, container-free, so the desktop
 * right-side Drawer and the phone bottom sheet (Native Laporan) render one and the same thing.
 * `revenue`/`expense` list that section's accounts (name + amount + share of the section total,
 * largest first, contra lines sinking to the bottom) reusing the statement already on screen; `net`
 * is the compact recap (revenue − expense = net, + margin). Read-only, no fetch — account names come
 * from the console's own localized map (accountLabels.ts). All copy i18n (rule 9); money via Intl
 * (rule 8).
 */
import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/cn'
import { formatMoney, formatPercent } from '@/lib/money'
import type { IncomeStatementResponse } from './api'
import { detailRows, type IncomeDetailKind } from './incomeDetail'
import { ShareBars } from './ShareBars'

export function IncomeDetailBody({
  kind,
  data,
  names,
  currency,
  locale,
  size = 'md',
}: {
  kind: IncomeDetailKind
  data: IncomeStatementResponse
  names: ReadonlyMap<string, string>
  currency: string
  locale: string
  /** Row metrics: `md` is the desktop drawer as it has always been, `sm` the phone sheet. */
  size?: 'md' | 'sm'
}) {
  const { t } = useTranslation()
  const totalRevenue = data.totalRevenueMinor
  const totalExpense = data.totalExpenseMinor
  const net = data.netMinor
  const profit = net >= 0

  if (kind === 'net') {
    return (
      <div className="space-y-3">
        <RecapRow label={t('statements.revenue')} value={formatMoney(totalRevenue, currency, locale)} />
        <RecapRow label={t('statements.expense')} value={formatMoney(-totalExpense, currency, locale)} />
        <div className="mt-1 flex items-center border-t-[1.5px] border-line-strong pt-3">
          <span className="flex-1 text-sm font-semibold text-ink">
            {profit ? t('statements.netProfit') : t('statements.netLoss')}
          </span>
          <span className={cn('tnum font-mono text-sm font-bold', profit ? 'text-profit-ink' : 'text-loss')}>
            {formatMoney(net, currency, locale)}
          </span>
        </div>
        {totalRevenue > 0 ? (
          <p className={cn('text-xs font-semibold', profit ? 'text-profit-ink' : 'text-loss')}>
            {t('statements.marginPct', { pct: formatPercent(net / totalRevenue, locale) })}
          </p>
        ) : null}
        <p className="pt-1 text-xs leading-relaxed text-ink-3">{t('statements.netDetailHint')}</p>
      </div>
    )
  }

  const isRevenue = kind === 'revenue'
  const sectionTotal = isRevenue ? totalRevenue : totalExpense
  const rows = detailRows(isRevenue ? data.revenueLines : data.expenseLines, sectionTotal, names)

  if (rows.length === 0) {
    return <p className="py-6 text-center text-sm text-ink-3">{t('statements.noLines')}</p>
  }

  return (
    <>
      <div className="mb-3 flex items-baseline justify-between">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {isRevenue ? t('statements.revenueAccounts') : t('statements.expenseAccounts')}
        </span>
        <span className="text-[11.5px] text-ink-3">{t('statements.detailShareNote')}</span>
      </div>
      <ShareBars
        rows={rows.map((r) => ({
          key: r.accountCode,
          name: r.name,
          code: r.accountCode,
          amountMinor: r.amountMinor,
          share: r.share,
        }))}
        currency={currency}
        locale={locale}
        tone={isRevenue ? 'ink' : 'loss'}
        size={size}
      />
      <div className="mt-4 flex items-center border-t-[1.5px] border-line-strong pt-3">
        <span className="flex-1 text-sm font-semibold text-ink">
          {isRevenue ? t('statements.totalRevenue') : t('statements.totalExpense')}
        </span>
        <span className="tnum font-mono text-sm font-semibold text-ink">
          {formatMoney(sectionTotal, currency, locale)}
        </span>
      </div>
    </>
  )
}

function RecapRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between text-sm">
      <span className="text-ink-3">{label}</span>
      <span className="tnum font-mono text-ink">{value}</span>
    </div>
  )
}
