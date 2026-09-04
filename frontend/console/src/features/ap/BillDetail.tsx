import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, Info, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState, KpiTile } from '@/features/_shared/financeUi'
import { useOrgUnits } from '@/features/org/api'
import { formatShownQty, shownUnit, type UnitBearing } from '@/features/inventory/lib/units'
import type { Ingredient } from '@/features/inventory/ingredientApi'
import { apiFetch } from '@/lib/api'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import {
  useBill,
  usePostBill,
  useRecordPayment,
  useVoidBill,
  type BillDetail as BillDetailDto,
  type BillLine,
} from './api'
import { formatDate, billErrorKey } from './format'
import { DialogOverlay, BillStatusBadge, SELECT_CLASSES } from './parts'

/**
 * ADR 0072 P4 — resolves a linked line's `ingredientQtyBase` into the ingredient's DISPLAY unit
 * (mirrors `NewCompanyExpense.tsx`'s identically-named local hook exactly). A bill carries no
 * outlet column at all, so BillDetail cannot know which outlet a linked ingredient lives in on its
 * own — `businessId` here is a console-only RESOLVER filter the viewer optionally picks (the
 * "Show quantities for outlet" select inside `BillDetail`'s line-items Card), never sent anywhere.
 * Unresolved (no outlet picked, or the id isn't in the chosen outlet's catalog) falls back to the
 * plain base-unit count.
 */
function useIngredientsForOutlet(params: {
  companyId: string
  actor: string
  businessId: string
  enabled: boolean
}) {
  const { companyId, actor, businessId, enabled } = params
  return useQuery({
    enabled: enabled && !!businessId,
    queryKey: ['apBillIngredients', companyId, businessId],
    queryFn: async () => {
      const result = await apiFetch<Ingredient[]>('/api/v1/ingredients', {
        tenant: { companyId, actor },
        query: { businessId },
      })
      return result ?? []
    },
  })
}

/** The ADR 0072 P4 linkage snapshot for a line, formatted for display — the ingredient name plus
 *  either the resolved DISPLAY-unit quantity (e.g. "1.5 kg", when `resolved` matches by id) or the
 *  plain BASE-unit count (e.g. "1500 (satuan dasar)") when it doesn't resolve. `null` for a plain
 *  or inventory-flagged-but-unlinked line (`ingredientId` absent). */
function ingredientLinkLabel(
  line: Pick<BillLine, 'ingredientId' | 'ingredientName' | 'ingredientQtyBase'>,
  resolved: UnitBearing | null,
  locale: string,
  t: (key: string, opts?: Record<string, unknown>) => string,
): string | null {
  if (!line.ingredientId || line.ingredientQtyBase == null) return null
  const name = line.ingredientName || line.ingredientId
  if (resolved) {
    return `${name} · ${formatShownQty(line.ingredientQtyBase, resolved, locale)} ${shownUnit(resolved)}`
  }
  return `${name} · ${t('ap.detail.ingredientQtyBaseUnit', {
    qty: new Intl.NumberFormat(locale).format(line.ingredientQtyBase),
  })}`
}

/**
 * Bill detail (/bills/:id) — header (number, vendor, status, dates), KPI tiles (total/
 * paid/outstanding), an amber "estimated tax" badge when the bill used illustrative tax rules,
 * a line-items table, a payments table, and status-gated actions: Post (DRAFT), Record payment
 * (POSTED/PARTIALLY_PAID), Void (DRAFT/POSTED and unpaid). 409 invalid-state responses map to a
 * friendly i18n message via {@link billErrorKey}.
 */
export function BillDetail() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const { id } = useParams<{ id: string }>()
  const locale = localeOf(i18n.language)
  const [dialog, setDialog] = useState<'post' | 'payment' | 'void' | null>(null)
  // ADR 0072 P4 — an OPTIONAL, console-only outlet pick that resolves any linked line's
  // ingredientQtyBase into a display-unit quantity (e.g. "1.5 kg") — never sent anywhere; a bill
  // has no outlet column to send it to. Unresolved lines fall back to the plain base-unit count.
  const [resolveOutletId, setResolveOutletId] = useState('')

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''

  const query = useBill({
    companyId,
    actor,
    id: id ?? '',
    enabled: !!company && !!id,
  })
  const outletsQuery = useOrgUnits({ companyId, actor, enabled: !!company })
  const resolveIngredientsQuery = useIngredientsForOutlet({
    companyId,
    actor,
    businessId: resolveOutletId,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('ap.bills.noCompany')} hint={t('ap.bills.noCompanyHint')} />
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
        {t('ap.detail.error')}
      </Card>
    )
  }
  const bill = query.data
  if (!bill) {
    return (
      <div className="mx-auto max-w-md space-y-4 text-center">
        <EmptyState title={t('ap.detail.notFoundTitle')} hint={t('ap.detail.notFoundHint')} />
        <Link to="/bills" className="text-sm font-semibold text-brand-700 hover:underline">
          {t('ap.detail.backToBills')}
        </Link>
      </div>
    )
  }

  const canPost = bill.status === 'DRAFT'
  const canRecordPayment = bill.status === 'POSTED' || bill.status === 'PARTIALLY_PAID'
  const canVoid = (bill.status === 'DRAFT' || bill.status === 'POSTED') && bill.paidMinor === 0

  // ADR 0072 P4 — the ingredient-linkage display bits.
  const resolveOutlets = (outletsQuery.data ?? []).filter((u) => u.active)
  const resolvedIngredients = resolveIngredientsQuery.data ?? []
  const resolveIngredient = (ingredientId: string): UnitBearing | null =>
    resolvedIngredients.find((i) => i.id === ingredientId) ?? null
  const anyLinkedLine = bill.lines.some((l) => !!l.ingredientId)

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Breadcrumb trail */}
      <nav aria-label={t('ap.detail.breadcrumbLabel')} className="flex items-center gap-1.5 text-sm">
        <Link to="/bills" className="font-medium text-ink-3 transition-colors hover:text-brand-700">
          {t('ap.bills.title')}
        </Link>
        <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
        <span className="font-semibold text-ink">{bill.billNumber}</span>
      </nav>

      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
              {bill.billNumber}
            </h1>
            <BillStatusBadge status={bill.status} />
            {bill.usesIllustrativeRules ? (
              <Badge tone="amber">{t('ap.detail.estimatedTax')}</Badge>
            ) : null}
          </div>
          <p className="mt-1.5 text-sm text-ink-3">
            {t('ap.detail.vendor')}: {bill.vendorName} · {t('ap.detail.billDate')}:{' '}
            {formatDate(bill.billDate, locale)} · {t('ap.detail.dueDate')}:{' '}
            {formatDate(bill.dueDate, locale)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {canPost ? (
            <Button type="button" onClick={() => setDialog('post')}>
              {t('ap.detail.actions.post')}
            </Button>
          ) : null}
          {canRecordPayment ? (
            <Button type="button" onClick={() => setDialog('payment')}>
              {t('ap.detail.actions.recordPayment')}
            </Button>
          ) : null}
          {canVoid ? (
            <Button
              type="button"
              variant="outline"
              className="text-loss hover:bg-tint-loss"
              onClick={() => setDialog('void')}
            >
              {t('ap.detail.actions.void')}
            </Button>
          ) : null}
        </div>
      </div>

      {/* KPI tiles */}
      <div className="grid gap-4 sm:grid-cols-3">
        <KpiTile
          label={t('ap.detail.total')}
          minor={bill.totalMinor}
          currency={bill.currency}
          locale={locale}
          loading={false}
        />
        <KpiTile
          label={t('ap.detail.paid')}
          minor={bill.paidMinor}
          currency={bill.currency}
          locale={locale}
          loading={false}
          tone="text-profit-ink"
        />
        <KpiTile
          label={t('ap.detail.outstanding')}
          minor={bill.outstandingMinor}
          currency={bill.currency}
          locale={locale}
          loading={false}
          tone={bill.outstandingMinor > 0 ? 'text-loss' : 'text-profit-ink'}
          emphatic
        />
      </div>

      {/* Line items */}
      <Card className="p-6">
        <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {t('ap.detail.lines')}
        </h2>

        {/* ADR 0072 P4 — an OPTIONAL, console-only resolver: a bill has no outlet column, so
            picking one here only lets the console show a linked line's quantity in the
            ingredient's DISPLAY unit instead of the plain base-unit count. Never sent anywhere. */}
        {anyLinkedLine ? (
          <div className="mb-3 max-w-xs">
            <Field
              label={t('ap.detail.resolveOutletLabel')}
              htmlFor="bill-resolve-outlet"
              hint={t('ap.detail.resolveOutletHint')}
            >
              <select
                id="bill-resolve-outlet"
                className={SELECT_CLASSES}
                value={resolveOutletId}
                onChange={(e) => setResolveOutletId(e.target.value)}
              >
                <option value="">{t('ap.detail.resolveOutletPlaceholder')}</option>
                {resolveOutlets.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        ) : null}

        {bill.lines.length === 0 ? (
          <p className="py-2 text-sm text-ink-3">{t('ap.detail.noLines')}</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="py-2">{t('ap.detail.colDescription')}</th>
                <th className="py-2 text-right">{t('ap.detail.colQuantity')}</th>
                <th className="py-2 text-right">{t('ap.detail.colUnitPrice')}</th>
                <th className="py-2 text-right">{t('ap.detail.colLineTotal')}</th>
              </tr>
            </thead>
            <tbody>
              {bill.lines.map((line) => {
                const linkLabel = ingredientLinkLabel(
                  line,
                  line.ingredientId ? resolveIngredient(line.ingredientId) : null,
                  locale,
                  t,
                )
                return (
                  <tr key={line.lineNo} className="border-b border-ink-50 last:border-0">
                    <td className="py-2.5 text-ink-2">
                      <div>{line.description}</div>
                      {/* ADR 0067/0072 — the "Persediaan" indicator + (when linked) the ingredient
                          snapshot; a plain line renders unchanged. */}
                      {line.inventory ? (
                        <div className="mt-1 flex flex-wrap items-center gap-1.5">
                          <Badge tone="info">{t('ap.detail.inventoryBadge')}</Badge>
                          {linkLabel ? (
                            <span className="text-xs text-ink-3">{linkLabel}</span>
                          ) : null}
                        </div>
                      ) : null}
                    </td>
                    {/* ADR 0072 P4 UX rework — a LINKED line always carries quantity 1 and
                        unitPriceMinor == the line total (the bahan qty rides ingredientQtyBase,
                        shown in the description's subtext via `linkLabel` instead); rendering
                        "1" and the total again here would misleadingly read as a real per-unit
                        breakdown ("1 × total"), so both columns dash out for a linked line. */}
                    <td className="tnum py-2.5 text-right font-mono text-ink-2">
                      {line.ingredientId ? '—' : line.quantity}
                    </td>
                    <td className="tnum py-2.5 text-right font-mono text-ink-2">
                      {line.ingredientId ? '—' : formatMoney(line.unitPriceMinor, bill.currency, locale)}
                    </td>
                    <td className="tnum py-2.5 text-right font-mono text-ink">
                      {formatMoney(line.lineTotalMinor, bill.currency, locale)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
            <tfoot>
              <tr className="border-t-[1.5px] border-line-strong">
                <td colSpan={3} className="pt-3 text-sm font-semibold text-ink">
                  {t('ap.detail.total')}
                </td>
                <td className="tnum pt-3 text-right font-mono text-sm font-semibold text-ink">
                  {formatMoney(bill.totalMinor, bill.currency, locale)}
                </td>
              </tr>
            </tfoot>
          </table>
        )}
      </Card>

      {/* Payments */}
      <Card className="p-6">
        <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {t('ap.detail.payments')}
        </h2>
        {bill.payments.length === 0 ? (
          <p className="py-2 text-sm text-ink-3">{t('ap.detail.noPayments')}</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="py-2">{t('ap.detail.colPaymentDate')}</th>
                <th className="py-2">{t('ap.detail.colPaymentMethod')}</th>
                <th className="py-2 text-right">{t('ap.detail.colPaymentAmount')}</th>
              </tr>
            </thead>
            <tbody>
              {bill.payments.map((p) => (
                <tr key={p.id} className="border-b border-ink-50 last:border-0">
                  <td className="py-2.5 text-ink-2">{formatDate(p.paidAt, locale)}</td>
                  <td className="py-2.5 text-ink-2">{p.method ?? t('ap.detail.unknownMethod')}</td>
                  <td className="tnum py-2.5 text-right font-mono text-ink">
                    {formatMoney(p.amountMinor, p.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {dialog === 'post' ? (
        <PostDialog
          bill={bill}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'payment' ? (
        <PaymentDialog
          bill={bill}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'void' ? (
        <VoidDialog
          bill={bill}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

function PostDialog({
  bill,
  companyId,
  actor,
  onClose,
}: {
  bill: BillDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [termDays, setTermDays] = useState('')
  const mutation = usePostBill({ companyId, actor, id: bill.id })

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
          {t('ap.detail.postDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('ap.detail.postDialog.body', { number: bill.billNumber })}
        </p>
        <Field
          label={t('ap.detail.postDialog.termDaysLabel')}
          htmlFor="post-term-days"
          hint={t('ap.detail.postDialog.termDaysHint')}
        >
          <TextInput
            id="post-term-days"
            type="number"
            min="1"
            step="1"
            value={termDays}
            onChange={(e) => setTermDays(e.target.value)}
          />
        </Field>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(billErrorKey(mutation.error))}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending
              ? t('ap.detail.postDialog.submitting')
              : t('ap.detail.postDialog.confirm')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

function PaymentDialog({
  bill,
  companyId,
  actor,
  onClose,
}: {
  bill: BillDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [amountMajor, setAmountMajor] = useState('')
  const [method, setMethod] = useState('')
  const mutation = useRecordPayment({ companyId, actor, id: bill.id })

  const exponent = isoMinorExponent(bill.currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const step = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()
  const parsedMajor = Number(amountMajor)
  const amountMinor =
    Number.isFinite(parsedMajor) && parsedMajor > 0 ? Math.round(parsedMajor * 10 ** exponent) : null
  const amountInvalid = amountMajor !== '' && amountMinor === null
  const amountOverpay = amountMinor !== null && amountMinor > bill.outstandingMinor
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
          {t('ap.detail.paymentDialog.title')}
        </h2>
        <Field
          label={t('ap.detail.paymentDialog.amountLabel', { currency: bill.currency })}
          htmlFor="pay-amount"
          hint={t('ap.detail.paymentDialog.outstandingHint', {
            amount: formatMoney(bill.outstandingMinor, bill.currency, locale),
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
        <Field label={t('ap.detail.paymentDialog.methodLabel')} htmlFor="pay-method">
          <TextInput
            id="pay-method"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            placeholder={t('ap.detail.paymentDialog.methodPlaceholder')}
          />
        </Field>
        {amountInvalid ? (
          <p className="text-sm text-loss">{t('ap.detail.paymentDialog.amountInvalid')}</p>
        ) : amountOverpay ? (
          <p className="text-sm text-loss">{t('ap.detail.paymentDialog.amountExceedsOutstanding')}</p>
        ) : null}
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(billErrorKey(mutation.error))}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? t('ap.detail.paymentDialog.submitting')
              : t('ap.detail.paymentDialog.confirm')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/**
 * Code-review W3 (ADR 0072 P4) — a POSTED bill with ingredient-linked lines auto-received stock on
 * posting; voiding only reverses the MONEY (mirrors the company-expense void's fix-forward
 * posture, `CompanyExpensesList.tsx`'s `VoidCompanyExpenseDialog`). When any line carries an
 * `ingredientId`, this dialog shows the stock guidance BOTH before confirming (so the voider knows
 * what voiding will and won't undo) and after — stock is never auto-reverted; "Atur jumlah" or a
 * stock opname is the fix-forward path.
 */
function VoidDialog({
  bill,
  companyId,
  actor,
  onClose,
}: {
  bill: BillDetailDto
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useVoidBill({ companyId, actor, id: bill.id })
  const [voided, setVoided] = useState(false)
  const anyLinkedLine = bill.lines.some((l) => !!l.ingredientId)

  function handleConfirm() {
    mutation.mutate(undefined, { onSuccess: () => setVoided(true) })
  }

  if (voided) {
    return (
      <DialogOverlay onClose={onClose}>
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('ap.detail.voidDialog.doneTitle')}
          </h2>
          <p className="text-sm text-ink-2">{t('ap.detail.voidDialog.doneBody')}</p>
          {anyLinkedLine ? (
            <div className="flex items-start gap-2 rounded-xl bg-tint-warning px-3.5 py-3 text-sm text-ink-2">
              <Info className="mt-0.5 size-4 shrink-0 text-amber-2" aria-hidden="true" />
              <div>
                <p>{t('ap.detail.voidDialog.stockGuidance')}</p>
                <Link
                  to="/inventory"
                  className="mt-1 inline-block font-semibold text-brand-700 hover:underline"
                >
                  {t('ap.detail.voidDialog.stockGuidanceLink')}
                </Link>
              </div>
            </div>
          ) : null}
          <div className="flex justify-end">
            <Button type="button" onClick={onClose}>
              {t('common.close')}
            </Button>
          </div>
        </div>
      </DialogOverlay>
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('ap.detail.voidDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('ap.detail.voidDialog.body', { number: bill.billNumber })}
        </p>
        {anyLinkedLine ? (
          <p className="rounded-xl bg-tint-warning px-3.5 py-3 text-sm text-ink-2">
            {t('ap.detail.voidDialog.stockGuidanceNote')}
          </p>
        ) : null}
        {mutation.isError ? (
          <p className="text-sm text-loss">{t(billErrorKey(mutation.error))}</p>
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
              ? t('ap.detail.voidDialog.submitting')
              : t('ap.detail.voidDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}
