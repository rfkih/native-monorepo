/**
 * Expenses list (part of `/expenses`, ADR 0030 Phase E7) — the manager's tenant-wide claim list:
 * status + org-unit filters, click-through to a detail drawer (fields + receipt preview), and the
 * settlement/correction actions once a claim is APPROVED: "Pay now" (DIRECT reimbursement, Phase
 * E4) and "Void" (the correction path, Phase E7) — each disabled with a surfaced reason when the
 * claim is linked to a payroll run or already settled (`./format`'s `payDisabledReason`/
 * `voidDisabledReason`, mirroring the backend guards exactly).
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ImageOff, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Select } from '@/components/ui/Select'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { useOrgUnits } from '@/features/org/api'
import { useSession } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { formatDate, payDisabledReason, voidDisabledReason } from './format'
import { ClaimStatusBadge } from './parts'
import {
  useClaim,
  useClaims,
  useManagerReceiptUrl,
  usePayClaimNow,
  useVoidClaim,
  type ClaimStatus,
  type ExpenseClaimDetail,
  type ExpenseClaimSummary,
} from './api'

const PAGE_SIZE = 15
const ALL = 'ALL'
type StatusFilter = ClaimStatus | typeof ALL

const STATUSES: ClaimStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'REFUSED',
  'CANCELLED',
  'VOIDED',
  'REIMBURSED',
]

export function ExpensesList() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [status, setStatus] = useState<StatusFilter>(ALL)
  const [orgUnitId, setOrgUnitId] = useState('')
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const ready = !!company
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''

  const orgUnits = useOrgUnits({ companyId, actor, enabled: ready })
  const claims = useClaims({
    companyId,
    actor,
    status: status === ALL ? undefined : status,
    orgUnitId: orgUnitId || undefined,
    page,
    size: PAGE_SIZE,
    enabled: ready,
  })
  const page0 = claims.data

  if (!company) {
    return <EmptyState title={t('expenses.list.noCompany')} hint={t('expenses.list.noCompanyHint')} />
  }

  const orgUnitName = (id: string) => orgUnits.data?.find((u) => u.id === id)?.name ?? id

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h2 className="font-display text-lg font-semibold text-ink">{t('expenses.list.title')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('expenses.list.subtitle')}</p>
      </div>

      <div className="flex flex-wrap gap-3">
        <div className="w-full max-w-[220px]">
          <Select
            aria-label={t('expenses.list.filterStatus')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as StatusFilter)
              setPage(0)
            }}
          >
            <option value={ALL}>{t('expenses.list.filterStatusAll')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`expenses.status.${s}`)}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-full max-w-[260px]">
          <Select
            aria-label={t('expenses.list.filterOrgUnit')}
            value={orgUnitId}
            onChange={(e) => {
              setOrgUnitId(e.target.value)
              setPage(0)
            }}
          >
            <option value="">{t('expenses.list.filterOrgUnitAll')}</option>
            {(orgUnits.data ?? []).map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </Select>
        </div>
      </div>

      {claims.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : claims.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('expenses.list.error')}
        </Card>
      ) : !page0 || page0.content.length === 0 ? (
        <EmptyState title={t('expenses.list.empty')} hint={t('expenses.list.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('expenses.list.colEmployee')}</th>
                <th className="px-4 py-3">{t('expenses.list.colCategory')}</th>
                <th className="px-4 py-3">{t('expenses.list.colOrgUnit')}</th>
                <th className="px-4 py-3">{t('expenses.list.colStatus')}</th>
                <th className="px-4 py-3">{t('expenses.list.colDate')}</th>
                <th className="px-4 py-3 text-right">{t('expenses.list.colAmount')}</th>
              </tr>
            </thead>
            <tbody>
              {page0.content.map((c) => (
                <ClaimRow key={c.id} claim={c} locale={locale} onOpen={() => setSelectedId(c.id)} orgUnitName={orgUnitName} />
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {page0 && page0.totalPages > 1 ? (
        <div className="flex items-center justify-center gap-3">
          <Button
            type="button"
            variant="outline"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page0.page <= 0}
          >
            {t('me.expenses.pagination.prev')}
          </Button>
          <span className="tnum text-sm text-ink-3">
            {t('me.expenses.pagination.pageOf', { page: page0.page + 1, total: page0.totalPages })}
          </span>
          <Button
            type="button"
            variant="outline"
            onClick={() => setPage((p) => p + 1)}
            disabled={page0.page >= page0.totalPages - 1}
          >
            {t('me.expenses.pagination.next')}
          </Button>
        </div>
      ) : null}

      {selectedId ? (
        <ClaimDetailDrawer
          id={selectedId}
          companyId={companyId}
          actor={actor}
          locale={locale}
          onClose={() => setSelectedId(null)}
        />
      ) : null}
    </div>
  )
}

function ClaimRow({
  claim,
  locale,
  onOpen,
  orgUnitName,
}: {
  claim: ExpenseClaimSummary
  locale: string
  onOpen: () => void
  orgUnitName: (id: string) => string
}) {
  return (
    <tr className="border-b border-ink-50 last:border-0 hover:bg-hover">
      <td className="px-4 py-3">
        <button
          type="button"
          onClick={onOpen}
          className="font-semibold text-ink hover:text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-emerald"
        >
          {claim.employeeName}
        </button>
      </td>
      <td className="px-4 py-3 text-ink-2">{claim.categoryName}</td>
      <td className="px-4 py-3 text-ink-3">{orgUnitName(claim.orgUnitId)}</td>
      <td className="px-4 py-3">
        <ClaimStatusBadge status={claim.status} />
      </td>
      <td className="px-4 py-3 text-ink-3">{formatDate(claim.expenseDate, locale)}</td>
      <td className="tnum px-4 py-3 text-right font-mono text-ink">
        {formatMoney(claim.amountMinor, claim.currency, locale)}
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

function ClaimDetailDrawer({
  id,
  companyId,
  actor,
  locale,
  onClose,
}: {
  id: string
  companyId: string
  actor: string
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const detail = useClaim({ companyId, actor, id, enabled: true })
  const receipt = useManagerReceiptUrl({ companyId, actor, id, enabled: true })
  const [dialog, setDialog] = useState<'pay' | 'void' | null>(null)

  const claim = detail.data

  return (
    <DialogOverlay onClose={onClose}>
      {dialog === 'pay' && claim ? (
        <PayNowDialog
          claim={claim}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
          onDone={onClose}
        />
      ) : dialog === 'void' && claim ? (
        <VoidDialog
          claim={claim}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
          onDone={onClose}
        />
      ) : (
        <div className="space-y-5">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('expenses.detail.title')}
          </h2>

          {detail.isLoading || !claim ? (
            <Spinner className="text-brand-500" />
          ) : (
            <>
              <div className="flex items-center gap-3">
                <ClaimStatusBadge status={claim.status} />
                <span className="tnum font-mono text-xl font-semibold text-ink">
                  {formatMoney(claim.amountMinor, claim.currency, locale)}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3 rounded-2xl border border-line bg-surface p-4 text-sm">
                <Detail
                  label={t('me.expenses.detail.date')}
                  value={formatDate(claim.expenseDate, locale)}
                />
                <Detail label={t('me.expenses.detail.merchant')} value={claim.merchant || '—'} />
                <Detail
                  label={t('me.expenses.detail.method')}
                  value={t(`expenses.reimbursementMethod.${claim.reimbursementMethod}`)}
                />
                {claim.decidedBy ? (
                  <Detail label={t('me.expenses.detail.decidedBy')} value={claim.decidedBy} />
                ) : null}
              </div>

              {claim.note ? (
                <div>
                  <div className="text-xs font-semibold uppercase tracking-wider text-ink-3">
                    {t('me.expenses.detail.note')}
                  </div>
                  <p className="mt-0.5 whitespace-pre-wrap text-sm text-ink-2">{claim.note}</p>
                </div>
              ) : null}

              {claim.decisionComment ? (
                <div className="rounded-xl bg-tint-info px-3.5 py-3 text-sm text-ink-2">
                  <div className="text-[11px] font-semibold uppercase tracking-wider text-info">
                    {t('me.expenses.detail.decisionComment')}
                  </div>
                  <p className="mt-0.5">{claim.decisionComment}</p>
                </div>
              ) : null}

              <div>
                <div className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-ink-3">
                  {t('me.expenses.detail.receiptTitle')}
                </div>
                {receipt.status === 'loading' ? (
                  <div className="flex items-center gap-2 text-sm text-ink-3">
                    <Spinner /> {t('me.expenses.detail.receiptLoading')}
                  </div>
                ) : receipt.status === 'ready' && receipt.url ? (
                  <img
                    src={receipt.url}
                    alt={t('me.expenses.detail.receiptTitle')}
                    className="max-h-64 w-full rounded-xl border border-line object-contain"
                  />
                ) : receipt.status === 'error' ? (
                  <p className="text-sm text-loss">{t('me.expenses.detail.receiptError')}</p>
                ) : (
                  <div className="flex items-center gap-2 text-sm text-ink-3">
                    <ImageOff className="size-4" aria-hidden="true" />
                    {t('me.expenses.detail.receiptNone')}
                  </div>
                )}
              </div>

              <ClaimActions claim={claim} onPay={() => setDialog('pay')} onVoid={() => setDialog('void')} />

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

function ClaimActions({
  claim,
  onPay,
  onVoid,
}: {
  claim: ExpenseClaimDetail
  onPay: () => void
  onVoid: () => void
}) {
  const { t } = useTranslation()
  const payReason = payDisabledReason(claim)
  const voidReason = voidDisabledReason(claim)

  return (
    <div className="space-y-2 border-t border-line pt-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" onClick={onPay} disabled={!!payReason} title={payReason ? t(payReason) : undefined}>
          {t('expenses.actions.payNow')}
        </Button>
        <Button
          type="button"
          variant="outline"
          className="text-loss hover:bg-tint-loss"
          onClick={onVoid}
          disabled={!!voidReason}
          title={voidReason ? t(voidReason) : undefined}
        >
          {t('expenses.actions.void')}
        </Button>
      </div>
      {payReason ? <p className="text-xs text-ink-3">{t(payReason)}</p> : null}
      {voidReason ? <p className="text-xs text-ink-3">{t(voidReason)}</p> : null}
    </div>
  )
}

function PayNowDialog({
  claim,
  companyId,
  actor,
  onClose,
  onDone,
}: {
  claim: ExpenseClaimDetail
  companyId: string
  actor: string
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const payNow = usePayClaimNow({ companyId, actor })

  function handleConfirm() {
    payNow.mutate({ id: claim.id, idempotencyKey: crypto.randomUUID() }, { onSuccess: onDone })
  }

  return (
    <div className="space-y-4">
      <h2 className="font-display text-lg font-semibold text-ink">
        {t('expenses.actions.payDialog.title')}
      </h2>
      <p className="text-sm text-ink-2">{t('expenses.actions.payDialog.body')}</p>
      {payNow.isError ? <p className="text-sm text-loss">{t('expenses.actions.error')}</p> : null}
      <div className="flex justify-end gap-3">
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="button" onClick={handleConfirm} disabled={payNow.isPending}>
          {payNow.isPending ? t('expenses.actions.saving') : t('expenses.actions.payDialog.confirm')}
        </Button>
      </div>
    </div>
  )
}

function VoidDialog({
  claim,
  companyId,
  actor,
  onClose,
  onDone,
}: {
  claim: ExpenseClaimDetail
  companyId: string
  actor: string
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const [comment, setComment] = useState('')
  const voidClaim = useVoidClaim({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!comment.trim()) return
    voidClaim.mutate(
      { id: claim.id, comment: comment.trim(), idempotencyKey: crypto.randomUUID() },
      { onSuccess: onDone },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <h2 className="font-display text-lg font-semibold text-ink">
        {t('expenses.actions.voidDialog.title')}
      </h2>
      <p className="text-sm text-ink-2">{t('expenses.actions.voidDialog.body')}</p>
      <div className="space-y-1.5">
        <label htmlFor="void-comment" className="text-sm font-medium text-ink-2">
          {t('expenses.actions.voidDialog.commentLabel')}
        </label>
        <textarea
          id="void-comment"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          rows={3}
          maxLength={4000}
          required
          className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
        <p className="text-xs text-ink-3">{t('expenses.actions.voidDialog.commentHint')}</p>
      </div>
      {voidClaim.isError ? <p className="text-sm text-loss">{t('expenses.actions.error')}</p> : null}
      <div className="flex justify-end gap-3">
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button
          type="submit"
          className="bg-loss text-white hover:opacity-90"
          disabled={voidClaim.isPending || !comment.trim()}
        >
          {voidClaim.isPending
            ? t('expenses.actions.saving')
            : t('expenses.actions.voidDialog.confirm')}
        </Button>
      </div>
    </form>
  )
}
