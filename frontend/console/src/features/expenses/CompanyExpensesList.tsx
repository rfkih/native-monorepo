/**
 * CompanyExpensesList — the "Perusahaan" tab body on `/expenses` (ADR 0072 P3): a read history of
 * `POST /api/v1/company-expenses` submits (both GENERAL and INVENTORY), with a void action per row.
 * Mirrors `ExpensesList.tsx`'s table + `DialogOverlay` detail/void idiom, but for the FINANCE-gated
 * company-expense feed rather than the HR-gated employee claim queue.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Info, TriangleAlert } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { useOrgUnits } from '@/features/org/api'
import { ApiError } from '@/lib/api'
import { useSession } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { formatDate } from './format'
import {
  useCompanyExpense,
  useCompanyExpenses,
  useVoidCompanyExpense,
  type CompanyExpense,
  type CompanyExpenseKind,
  type CompanyExpenseStatus,
} from './companyExpenseApi'

const LIST_LIMIT = 50

const KIND_TONE: Record<CompanyExpenseKind, 'neutral' | 'info'> = {
  GENERAL: 'neutral',
  INVENTORY: 'info',
}

const STATUS_TONE: Record<CompanyExpenseStatus, 'profit' | 'loss'> = {
  POSTED: 'profit',
  VOID: 'loss',
}

export function CompanyExpensesList() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const ready = !!company
  const orgUnits = useOrgUnits({ companyId, actor, enabled: ready })
  const expenses = useCompanyExpenses({ companyId, actor, limit: LIST_LIMIT, enabled: ready })
  const outletName = (id: string) => orgUnits.data?.find((u) => u.id === id)?.name ?? id

  if (!company) {
    return (
      <EmptyState
        title={t('expenses.company.noCompany')}
        hint={t('expenses.company.noCompanyHint')}
      />
    )
  }

  const rows = expenses.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h2 className="font-display text-lg font-semibold text-ink">{t('expenses.company.title')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('expenses.company.subtitle')}</p>
      </div>

      {expenses.isLoading ? (
        <ListSkeleton rows={6} />
      ) : expenses.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('expenses.company.error')}
        </Card>
      ) : rows.length === 0 ? (
        <EmptyState title={t('expenses.company.empty')} hint={t('expenses.company.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('expenses.company.colNo')}</th>
                  <th className="px-4 py-3">{t('expenses.company.colDate')}</th>
                  <th className="px-4 py-3">{t('expenses.company.colKind')}</th>
                  <th className="px-4 py-3">{t('expenses.company.colOutlet')}</th>
                  <th className="px-4 py-3">{t('expenses.company.colDescription')}</th>
                  <th className="px-4 py-3">{t('expenses.company.colStatus')}</th>
                  <th className="px-4 py-3 text-right">{t('expenses.company.colAmount')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <CompanyExpenseRow
                    key={row.id}
                    expense={row}
                    locale={locale}
                    outletName={outletName(row.businessId)}
                    onOpen={() => setSelectedId(row.id)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {selectedId ? (
        <CompanyExpenseDetailSheet
          id={selectedId}
          companyId={companyId}
          actor={actor}
          locale={locale}
          outletName={outletName}
          onClose={() => setSelectedId(null)}
        />
      ) : null}
    </div>
  )
}

function CompanyExpenseRow({
  expense,
  locale,
  outletName,
  onOpen,
}: {
  expense: CompanyExpense
  locale: string
  outletName: string
  onOpen: () => void
}) {
  const { t } = useTranslation()
  return (
    <tr className="border-b border-ink-50 last:border-0 hover:bg-hover">
      <td className="px-4 py-3">
        <button
          type="button"
          onClick={onOpen}
          className="font-mono text-xs font-semibold text-ink hover:text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-emerald"
        >
          {expense.expenseNo}
        </button>
      </td>
      <td className="px-4 py-3 text-ink-3">{formatDate(expense.occurredAt, locale)}</td>
      <td className="px-4 py-3">
        <Badge tone={KIND_TONE[expense.kind]}>{t(`expenses.company.kind.${expense.kind}`)}</Badge>
      </td>
      <td className="px-4 py-3 text-ink-3">{outletName}</td>
      <td className="max-w-[280px] truncate px-4 py-3 text-ink-2">{expense.description}</td>
      <td className="px-4 py-3">
        <Badge tone={STATUS_TONE[expense.status]}>{t(`expenses.company.status.${expense.status}`)}</Badge>
      </td>
      <td className="tnum px-4 py-3 text-right font-mono text-ink">
        {formatMoney(expense.amountMinor, expense.currency, locale)}
      </td>
    </tr>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-ink-3">{label}</div>
      <div className="truncate text-ink">{value}</div>
    </div>
  )
}

function CompanyExpenseDetailSheet({
  id,
  companyId,
  actor,
  locale,
  outletName,
  onClose,
}: {
  id: string
  companyId: string
  actor: string
  locale: string
  outletName: (id: string) => string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const detail = useCompanyExpense({ companyId, actor, id, enabled: true })
  const [voiding, setVoiding] = useState(false)

  const expense = detail.data

  return (
    <DialogOverlay onClose={onClose}>
      {voiding && expense ? (
        <VoidCompanyExpenseDialog
          expense={expense}
          companyId={companyId}
          actor={actor}
          onClose={() => setVoiding(false)}
          onDone={onClose}
        />
      ) : (
        <div className="space-y-5">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('expenses.company.detail.title')}
          </h2>

          {detail.isLoading || !expense ? (
            <div className="space-y-5">
              <div className="flex items-center gap-3">
                <Skeleton className="h-6 w-24 rounded-full" />
                <Skeleton className="h-6 w-28" />
              </div>
              <div className="grid grid-cols-2 gap-3 rounded-2xl border border-line bg-surface p-4">
                <Skeleton className="h-9" />
                <Skeleton className="h-9" />
                <Skeleton className="h-9" />
              </div>
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-3">
                <Badge tone={KIND_TONE[expense.kind]}>{t(`expenses.company.kind.${expense.kind}`)}</Badge>
                <Badge tone={STATUS_TONE[expense.status]}>
                  {t(`expenses.company.status.${expense.status}`)}
                </Badge>
                <span className="tnum font-mono text-xl font-semibold text-ink">
                  {formatMoney(expense.amountMinor, expense.currency, locale)}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3 rounded-2xl border border-line bg-surface p-4 text-sm">
                <Detail label={t('expenses.company.detail.expenseNo')} value={expense.expenseNo} />
                <Detail
                  label={t('expenses.company.detail.date')}
                  value={formatDate(expense.occurredAt, locale)}
                />
                <Detail label={t('expenses.company.colOutlet')} value={outletName(expense.businessId)} />
                {expense.kind === 'GENERAL' ? (
                  <Detail
                    label={t('expenses.company.detail.glHint')}
                    value={
                      expense.glHint
                        ? t(`expenses.categories.glHint.${expense.glHint}`, { defaultValue: expense.glHint })
                        : t('expenses.categories.glHint.general')
                    }
                  />
                ) : null}
              </div>

              <div>
                <div className="text-xs font-semibold uppercase tracking-wider text-ink-3">
                  {t('expenses.company.colDescription')}
                </div>
                <p className="mt-0.5 whitespace-pre-wrap text-sm text-ink-2">{expense.description}</p>
              </div>

              {expense.kind === 'INVENTORY' && expense.lines.length > 0 ? (
                <div>
                  <div className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-ink-3">
                    {t('expenses.record.inventory.lines')}
                  </div>
                  <div className="overflow-hidden rounded-xl border border-line">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                          <th className="px-3 py-2">{t('expenses.record.inventory.ingredientLabel')}</th>
                          <th className="px-3 py-2 text-right">{t('expenses.company.detail.lineQty')}</th>
                          <th className="px-3 py-2 text-right">{t('expenses.record.inventory.lineValue')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {expense.lines.map((line) => (
                          <tr key={line.id} className="border-b border-ink-50 last:border-0">
                            <td className="px-3 py-2 text-ink-2">
                              {/* "Nama di nota berbeda" (V60) — a non-null line description is the
                                  RECEIPT wording, shown as the primary text with the linked
                                  inventory item as subtext; a plain line (description null, the
                                  common case) renders exactly as before. */}
                              {line.description ? (
                                <>
                                  <div>{line.description}</div>
                                  <div className="text-xs text-ink-3">
                                    → {line.ingredientName} ·{' '}
                                    {new Intl.NumberFormat(locale).format(line.qtyBase)}
                                  </div>
                                </>
                              ) : (
                                line.ingredientName
                              )}
                            </td>
                            <td className="tnum px-3 py-2 text-right font-mono text-ink-2">
                              {new Intl.NumberFormat(locale).format(line.qtyBase)}
                            </td>
                            <td className="tnum px-3 py-2 text-right font-mono text-ink">
                              {formatMoney(line.valueMinor, expense.currency, locale)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ) : null}

              {expense.status === 'POSTED' ? (
                <div className="border-t border-line pt-4">
                  <Button
                    type="button"
                    variant="outline"
                    className="text-loss hover:bg-tint-loss"
                    onClick={() => setVoiding(true)}
                  >
                    {t('expenses.company.void')}
                  </Button>
                </div>
              ) : null}

              <div className="flex justify-end pt-1">
                <Button type="button" variant="outline" onClick={onClose}>
                  {t('me.expenses.detail.close')}
                </Button>
              </div>
            </>
          )}
        </div>
      )}
    </DialogOverlay>
  )
}

function VoidCompanyExpenseDialog({
  expense,
  companyId,
  actor,
  onClose,
  onDone,
}: {
  expense: CompanyExpense
  companyId: string
  actor: string
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const voidExpense = useVoidCompanyExpense({ companyId, actor })
  const [voided, setVoided] = useState(false)

  function handleConfirm() {
    voidExpense.mutate({ id: expense.id }, { onSuccess: () => setVoided(true) })
  }

  if (voided) {
    return (
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('expenses.company.voidDialog.doneTitle')}
        </h2>
        <p className="text-sm text-ink-2">{t('expenses.company.voidDialog.doneBody')}</p>
        {expense.kind === 'INVENTORY' ? (
          <div className="flex items-start gap-2 rounded-xl bg-tint-warning px-3.5 py-3 text-sm text-ink-2">
            <Info className="mt-0.5 size-4 shrink-0 text-amber-2" aria-hidden="true" />
            <div>
              <p>{t('expenses.company.voidDialog.stockGuidance')}</p>
              <Link to="/inventory" className="mt-1 inline-block font-semibold text-brand-700 hover:underline">
                {t('expenses.company.voidDialog.stockGuidanceLink')}
              </Link>
            </div>
          </div>
        ) : null}
        <div className="flex justify-end">
          <Button type="button" onClick={onDone}>
            {t('common.close')}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <h2 className="font-display text-lg font-semibold text-ink">
        {t('expenses.company.voidDialog.title')}
      </h2>
      <p className="text-sm text-ink-2">{t('expenses.company.voidDialog.body')}</p>
      {voidExpense.isError ? (
        <p className="text-sm text-loss" role="alert">
          {voidExpense.error instanceof ApiError && voidExpense.error.problem?.detail
            ? voidExpense.error.problem.detail
            : t('expenses.actions.error')}
        </p>
      ) : null}
      <div className="flex justify-end gap-3">
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button
          type="button"
          className="bg-loss text-white hover:opacity-90"
          onClick={handleConfirm}
          disabled={voidExpense.isPending}
        >
          {voidExpense.isPending ? t('expenses.actions.saving') : t('expenses.company.voidDialog.confirm')}
        </Button>
      </div>
    </div>
  )
}
