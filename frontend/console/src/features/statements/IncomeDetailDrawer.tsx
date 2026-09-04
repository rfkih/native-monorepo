import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Drawer } from '@/components/ui/Drawer'
import { cn } from '@/lib/cn'
import { formatAmount, formatMoney, formatPercent } from '@/lib/money'
import type { IncomeStatementResponse } from './api'
import { detailRows } from './incomeDetail'

/**
 * The Income Statement (Laba Rugi) card drill-down: a right-side drawer opened by clicking a summary
 * card. `revenue`/`expense` show that section's accounts (name + amount + % share of the section
 * total, largest first) reusing the statement data already on screen; `net` is a compact recap
 * (revenue − expense = net + margin). Read-only, no new fetch — account names come from the
 * chart-of-accounts map the page already loaded. All copy is i18n (rule 9); money via Intl (rule 8).
 */
export type IncomeDetailKind = 'revenue' | 'expense' | 'net'

export function IncomeDetailDrawer({
  kind,
  data,
  names,
  currency,
  locale,
  onClose,
}: {
  kind: IncomeDetailKind
  data: IncomeStatementResponse
  names: ReadonlyMap<string, string>
  currency: string
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()

  const totalRevenue = data.totalRevenueMinor
  const totalExpense = data.totalExpenseMinor
  const net = data.netMinor
  const profit = net >= 0

  const title =
    kind === 'revenue'
      ? t('statements.revenueDetailTitle')
      : kind === 'expense'
        ? t('statements.expenseDetailTitle')
        : t('statements.netDetailTitle')

  const isRevenue = kind === 'revenue'
  const sectionTotal = isRevenue ? totalRevenue : totalExpense
  const rows = detailRows(isRevenue ? data.revenueLines : data.expenseLines, sectionTotal, names)

  return (
    <Drawer onClose={onClose} ariaLabel={title}>
      <div className="flex items-center justify-between border-b border-line px-5 py-4">
        <div>
          <h2 className="font-display text-lg font-semibold text-ink">{title}</h2>
          <p className="mt-0.5 text-xs text-ink-3">{data.period}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          <X className="size-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-5 py-4">
        {kind === 'net' ? (
          <div className="space-y-3">
            <RecapRow label={t('statements.revenue')} value={formatMoney(totalRevenue, currency, locale)} />
            <RecapRow label={t('statements.expense')} value={formatMoney(-totalExpense, currency, locale)} />
            <div className="mt-1 flex items-center border-t-[1.5px] border-line-strong pt-3">
              <span className="flex-1 text-sm font-semibold text-ink">
                {profit ? t('statements.netProfit') : t('statements.netLoss')}
              </span>
              <span
                className={cn(
                  'tnum font-mono text-sm font-bold',
                  profit ? 'text-profit-ink' : 'text-loss',
                )}
              >
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
        ) : rows.length === 0 ? (
          <p className="py-6 text-center text-sm text-ink-3">{t('statements.noLines')}</p>
        ) : (
          <>
            <div className="mb-3 flex items-baseline justify-between">
              <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                {isRevenue ? t('statements.revenueAccounts') : t('statements.expenseAccounts')}
              </span>
              <span className="text-[11.5px] text-ink-3">{t('statements.detailShareNote')}</span>
            </div>
            <div className="flex flex-col gap-[15px]">
              {rows.map((r) => {
                const width = Math.max(0, Math.min(1, r.share)) * 100
                return (
                  <div key={r.accountCode}>
                    <div className="mb-1.5 flex items-baseline justify-between gap-3">
                      <span className="min-w-0 flex-1 truncate text-sm text-ink-2">
                        {r.name}
                        <span className="ml-1.5 font-mono text-[11px] text-ink-3">{r.accountCode}</span>
                      </span>
                      <span className="flex shrink-0 items-baseline gap-3">
                        <span className="tnum font-mono text-[13.5px] font-semibold text-ink">
                          {formatAmount(r.amountMinor, currency, locale)}
                        </span>
                        <span className="tnum w-[42px] text-right font-mono text-xs text-ink-3">
                          {formatPercent(r.share, locale)}
                        </span>
                      </span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-ink-50">
                      <div
                        className={cn('h-full rounded-full', isRevenue ? 'bg-brand-500' : 'bg-loss')}
                        style={{ width: `${width}%` }}
                      />
                    </div>
                  </div>
                )
              })}
            </div>
            <div className="mt-4 flex items-center border-t-[1.5px] border-line-strong pt-3">
              <span className="flex-1 text-sm font-semibold text-ink">
                {isRevenue ? t('statements.totalRevenue') : t('statements.totalExpense')}
              </span>
              <span className="tnum font-mono text-sm font-semibold text-ink">
                {formatMoney(sectionTotal, currency, locale)}
              </span>
            </div>
          </>
        )}
      </div>
    </Drawer>
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
