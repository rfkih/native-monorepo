/**
 * "My expenses" (/me/expenses, ADR 0030 Phase E6) — the employee self-service expense-claims
 * surface. Full-screen (outside the back-office Shell), reachable by every business role (mirrors
 * how {@code /me} itself is never page-gated — see app/App.tsx). A paginated list, a detail sheet
 * (fields + receipt preview + guarded submit/cancel with confirm), and the new/edit-draft dialog.
 * Money via `lib/money` Intl formatting; dates via `./format`'s Intl wrapper — never hand-built.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ChevronLeft,
  ChevronRight,
  ImageOff,
  LogOut,
  Plus,
  Receipt as ReceiptIcon,
  TriangleAlert,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { formatDate } from './format'
import { NewClaimDialog } from './NewClaimDialog'
import { ClaimStatusBadge } from './parts'
import {
  useCancelClaim,
  useMyClaim,
  useMyClaims,
  useMyReceiptUrl,
  useSubmitClaim,
  type ExpenseClaimDetail,
  type MyExpenseClaim,
} from './api'

const PAGE_SIZE = 10

type DialogState = { mode: 'create' } | { mode: 'edit'; claim: ExpenseClaimDetail }

export function MyExpenses() {
  const { t, i18n } = useTranslation()
  const auth = useAuth()
  const { company, loading: sessionLoading } = useSession()
  const locale = localeOf(i18n.language)

  const [page, setPage] = useState(0)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [dialog, setDialog] = useState<DialogState | null>(null)

  const ready = !!company
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? auth.actor
  const currency = company?.baseCurrency ?? 'IDR'

  const claims = useMyClaims({ companyId, actor, page, size: PAGE_SIZE, enabled: ready })
  const page0 = claims.data

  return (
    <div className="min-h-screen bg-paper">
      {/* Topbar — mirrors features/me/Me.tsx's chrome */}
      <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        <Link
          to="/me"
          className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
        >
          {t('me.expenses.back')}
        </Link>
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            className="flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
          >
            <LogOut className="size-4" />
            <span className="hidden sm:inline">{t('nav.logout')}</span>
          </button>
        ) : null}
      </header>

      <main className="mx-auto w-full max-w-[900px] px-5 py-8 lg:px-8">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-ink">
              {t('me.expenses.title')}
            </h1>
            <p className="text-sm text-ink-3">{t('me.expenses.subtitle')}</p>
          </div>
          <Button type="button" onClick={() => setDialog({ mode: 'create' })} disabled={!ready}>
            <Plus className="size-4" />
            {t('me.expenses.newClaim')}
          </Button>
        </div>

        <div className="mt-6">
          {!ready ? (
            sessionLoading ? (
              <Card className="p-10 text-center">
                <Spinner className="mx-auto text-brand-500" />
              </Card>
            ) : (
              <EmptyState
                title={t('me.expenses.noCompany.title')}
                hint={t('me.expenses.noCompany.hint')}
              />
            )
          ) : claims.isLoading ? (
            <Card className="p-10 text-center">
              <Spinner className="mx-auto text-brand-500" />
            </Card>
          ) : claims.isError ? (
            <Card className="p-8 text-center text-sm text-loss">
              <TriangleAlert className="mx-auto mb-2 size-5" />
              {t('me.expenses.loadError')}
            </Card>
          ) : !page0 || page0.content.length === 0 ? (
            <EmptyState title={t('me.expenses.empty.title')} hint={t('me.expenses.empty.hint')} />
          ) : (
            <>
              <Card className="rounded-[20px] p-2.5">
                {page0.content.map((c) => (
                  <ClaimRow key={c.id} claim={c} locale={locale} onOpen={() => setDetailId(c.id)} />
                ))}
              </Card>
              <Pagination page={page0.page} totalPages={page0.totalPages} onChange={setPage} />
            </>
          )}
        </div>
      </main>

      {detailId ? (
        <ClaimDetailSheet
          id={detailId}
          companyId={companyId}
          actor={actor}
          locale={locale}
          onClose={() => setDetailId(null)}
          onEdit={(full) => {
            setDetailId(null)
            setDialog({ mode: 'edit', claim: full })
          }}
        />
      ) : null}

      {dialog ? (
        <NewClaimDialog
          companyId={companyId}
          actor={actor}
          currency={currency}
          claim={dialog.mode === 'edit' ? dialog.claim : null}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

function ClaimRow({
  claim,
  locale,
  onOpen,
}: {
  claim: MyExpenseClaim
  locale: string
  onOpen: () => void
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-emerald"
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
        <ReceiptIcon className="size-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-semibold text-ink">{claim.categoryName}</span>
          <ClaimStatusBadge status={claim.status} />
        </div>
        <p className="mt-0.5 truncate text-xs text-ink-3">
          {formatDate(claim.expenseDate, locale)}
          {claim.merchant ? ` · ${claim.merchant}` : ''}
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
    <div className="mt-3 flex items-center justify-center gap-3">
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

type ConfirmKind = 'submit' | 'cancel'

function ClaimDetailSheet({
  id,
  companyId,
  actor,
  locale,
  onClose,
  onEdit,
}: {
  id: string
  companyId: string
  actor: string
  locale: string
  onClose: () => void
  onEdit: (claim: ExpenseClaimDetail) => void
}) {
  const { t } = useTranslation()
  const detail = useMyClaim({ companyId, actor, id, enabled: true })
  const receipt = useMyReceiptUrl({ companyId, actor, id, enabled: true })
  const submitClaim = useSubmitClaim({ companyId, actor })
  const cancelClaim = useCancelClaim({ companyId, actor })
  const [confirm, setConfirm] = useState<ConfirmKind | null>(null)

  const claim = detail.data

  function handleSubmit() {
    submitClaim.mutate(
      { id, idempotencyKey: crypto.randomUUID() },
      { onSuccess: () => setConfirm(null) },
    )
  }

  function handleCancel() {
    cancelClaim.mutate(
      { id, idempotencyKey: crypto.randomUUID() },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      {confirm ? (
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t(confirm === 'submit' ? 'me.expenses.confirmSubmit.title' : 'me.expenses.confirmCancel.title')}
          </h2>
          <p className="text-sm text-ink-2">
            {t(confirm === 'submit' ? 'me.expenses.confirmSubmit.body' : 'me.expenses.confirmCancel.body')}
          </p>
          {(submitClaim.isError || cancelClaim.isError) ? (
            <p className="text-sm text-loss">{t('me.expenses.detail.actionError')}</p>
          ) : null}
          <div className="flex justify-end gap-3">
            <Button type="button" variant="outline" onClick={() => setConfirm(null)}>
              {t('common.cancel')}
            </Button>
            <Button
              type="button"
              className={confirm === 'cancel' ? 'bg-loss text-white hover:opacity-90' : undefined}
              disabled={confirm === 'submit' ? submitClaim.isPending : cancelClaim.isPending}
              onClick={confirm === 'submit' ? handleSubmit : handleCancel}
            >
              {t(
                confirm === 'submit'
                  ? 'me.expenses.confirmSubmit.confirm'
                  : 'me.expenses.confirmCancel.confirm',
              )}
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-5">
          <h2 className="font-display text-lg font-semibold text-ink">{t('me.expenses.detail.title')}</h2>

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
                <Detail label={t('me.expenses.detail.date')} value={formatDate(claim.expenseDate, locale)} />
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

              <div className="flex flex-wrap justify-end gap-2 pt-1">
                <Button type="button" variant="outline" onClick={onClose}>
                  {t('me.expenses.detail.close')}
                </Button>
                {claim.status === 'DRAFT' ? (
                  <Button type="button" variant="outline" onClick={() => onEdit(claim)}>
                    {t('me.expenses.detail.edit')}
                  </Button>
                ) : null}
                {claim.status === 'DRAFT' || claim.status === 'SUBMITTED' ? (
                  <Button type="button" variant="outline" onClick={() => setConfirm('cancel')}>
                    {t('me.expenses.detail.cancelClaim')}
                  </Button>
                ) : null}
                {claim.status === 'DRAFT' ? (
                  <Button type="button" onClick={() => setConfirm('submit')}>
                    {t('me.expenses.detail.submit')}
                  </Button>
                ) : null}
              </div>
            </>
          )}
        </div>
      )}
    </DialogOverlay>
  )
}
