import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Plus, Trash2, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import {
  useAccounts,
  useBudgets,
  useCreateBudget,
  useDeleteBudget,
  type CreateBudgetBody,
} from './api'
import { budgetErrorKey } from './format'

const SELECT_CLASSES =
  'w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7)
}

function toMinor(major: number, currency: string): number {
  return Math.round(major * 10 ** isoMinorExponent(currency))
}

interface DraftLine {
  accountCode: string
  amount: string
}

/**
 * Budgets — the per-month budget list plus a create dialog (name, month, account lines via the
 * chart-of-account picker). Each row links to the budget-vs-actual variance report. Owner/manager.
 */
export function Budgets() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const enabled = !!company
  const currency = company?.baseCurrency ?? 'IDR'

  const budgetsQuery = useBudgets({ companyId, actor, enabled })
  const accountsQuery = useAccounts({ companyId, actor, enabled })
  const createBudget = useCreateBudget({ companyId, actor })
  const deleteBudget = useDeleteBudget({ companyId, actor })

  const [dialogOpen, setDialogOpen] = useState(false)
  const [name, setName] = useState('')
  const [period, setPeriod] = useState(currentMonth())
  const [lines, setLines] = useState<DraftLine[]>([{ accountCode: '', amount: '' }])
  const [error, setError] = useState<string | null>(null)

  useBackDismiss(() => setDialogOpen(false), dialogOpen)
  useScrollLock(dialogOpen)

  if (!company) {
    return <EmptyState title={t('budget.noCompany')} hint={t('budget.noCompanyHint')} />
  }

  const budgets = budgetsQuery.data ?? []
  // Budgets target income and costs — offer only P&L (revenue/expense) accounts in the picker, not
  // balance-sheet or clearing/suspense accounts (whose period movement wouldn't be a meaningful plan).
  const accounts = (accountsQuery.data ?? []).filter(
    (a) => a.accountType === 'REVENUE' || a.accountType === 'EXPENSE',
  )

  function openDialog() {
    setName('')
    setPeriod(currentMonth())
    setLines([{ accountCode: '', amount: '' }])
    setError(null)
    setDialogOpen(true)
  }

  function setLine(idx: number, patch: Partial<DraftLine>) {
    setLines((ls) => ls.map((l, i) => (i === idx ? { ...l, ...patch } : l)))
  }

  async function submit() {
    setError(null)
    const validLines = lines.filter((l) => l.accountCode && l.amount.trim() !== '')
    if (!name.trim() || validLines.length === 0) {
      setError(t('budget.errors.invalid'))
      return
    }
    const body: CreateBudgetBody = {
      name: name.trim(),
      period,
      currency,
      lines: validLines.map((l) => ({
        accountCode: l.accountCode,
        amountMinor: toMinor(Number(l.amount), currency),
      })),
    }
    try {
      await createBudget.mutateAsync(body)
      setDialogOpen(false)
    } catch (e) {
      setError(t(budgetErrorKey(e)))
    }
  }

  async function onDelete(id: string) {
    if (!window.confirm(t('budget.deleteConfirm'))) return
    try {
      await deleteBudget.mutateAsync(id)
    } catch {
      // list refetch will reflect the true state; a failed delete is non-destructive.
    }
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('budget.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('budget.subtitle')}</p>
        </div>
        <Button type="button" onClick={openDialog}>
          <Plus className="size-[15px]" aria-hidden />
          {t('budget.new')}
        </Button>
      </div>

      {budgetsQuery.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('budget.error')}
        </Card>
      ) : budgetsQuery.isLoading ? (
        <ListSkeleton rows={6} />
      ) : budgets.length === 0 ? (
        <EmptyState title={t('budget.empty')} hint={t('budget.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('budget.colName')}</th>
                <th className="px-4 py-3">{t('budget.colPeriod')}</th>
                <th className="px-4 py-3 text-right">{t('budget.colLines')}</th>
                <th className="px-4 py-3 text-right">{t('budget.colPlanned')}</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {budgets.map((b) => (
                <tr key={b.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">
                    <Link to={`/budgets/${b.id}`} className="hover:text-emerald">
                      {b.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-ink-2">{formatPeriod(b.period, locale)}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">{b.lineCount}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(b.totalPlannedMinor, b.currency, locale)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      type="button"
                      aria-label={t('budget.delete')}
                      onClick={() => onDelete(b.id)}
                      className="text-ink-3 transition-colors hover:text-loss"
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {dialogOpen ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={(e) => {
            if (e.target === e.currentTarget) setDialogOpen(false)
          }}
        >
          <Card className="w-full max-w-lg p-6">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="font-display text-xl font-semibold text-ink">{t('budget.new')}</h2>
              <button
                type="button"
                aria-label={t('common.close')}
                onClick={() => setDialogOpen(false)}
                className="text-ink-3 hover:text-ink"
              >
                <X className="size-5" />
              </button>
            </div>

            <div className="flex flex-col gap-3.5">
              <Field label={t('budget.nameLabel')} htmlFor="budget-name">
                <TextInput
                  id="budget-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('budget.namePlaceholder')}
                />
              </Field>
              <Field label={t('budget.periodLabel')} htmlFor="budget-period">
                <TextInput
                  id="budget-period"
                  type="month"
                  value={period}
                  onChange={(e) => setPeriod(e.target.value)}
                />
              </Field>

              <div className="flex flex-col gap-2">
                <span className="text-sm font-medium text-ink-2">{t('budget.linesLabel')}</span>
                {lines.map((line, idx) => (
                  <div key={idx} className="flex items-center gap-2">
                    <select
                      aria-label={t('budget.account')}
                      value={line.accountCode}
                      onChange={(e) => setLine(idx, { accountCode: e.target.value })}
                      className={SELECT_CLASSES}
                    >
                      <option value="">{t('budget.selectAccount')}</option>
                      {accounts.map((a) => (
                        <option key={a.accountCode} value={a.accountCode}>
                          {a.accountCode} — {a.name}
                        </option>
                      ))}
                    </select>
                    <TextInput
                      type="number"
                      min="0"
                      aria-label={t('budget.amount')}
                      value={line.amount}
                      onChange={(e) => setLine(idx, { amount: e.target.value })}
                      placeholder={t('budget.amount')}
                      className="w-40"
                    />
                    <button
                      type="button"
                      aria-label={t('budget.removeLine')}
                      onClick={() => setLines((ls) => (ls.length > 1 ? ls.filter((_, i) => i !== idx) : ls))}
                      className="shrink-0 text-ink-3 hover:text-loss"
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setLines((ls) => [...ls, { accountCode: '', amount: '' }])}
                  className="self-start"
                >
                  <Plus className="size-[14px]" aria-hidden />
                  {t('budget.addLine')}
                </Button>
              </div>

              {error ? (
                <div className="flex items-center gap-2 text-sm text-loss">
                  <TriangleAlert className="size-4 shrink-0" aria-hidden />
                  {error}
                </div>
              ) : null}

              <div className="mt-1 flex justify-end gap-2.5">
                <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
                  {t('common.cancel')}
                </Button>
                <Button type="button" onClick={submit} disabled={createBudget.isPending}>
                  {t('budget.create')}
                </Button>
              </div>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  )
}
