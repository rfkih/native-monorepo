import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ChevronRight, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState, KpiTile } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import {
  useInvoice,
  useIssueInvoice,
  useRecordPayment,
  useVoidInvoice,
  type InvoiceDetail as InvoiceDetailDto,
} from './api'
import { formatDate, invoiceErrorKey } from './format'
import { DialogOverlay, InvoiceStatusBadge } from './parts'

/**
 * Invoice detail (/invoices/:id) — header (number, customer, status, dates), KPI tiles (total/
 * paid/outstanding), an amber "estimated tax" badge when the invoice used illustrative tax rules,
 * a line-items table, a payments table, and status-gated actions: Issue (DRAFT), Record payment
 * (ISSUED/PARTIALLY_PAID), Void (DRAFT/ISSUED and unpaid). 409 invalid-state responses map to a
 * friendly i18n message via {@link invoiceErrorKey}.
 */
export function InvoiceDetail() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const { id } = useParams<{ id: string }>()
  const locale = localeOf(i18n.language)
  const [dialog, setDialog] = useState<'issue' | 'payment' | 'void' | null>(null)

  const query = useInvoice({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    id: id ?? '',
    enabled: !!company && !!id,
  })

  if (!company) {
    return <EmptyState title={t('ar.invoices.noCompany')} hint={t('ar.invoices.noCompanyHint')} />
  }
  if (query.isLoading) {
    return (
      <div className="flex flex-col gap-[18px]">
        <StatCardsSkeleton cards={3} />
        <ListSkeleton rows={4} />
        <ListSkeleton rows={3} />
      </div>
    )
  }
  if (query.isError) {
    return (
      <Card className="p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('ar.detail.error')}
      </Card>
    )
  }
  const invoice = query.data
  if (!invoice) {
    return (
      <div className="mx-auto max-w-md space-y-4 text-center">
        <EmptyState title={t('ar.detail.notFoundTitle')} hint={t('ar.detail.notFoundHint')} />
        <Link to="/invoices" className="text-sm font-semibold text-brand-700 hover:underline">
          {t('ar.detail.backToInvoices')}
        </Link>
      </div>
    )
  }

  const canIssue = invoice.status === 'DRAFT'
  const canRecordPayment = invoice.status === 'ISSUED' || invoice.status === 'PARTIALLY_PAID'
  const canVoid = (invoice.status === 'DRAFT' || invoice.status === 'ISSUED') && invoice.paidMinor === 0

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Breadcrumb trail */}
      <nav aria-label={t('ar.detail.breadcrumbLabel')} className="flex items-center gap-1.5 text-sm">
        <Link to="/invoices" className="font-medium text-ink-3 transition-colors hover:text-brand-700">
          {t('ar.invoices.title')}
        </Link>
        <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
        <span className="font-semibold text-ink">{invoice.invoiceNumber}</span>
      </nav>

      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
              {invoice.invoiceNumber}
            </h1>
            <InvoiceStatusBadge status={invoice.status} />
            {invoice.usesIllustrativeRules ? (
              <Badge tone="amber">{t('ar.detail.estimatedTax')}</Badge>
            ) : null}
          </div>
          <p className="mt-1.5 text-sm text-ink-3">
            {t('ar.detail.customer')}: {invoice.customerName} · {t('ar.detail.issueDate')}:{' '}
            {formatDate(invoice.issueDate, locale)} · {t('ar.detail.dueDate')}:{' '}
            {formatDate(invoice.dueDate, locale)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {canIssue ? (
            <Button type="button" onClick={() => setDialog('issue')}>
              {t('ar.detail.actions.issue')}
            </Button>
          ) : null}
          {canRecordPayment ? (
            <Button type="button" onClick={() => setDialog('payment')}>
              {t('ar.detail.actions.recordPayment')}
            </Button>
          ) : null}
          {canVoid ? (
            <Button
              type="button"
              variant="outline"
              className="text-loss hover:bg-tint-loss"
              onClick={() => setDialog('void')}
            >
              {t('ar.detail.actions.void')}
            </Button>
          ) : null}
        </div>
      </div>

      {/* KPI tiles */}
      <div className="grid gap-4 sm:grid-cols-3">
        <KpiTile
          label={t('ar.detail.total')}
          minor={invoice.totalMinor}
          currency={invoice.currency}
          locale={locale}
          loading={false}
        />
        <KpiTile
          label={t('ar.detail.paid')}
          minor={invoice.paidMinor}
          currency={invoice.currency}
          locale={locale}
          loading={false}
          tone="text-profit-ink"
        />
        <KpiTile
          label={t('ar.detail.outstanding')}
          minor={invoice.outstandingMinor}
          currency={invoice.currency}
          locale={locale}
          loading={false}
          tone={invoice.outstandingMinor > 0 ? 'text-loss' : 'text-profit-ink'}
          emphatic
        />
      </div>

      {/* Line items */}
      <Card className="p-6">
        <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {t('ar.detail.lines')}
        </h2>
        {invoice.lines.length === 0 ? (
          <p className="py-2 text-sm text-ink-3">{t('ar.detail.noLines')}</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="py-2">{t('ar.detail.colDescription')}</th>
                <th className="py-2 text-right">{t('ar.detail.colQuantity')}</th>
                <th className="py-2 text-right">{t('ar.detail.colUnitPrice')}</th>
                <th className="py-2 text-right">{t('ar.detail.colLineTotal')}</th>
              </tr>
            </thead>
            <tbody>
              {invoice.lines.map((line) => (
                <tr key={line.lineNo} className="border-b border-ink-50 last:border-0">
                  <td className="py-2.5 text-ink-2">{line.description}</td>
                  <td className="tnum py-2.5 text-right font-mono text-ink-2">{line.quantity}</td>
                  <td className="tnum py-2.5 text-right font-mono text-ink-2">
                    {formatMoney(line.unitPriceMinor, invoice.currency, locale)}
                  </td>
                  <td className="tnum py-2.5 text-right font-mono text-ink">
                    {formatMoney(line.lineTotalMinor, invoice.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t-[1.5px] border-line-strong">
                <td colSpan={3} className="pt-3 text-sm font-semibold text-ink">
                  {t('ar.detail.total')}
                </td>
                <td className="tnum pt-3 text-right font-mono text-sm font-semibold text-ink">
                  {formatMoney(invoice.totalMinor, invoice.currency, locale)}
                </td>
              </tr>
            </tfoot>
          </table>
        )}
      </Card>

      {/* Payments */}
      <Card className="p-6">
        <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {t('ar.detail.payments')}
        </h2>
        {invoice.payments.length === 0 ? (
          <p className="py-2 text-sm text-ink-3">{t('ar.detail.noPayments')}</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="py-2">{t('ar.detail.colPaymentDate')}</th>
                <th className="py-2">{t('ar.detail.colPaymentMethod')}</th>
                <th className="py-2 text-right">{t('ar.detail.colPaymentAmount')}</th>
              </tr>
            </thead>
            <tbody>
              {invoice.payments.map((p) => (
                <tr key={p.id} className="border-b border-ink-50 last:border-0">
                  <td className="py-2.5 text-ink-2">{formatDate(p.paidAt, locale)}</td>
                  <td className="py-2.5 text-ink-2">{p.method ?? t('ar.detail.unknownMethod')}</td>
                  <td className="tnum py-2.5 text-right font-mono text-ink">
                    {formatMoney(p.amountMinor, p.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {dialog === 'issue' ? (
        <IssueDialog
          invoice={invoice}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'payment' ? (
        <PaymentDialog
          invoice={invoice}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'void' ? (
        <VoidDialog
          invoice={invoice}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

function IssueDialog({
  invoice,
  companyId,
  actor,
  onClose,
}: {
  invoice: InvoiceDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [termDays, setTermDays] = useState('')
  const mutation = useIssueInvoice({ companyId, actor, id: invoice.id })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsed = termDays.trim() === '' ? undefined : Number(termDays)
    mutation.mutate(
      { termDays: Number.isFinite(parsed) ? parsed : undefined },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('ar.detail.issueDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('ar.detail.issueDialog.body', { number: invoice.invoiceNumber })}
        </p>
        <Field
          label={t('ar.detail.issueDialog.termDaysLabel')}
          htmlFor="issue-term-days"
          hint={t('ar.detail.issueDialog.termDaysHint')}
        >
          <TextInput
            id="issue-term-days"
            type="number"
            min="1"
            step="1"
            value={termDays}
            onChange={(e) => setTermDays(e.target.value)}
          />
        </Field>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(invoiceErrorKey(mutation.error))}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending
              ? t('ar.detail.issueDialog.submitting')
              : t('ar.detail.issueDialog.confirm')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

function PaymentDialog({
  invoice,
  companyId,
  actor,
  onClose,
}: {
  invoice: InvoiceDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [amountMajor, setAmountMajor] = useState('')
  const [method, setMethod] = useState('')
  const mutation = useRecordPayment({ companyId, actor, id: invoice.id })

  const exponent = isoMinorExponent(invoice.currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const step = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()
  const parsedMajor = Number(amountMajor)
  const amountMinor =
    Number.isFinite(parsedMajor) && parsedMajor > 0 ? Math.round(parsedMajor * 10 ** exponent) : null
  const amountInvalid = amountMajor !== '' && amountMinor === null
  const amountOverpay = amountMinor !== null && amountMinor > invoice.outstandingMinor
  const canSubmit = !!amountMinor && !amountOverpay && !mutation.isPending

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!amountMinor || amountOverpay) return
    // One key per submit attempt: minted here (not inside the mutation fn), so TanStack Query's
    // automatic retries of THIS call reuse it, while the next submit gets a fresh one.
    mutation.mutate(
      { amountMinor, method: method.trim() || undefined, idempotencyKey: crypto.randomUUID() },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('ar.detail.paymentDialog.title')}
        </h2>
        <Field
          label={t('ar.detail.paymentDialog.amountLabel', { currency: invoice.currency })}
          htmlFor="pay-amount"
          hint={t('ar.detail.paymentDialog.outstandingHint', {
            amount: formatMoney(invoice.outstandingMinor, invoice.currency, locale),
          })}
        >
          <TextInput
            id="pay-amount"
            type="number"
            min="0"
            step={step}
            value={amountMajor}
            onChange={(e) => setAmountMajor(e.target.value)}
            required
            autoFocus
          />
        </Field>
        <Field label={t('ar.detail.paymentDialog.methodLabel')} htmlFor="pay-method">
          <TextInput
            id="pay-method"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            placeholder={t('ar.detail.paymentDialog.methodPlaceholder')}
          />
        </Field>
        {amountInvalid ? (
          <p className="text-sm text-loss">{t('ar.detail.paymentDialog.amountInvalid')}</p>
        ) : amountOverpay ? (
          <p className="text-sm text-loss">{t('ar.detail.paymentDialog.amountExceedsOutstanding')}</p>
        ) : null}
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(invoiceErrorKey(mutation.error))}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? t('ar.detail.paymentDialog.submitting')
              : t('ar.detail.paymentDialog.confirm')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

function VoidDialog({
  invoice,
  companyId,
  actor,
  onClose,
}: {
  invoice: InvoiceDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useVoidInvoice({ companyId, actor, id: invoice.id })

  function handleConfirm() {
    mutation.mutate(undefined, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('ar.detail.voidDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('ar.detail.voidDialog.body', { number: invoice.invoiceNumber })}
        </p>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(invoiceErrorKey(mutation.error))}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            className="bg-loss text-white hover:opacity-90"
            onClick={handleConfirm}
            disabled={mutation.isPending}
          >
            {mutation.isPending
              ? t('ar.detail.voidDialog.submitting')
              : t('ar.detail.voidDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}
