import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ChevronRight, Plus, Trash2, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState, KpiTile } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { isoMinorExponent } from '@/lib/money'
import { cn } from '@/lib/cn'
import {
  useBankAccount,
  useImportLines,
  useReconcile,
  useReconciliation,
  useStatementLines,
  type ImportStatementLineBody,
  type ReconcileCategory,
  type StatementLine,
  type StatementLineStatus,
} from './api'
import {
  categoriesFor,
  formatDate,
  formatSignedMoney,
  majorToSignedMinor,
  reconcileErrorKey,
} from './format'
import { CountTile, DialogOverlay, SELECT_CLASSES, StatementStatusBadge } from './parts'

type LineFilter = StatementLineStatus | 'ALL'

/**
 * Bank reconciliation workspace (/bank/:id) — KPI tiles (bank balance / in-transit clearing /
 * unreconciled count) from {@link useReconciliation}, an "Import statement lines" dialog, and a
 * filterable table of statement lines with a per-line Reconcile control that only offers the
 * direction-valid categories (a deposit can be CLEARING or INTEREST; a withdrawal CLEARING or
 * BANK_FEE — see {@link categoriesFor}). 409/400 reconcile failures map to a friendly i18n message
 * via {@link reconcileErrorKey}.
 */
export function BankReconcile() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const { id } = useParams<{ id: string }>()
  const locale = localeOf(i18n.language)
  const [filter, setFilter] = useState<LineFilter>('ALL')
  const [importOpen, setImportOpen] = useState(false)

  const bankAccountId = id ?? ''
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const enabled = !!company && !!bankAccountId

  const accountQuery = useBankAccount({ companyId, actor, id: bankAccountId, enabled })
  const reconciliationQuery = useReconciliation({ companyId, actor, bankAccountId, enabled })
  const linesQuery = useStatementLines({
    companyId,
    actor,
    bankAccountId,
    status: filter === 'ALL' ? undefined : filter,
    enabled,
  })

  if (!company) {
    return <EmptyState title={t('bank.reconcile.noCompany')} hint={t('bank.reconcile.noCompanyHint')} />
  }
  if (accountQuery.isLoading) {
    return (
      <Card className="p-10 text-center">
        <Spinner className="mx-auto text-brand-500" />
      </Card>
    )
  }
  if (accountQuery.isError) {
    return (
      <Card className="p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('bank.reconcile.error')}
      </Card>
    )
  }
  const account = accountQuery.data
  if (!account) {
    return (
      <div className="mx-auto max-w-md space-y-4 text-center">
        <EmptyState
          title={t('bank.reconcile.notFoundTitle')}
          hint={t('bank.reconcile.notFoundHint')}
        />
        <Link to="/bank" className="text-sm font-semibold text-brand-700 hover:underline">
          {t('bank.reconcile.backToAccounts')}
        </Link>
      </div>
    )
  }

  const report = reconciliationQuery.data ?? null
  const lines = linesQuery.data ?? []
  const currency = report?.currency ?? account.currency

  const options: { value: LineFilter; label: string }[] = [
    { value: 'ALL', label: t('bank.reconcile.filterAll') },
    { value: 'UNRECONCILED', label: t('bank.reconcile.status.UNRECONCILED') },
    { value: 'RECONCILED', label: t('bank.reconcile.status.RECONCILED') },
  ]

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Breadcrumb trail */}
      <nav
        aria-label={t('bank.reconcile.breadcrumbLabel')}
        className="flex items-center gap-1.5 text-sm"
      >
        <Link to="/bank" className="font-medium text-ink-3 transition-colors hover:text-brand-700">
          {t('bank.accounts.title')}
        </Link>
        <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
        <span className="font-semibold text-ink">{account.name}</span>
      </nav>

      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {account.name}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">
            {account.accountNumber ? `${account.accountNumber} · ` : ''}
            {account.currency}
          </p>
        </div>
        <Button type="button" onClick={() => setImportOpen(true)}>
          <Plus className="size-4" />
          {t('bank.reconcile.importLines')}
        </Button>
      </div>

      {/* KPI tiles */}
      <div className="grid gap-4 sm:grid-cols-3">
        <KpiTile
          label={t('bank.reconcile.bankBalance')}
          minor={report?.bankBalanceMinor ?? 0}
          currency={currency}
          locale={locale}
          loading={reconciliationQuery.isLoading}
        />
        <KpiTile
          label={t('bank.reconcile.inTransit')}
          minor={report?.cashClearingBalanceMinor ?? 0}
          currency={currency}
          locale={locale}
          loading={reconciliationQuery.isLoading}
        />
        <CountTile
          label={t('bank.reconcile.unreconciledCount')}
          value={report?.unreconciledCount ?? 0}
          locale={locale}
          loading={reconciliationQuery.isLoading}
        />
      </div>
      <p className="-mt-2.5 text-xs text-ink-3">{t('bank.reconcile.inTransitHint')}</p>

      <Segmented<LineFilter>
        options={options}
        value={filter}
        onChange={setFilter}
        ariaLabel={t('bank.reconcile.filterAll')}
        className="flex-wrap"
      />

      {linesQuery.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('bank.reconcile.linesError')}
        </Card>
      ) : linesQuery.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : lines.length === 0 ? (
        <EmptyState title={t('bank.reconcile.empty')} hint={t('bank.reconcile.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('bank.reconcile.colDate')}</th>
                <th className="px-4 py-3">{t('bank.reconcile.colDescription')}</th>
                <th className="px-4 py-3 text-right">{t('bank.reconcile.colAmount')}</th>
                <th className="px-4 py-3">{t('bank.reconcile.colStatus')}</th>
                <th className="px-4 py-3">{t('bank.reconcile.colAction')}</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr key={line.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="whitespace-nowrap px-4 py-3 text-ink-3">
                    {formatDate(line.lineDate, locale)}
                  </td>
                  <td className="px-4 py-3 text-ink-2">
                    {line.description ?? t('bank.reconcile.noValue')}
                    {line.reference ? (
                      <span className="block font-mono text-xs text-ink-3">{line.reference}</span>
                    ) : null}
                  </td>
                  <td
                    className={cn(
                      'tnum whitespace-nowrap px-4 py-3 text-right font-mono font-semibold',
                      line.amountMinor >= 0 ? 'text-profit-ink' : 'text-loss',
                    )}
                  >
                    {formatSignedMoney(line.amountMinor, line.currency, locale)}
                  </td>
                  <td className="px-4 py-3">
                    <StatementStatusBadge status={line.status} />
                  </td>
                  <td className="px-4 py-3">
                    {line.status === 'UNRECONCILED' ? (
                      <ReconcileControl
                        line={line}
                        companyId={companyId}
                        actor={actor}
                        bankAccountId={bankAccountId}
                      />
                    ) : (
                      <span className="text-xs text-ink-3">
                        {line.reconciledCategory
                          ? t(`bank.categories.${line.reconciledCategory}` as Parameters<typeof t>[0])
                          : t('bank.reconcile.noValue')}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {importOpen ? (
        <ImportLinesDialog
          companyId={companyId}
          actor={actor}
          bankAccountId={bankAccountId}
          currency={account.currency}
          onClose={() => setImportOpen(false)}
        />
      ) : null}
    </div>
  )
}

function ReconcileControl({
  line,
  companyId,
  actor,
  bankAccountId,
}: {
  line: StatementLine
  companyId: string
  actor: string
  bankAccountId: string
}) {
  const { t } = useTranslation()
  const categories = categoriesFor(line.amountMinor)
  const [category, setCategory] = useState<ReconcileCategory>(categories[0])
  const mutation = useReconcile({ companyId, actor, bankAccountId })

  function handleReconcile() {
    mutation.mutate({ lineId: line.id, category })
  }

  return (
    <div className="flex flex-col items-start gap-1.5">
      <div className="flex items-center gap-2">
        <label className="sr-only" htmlFor={`reconcile-cat-${line.id}`}>
          {t('bank.reconcile.categoryLabel')}
        </label>
        <select
          id={`reconcile-cat-${line.id}`}
          value={category}
          onChange={(e) => setCategory(e.target.value as ReconcileCategory)}
          className={SELECT_CLASSES}
        >
          {categories.map((c) => (
            <option key={c} value={c}>
              {t(`bank.categories.${c}`)}
            </option>
          ))}
        </select>
        <Button type="button" size="sm" onClick={handleReconcile} disabled={mutation.isPending}>
          {mutation.isPending ? t('bank.reconcile.reconciling') : t('bank.reconcile.reconcileAction')}
        </Button>
      </div>
      {mutation.isError ? (
        <p className="text-xs text-loss">{t(reconcileErrorKey(mutation.error))}</p>
      ) : null}
    </div>
  )
}

interface DraftLine {
  key: string
  lineDate: string
  amountMajor: string
  description: string
  reference: string
}

function newLine(): DraftLine {
  return { key: crypto.randomUUID(), lineDate: '', amountMajor: '', description: '', reference: '' }
}

/** A line is submittable once it has a date and a non-zero amount — the sign is what matters. */
function parseImportLine(line: DraftLine, currency: string): ImportStatementLineBody | null {
  if (!line.lineDate) return null
  const amountMinor = majorToSignedMinor(line.amountMajor, currency)
  if (amountMinor === null) return null
  return {
    lineDate: line.lineDate,
    amountMinor,
    description: line.description.trim() || undefined,
    reference: line.reference.trim() || undefined,
  }
}

function ImportLinesDialog({
  companyId,
  actor,
  bankAccountId,
  currency,
  onClose,
}: {
  companyId: string
  actor: string
  bankAccountId: string
  currency: string
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [lines, setLines] = useState<DraftLine[]>([newLine()])
  const mutation = useImportLines({ companyId, actor, bankAccountId })

  const exponent = isoMinorExponent(currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const amountStep = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()

  const parsedLines = lines.map((l) => ({ draft: l, parsed: parseImportLine(l, currency) }))
  const validBodies = parsedLines
    .map((p) => p.parsed)
    .filter((b): b is ImportStatementLineBody => !!b)
  const canSubmit = validBodies.length > 0 && !mutation.isPending

  function updateLine(key: string, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }
  function addLine() {
    setLines((prev) => [...prev, newLine()])
  }
  function removeLine(key: string) {
    setLines((prev) => (prev.length > 1 ? prev.filter((l) => l.key !== key) : prev))
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    mutation.mutate({ lines: validBodies }, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('bank.reconcile.importDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">{t('bank.reconcile.importDialog.body')}</p>

        <div className="flex max-h-[50vh] flex-col gap-3 overflow-y-auto pr-1">
          {lines.map((line, idx) => {
            const parsed = parseImportLine(line, currency)
            return (
              <div
                key={line.key}
                className="grid grid-cols-1 items-end gap-2.5 rounded-xl border border-line p-3 sm:grid-cols-[150px_130px_1fr_120px_auto]"
              >
                <Field
                  label={t('bank.reconcile.importDialog.dateLabel')}
                  htmlFor={`line-date-${line.key}`}
                >
                  <TextInput
                    id={`line-date-${line.key}`}
                    type="date"
                    value={line.lineDate}
                    onChange={(e) => updateLine(line.key, { lineDate: e.target.value })}
                    required
                  />
                </Field>
                <Field
                  label={t('bank.reconcile.importDialog.amountLabel')}
                  htmlFor={`line-amount-${line.key}`}
                >
                  <TextInput
                    id={`line-amount-${line.key}`}
                    type="number"
                    step={amountStep}
                    value={line.amountMajor}
                    onChange={(e) => updateLine(line.key, { amountMajor: e.target.value })}
                    placeholder={t('bank.reconcile.importDialog.amountPlaceholder')}
                  />
                </Field>
                <Field
                  label={t('bank.reconcile.importDialog.descriptionLabel')}
                  htmlFor={`line-desc-${line.key}`}
                >
                  <TextInput
                    id={`line-desc-${line.key}`}
                    value={line.description}
                    onChange={(e) => updateLine(line.key, { description: e.target.value })}
                  />
                </Field>
                <Field
                  label={t('bank.reconcile.importDialog.referenceLabel')}
                  htmlFor={`line-ref-${line.key}`}
                >
                  <TextInput
                    id={`line-ref-${line.key}`}
                    value={line.reference}
                    onChange={(e) => updateLine(line.key, { reference: e.target.value })}
                  />
                </Field>
                <div className="flex items-end justify-between gap-2">
                  {parsed ? (
                    <span
                      className={cn(
                        'tnum hidden font-mono text-xs sm:block',
                        parsed.amountMinor >= 0 ? 'text-profit-ink' : 'text-loss',
                      )}
                    >
                      {formatSignedMoney(parsed.amountMinor, currency, locale)}
                    </span>
                  ) : (
                    <span />
                  )}
                  <button
                    type="button"
                    aria-label={t('bank.reconcile.importDialog.removeLine', { n: idx + 1 })}
                    onClick={() => removeLine(line.key)}
                    disabled={lines.length === 1}
                    className="grid size-10 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-tint-loss hover:text-loss disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <Trash2 className="size-4" />
                  </button>
                </div>
              </div>
            )
          })}
        </div>

        <Button type="button" variant="outline" size="sm" onClick={addLine}>
          <Plus className="size-4" />
          {t('bank.reconcile.importDialog.addLine')}
        </Button>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('bank.reconcile.importDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? t('bank.reconcile.importDialog.submitting')
              : t('bank.reconcile.importDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
