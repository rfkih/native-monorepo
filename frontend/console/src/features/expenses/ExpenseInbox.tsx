/**
 * Expense inbox (part of `/expenses`, ADR 0030 Phase E7) — the manager's SUBMITTED queue: approve
 * (optional comment + a read-only reimbursement-method display — the claim's OWN choice, not an
 * override; the backend has no override field) or refuse (comment required). Mirrors
 * `MyExpenses.tsx`'s list/detail shape and `features/ar`'s dialog idiom (Idempotency-Key minted
 * once per submit via `crypto.randomUUID()`).
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ImageOff, Inbox, ChevronLeft, ChevronRight, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { useSession } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { formatDate } from './format'
import { ClaimStatusBadge } from './parts'
import {
  useApproveClaim,
  useClaim,
  useClaims,
  useManagerReceiptUrl,
  useRefuseClaim,
  type ExpenseClaimDetail,
  type ExpenseClaimSummary,
} from './api'

const PAGE_SIZE = 10

export function ExpenseInbox() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const ready = !!company
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''

  const claims = useClaims({
    companyId,
    actor,
    status: 'SUBMITTED',
    page,
    size: PAGE_SIZE,
    enabled: ready,
  })
  const page0 = claims.data

  if (!company) {
    return <EmptyState title={t('expenses.list.noCompany')} hint={t('expenses.list.noCompanyHint')} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h2 className="font-display text-lg font-semibold text-ink">{t('expenses.inbox.title')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('expenses.inbox.subtitle')}</p>
      </div>

      {claims.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : claims.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('expenses.inbox.error')}
        </Card>
      ) : !page0 || page0.content.length === 0 ? (
        <EmptyState title={t('expenses.inbox.empty.title')} hint={t('expenses.inbox.empty.hint')} />
      ) : (
        <>
          <Card className="rounded-[20px] p-2.5">
            {page0.content.map((c) => (
              <InboxRow key={c.id} claim={c} locale={locale} onOpen={() => setSelectedId(c.id)} />
            ))}
          </Card>
          <Pagination page={page0.page} totalPages={page0.totalPages} onChange={setPage} />
        </>
      )}

      {selectedId ? (
        <DecisionDrawer
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

function InboxRow({
  claim,
  locale,
  onOpen,
}: {
  claim: ExpenseClaimSummary
  locale: string
  onOpen: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-emerald"
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
        <Inbox className="size-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-semibold text-ink">{claim.employeeName}</span>
          <span className="text-xs text-ink-3">{claim.categoryName}</span>
        </div>
        <p className="mt-0.5 truncate text-xs text-ink-3">
          {formatDate(claim.expenseDate, locale)}
          {claim.merchant ? ` · ${claim.merchant}` : ''} ·{' '}
          {t(`expenses.reimbursementMethod.${claim.reimbursementMethod}`)}
        </p>
      </div>
      <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
        {formatMoney(claim.amountMinor, claim.currency, locale)}
      </span>
    </button>
  )
}

function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number
  totalPages: number
  onChange: (page: number) => void
}) {
  const { t } = useTranslation()
  if (totalPages <= 1) return null
  return (
    <div className="mt-1 flex items-center justify-center gap-3">
      <button
        type="button"
        onClick={() => onChange(page - 1)}
        disabled={page <= 0}
        aria-label={t('me.expenses.pagination.prev')}
        className="grid size-9 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-emerald"
      >
        <ChevronLeft className="size-4" />
      </button>
      <span className="tnum text-sm text-ink-3">
        {t('me.expenses.pagination.pageOf', { page: page + 1, total: totalPages })}
      </span>
      <button
        type="button"
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages - 1}
        aria-label={t('me.expenses.pagination.next')}
        className="grid size-9 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-emerald"
      >
        <ChevronRight className="size-4" />
      </button>
    </div>
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

function DecisionDrawer({
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
  const [dialog, setDialog] = useState<'approve' | 'refuse' | null>(null)

  const claim = detail.data

  return (
    <DialogOverlay onClose={onClose}>
      {dialog === 'approve' && claim ? (
        <ApproveDialog
          claim={claim}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
          onDecided={onClose}
        />
      ) : dialog === 'refuse' && claim ? (
        <RefuseDialog
          claim={claim}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
          onDecided={onClose}
        />
      ) : (
        <div className="space-y-5">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('expenses.inbox.decision.title')}
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
                <Detail
                  label={t('me.expenses.detail.merchant')}
                  value={claim.merchant || '—'}
                />
                <Detail
                  label={t('me.expenses.detail.method')}
                  value={t(`expenses.reimbursementMethod.${claim.reimbursementMethod}`)}
                />
              </div>

              {claim.note ? (
                <div>
                  <div className="text-xs font-semibold uppercase tracking-wider text-ink-3">
                    {t('me.expenses.detail.note')}
                  </div>
                  <p className="mt-0.5 whitespace-pre-wrap text-sm text-ink-2">{claim.note}</p>
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

              <div className="flex flex-wrap justify-end gap-2 pt-1">
                <Button type="button" variant="outline" onClick={onClose}>
                  {t('me.expenses.detail.close')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="text-loss hover:bg-tint-loss"
                  onClick={() => setDialog('refuse')}
                >
                  {t('expenses.actions.refuse')}
                </Button>
                <Button type="button" onClick={() => setDialog('approve')}>
                  {t('expenses.actions.approve')}
                </Button>
              </div>
            </>
          )}
        </div>
      )}
    </DialogOverlay>
  )
}

function ApproveDialog({
  claim,
  companyId,
  actor,
  onClose,
  onDecided,
}: {
  claim: ExpenseClaimDetail
  companyId: string
  actor: string
  onClose: () => void
  onDecided: () => void
}) {
  const { t } = useTranslation()
  const [comment, setComment] = useState('')
  const approve = useApproveClaim({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    approve.mutate(
      { id: claim.id, comment: comment.trim() || undefined, idempotencyKey: crypto.randomUUID() },
      { onSuccess: onDecided },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <h2 className="font-display text-lg font-semibold text-ink">
        {t('expenses.actions.approveDialog.title')}
      </h2>
      <p className="text-sm text-ink-2">{t('expenses.actions.approveDialog.body')}</p>

      <div className="rounded-xl bg-tint-info px-3.5 py-3 text-sm text-ink-2">
        <div className="text-[11px] font-semibold uppercase tracking-wider text-info">
          {t('expenses.actions.approveDialog.methodLabel')}
        </div>
        <p className="mt-0.5 font-semibold">
          {t(`expenses.reimbursementMethod.${claim.reimbursementMethod}`)}
        </p>
        <p className="mt-1 text-xs text-ink-3">
          {t(
            claim.reimbursementMethod === 'PAYROLL'
              ? 'me.expenses.form.methodPayrollHint'
              : 'me.expenses.form.methodDirectHint',
          )}
        </p>
      </div>

      <Field label={t('expenses.actions.approveDialog.commentLabel')} htmlFor="approve-comment">
        <textarea
          id="approve-comment"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          rows={3}
          maxLength={4000}
          className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
      </Field>

      {approve.isError ? (
        <p className="text-sm text-loss">{t('expenses.actions.error')}</p>
      ) : null}

      <div className="flex justify-end gap-3">
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={approve.isPending}>
          {approve.isPending
            ? t('expenses.actions.saving')
            : t('expenses.actions.approveDialog.confirm')}
        </Button>
      </div>
    </form>
  )
}

function RefuseDialog({
  claim,
  companyId,
  actor,
  onClose,
  onDecided,
}: {
  claim: ExpenseClaimDetail
  companyId: string
  actor: string
  onClose: () => void
  onDecided: () => void
}) {
  const { t } = useTranslation()
  const [comment, setComment] = useState('')
  const refuse = useRefuseClaim({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!comment.trim()) return
    refuse.mutate(
      { id: claim.id, comment: comment.trim(), idempotencyKey: crypto.randomUUID() },
      { onSuccess: onDecided },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <h2 className="font-display text-lg font-semibold text-ink">
        {t('expenses.actions.refuseDialog.title')}
      </h2>
      <p className="text-sm text-ink-2">{t('expenses.actions.refuseDialog.body')}</p>

      <Field
        label={t('expenses.actions.refuseDialog.commentLabel')}
        htmlFor="refuse-comment"
        hint={t('expenses.actions.refuseDialog.commentHint')}
      >
        <textarea
          id="refuse-comment"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          rows={3}
          maxLength={4000}
          required
          className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
      </Field>

      {refuse.isError ? <p className="text-sm text-loss">{t('expenses.actions.error')}</p> : null}

      <div className="flex justify-end gap-3">
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button
          type="submit"
          className="bg-loss text-white hover:opacity-90"
          disabled={refuse.isPending || !comment.trim()}
        >
          {refuse.isPending
            ? t('expenses.actions.saving')
            : t('expenses.actions.refuseDialog.confirm')}
        </Button>
      </div>
    </form>
  )
}
