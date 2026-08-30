/**
 * Detail klaim (/me/expenses/:id, ADR 0049 P5 redesign) — a PUSHED screen (no tab bar): the full
 * record for one of the caller's own claims plus the guarded submit/cancel transitions, matching
 * MyExpenses's ClaimDetailSheet (console) field-for-field but as a full-height phone screen with a
 * sticky back header and a pinned action footer instead of a dialog.
 */
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ImageOff, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Skeleton } from '@/components/ui/Skeleton'
import { Spinner } from '@/components/ui/Spinner'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { guardedNavigateBack } from '@/components/mobile/backGuardProtocol'
import { EmptyState } from '@/features/_shared/financeUi'
import { ClaimStatusBadge } from '@/features/expenses/parts'
import { formatDate } from '@/features/expenses/format'
import {
  useCancelClaim,
  useMyCategories,
  useMyClaim,
  useMyReceiptUrl,
  useSubmitClaim,
} from '@/features/expenses/api'
import { NewClaimDialog } from '@/features/expenses/NewClaimDialog'
import { isNotLinked } from '@/features/me/api'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { SectionLabel, useTenant } from './ui'

export function ClaimDetailScreen() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const navigate = useNavigate()
  const { id: rawId } = useParams()
  const id = rawId ?? null
  const { companyId, actor } = useTenant()

  const detail = useMyClaim({ companyId, actor, id, enabled: true })
  const receipt = useMyReceiptUrl({ companyId, actor, id, enabled: true })
  // ExpenseClaimDetail carries only categoryId (unlike the list's MyExpenseClaim, which already
  // has categoryName) — resolve the display name from the same category picker MyExpenses reuses.
  const categories = useMyCategories({ companyId, actor, enabled: true })
  const submitClaim = useSubmitClaim({ companyId, actor })
  const cancelClaim = useCancelClaim({ companyId, actor })
  const notLinked = isNotLinked(detail.error)
  const [showEdit, setShowEdit] = useState(false)

  const d = detail.data
  const categoryName = categories.data?.find((cat) => cat.id === d?.categoryId)?.name ?? null

  function handleSubmit() {
    if (!id) return
    submitClaim.mutate(
      { id, idempotencyKey: crypto.randomUUID() },
      { onSuccess: () => navigate('/me/expenses') },
    )
  }

  function handleCancel() {
    if (!id) return
    cancelClaim.mutate(
      { id, idempotencyKey: crypto.randomUUID() },
      { onSuccess: () => navigate('/me/expenses') },
    )
  }

  return (
    <div className="flex min-h-[100dvh] flex-col bg-paper">
      <ScreenHeader title={t('staff.claimDetail.title')} onBack={() => guardedNavigateBack(navigate)} />

      <div className="flex-1 overflow-y-auto">
        {detail.isLoading ? (
          <div aria-busy="true" className="flex flex-col gap-3 p-4 pt-5">
            <Skeleton className="h-6 w-24 rounded-full" />
            <Skeleton className="mt-2 h-9 w-40" />
            <Skeleton className="h-4 w-28" />
            <Skeleton className="mt-3 h-32 rounded-[18px]" />
            <Skeleton className="h-20 rounded-[18px]" />
          </div>
        ) : notLinked ? (
          <div className="p-4 pt-6">
            <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
          </div>
        ) : detail.isError || !d ? (
          <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('me.error')}
          </Card>
        ) : (
          <div className="px-4 pb-8 pt-5">
            <ClaimStatusBadge status={d.status} />
            <div className="tnum mt-3 font-mono text-[32px] font-bold leading-none tracking-[-0.02em] text-ink">
              {formatMoney(d.amountMinor, d.currency, locale)}
            </div>
            {categoryName ? (
              <div className="mt-1.5 text-[14px] font-medium text-ink-3">{categoryName}</div>
            ) : null}

            <Card className="mt-5 grid grid-cols-2 gap-4 p-4">
              <DetailField label={t('staff.claimDetail.date')} value={formatDate(d.expenseDate, locale)} />
              <DetailField label={t('staff.claimDetail.merchant')} value={d.merchant ?? '—'} />
              <DetailField
                label={t('staff.claimDetail.method')}
                value={t(
                  d.reimbursementMethod === 'PAYROLL'
                    ? 'staff.claimDetail.methodPayroll'
                    : 'staff.claimDetail.methodDirect',
                )}
              />
              <DetailField label={t('staff.claimDetail.decidedBy')} value={d.decidedBy ?? '—'} />
            </Card>

            {d.note ? (
              <div className="mt-5">
                <SectionLabel>{t('staff.claimDetail.note')}</SectionLabel>
                <p className="mt-1.5 whitespace-pre-wrap text-[14px] leading-snug text-ink-2">{d.note}</p>
              </div>
            ) : null}

            {d.decisionComment ? (
              <div className="mt-5 rounded-[16px] bg-tint-info px-4 py-3.5">
                <div className="text-[11px] font-semibold uppercase tracking-[0.06em] text-info">
                  {t('staff.claimDetail.decisionComment')}
                </div>
                <p className="mt-1 text-[14px] leading-snug text-ink-2">{d.decisionComment}</p>
              </div>
            ) : null}

            <div className="mt-5">
              <SectionLabel>{t('staff.claimDetail.receipt')}</SectionLabel>
              <div className="mt-1.5">
                {receipt.status === 'ready' && receipt.url ? (
                  <img
                    src={receipt.url}
                    alt={t('staff.claimDetail.receipt')}
                    className="w-full rounded-[16px] border border-line object-contain"
                  />
                ) : (
                  <div className="flex items-center gap-2 rounded-[16px] border border-dashed border-line bg-surface px-4 py-5 text-[13px] text-ink-3">
                    {receipt.status === 'loading' ? (
                      <Spinner />
                    ) : (
                      <ImageOff className="size-4 shrink-0" aria-hidden />
                    )}
                    {t(
                      receipt.status === 'none'
                        ? 'staff.claimDetail.noReceipt'
                        : 'staff.claimDetail.receiptAttached',
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>

      {d ? (
        <footer className="border-t border-line bg-surface px-4 pb-[calc(14px+var(--safe-area-inset-bottom,0px))] pt-3.5">
          {submitClaim.isError || cancelClaim.isError ? (
            <p className="mb-2.5 text-center text-[12.5px] text-loss">{t('me.error')}</p>
          ) : null}
          {d.status === 'DRAFT' ? (
            <Button size="xl" className="w-full" disabled={submitClaim.isPending} onClick={handleSubmit}>
              {t('staff.claimDetail.submit')}
            </Button>
          ) : null}
          {d.status === 'DRAFT' ? (
            <Button variant="outline" size="xl" className="mt-2 w-full" onClick={() => setShowEdit(true)}>
              {t('staff.claimDetail.edit')}
            </Button>
          ) : null}
          {d.status === 'DRAFT' || d.status === 'SUBMITTED' ? (
            <Button
              variant="outline"
              size="xl"
              className={d.status === 'DRAFT' ? 'mt-2 w-full' : 'w-full'}
              disabled={cancelClaim.isPending}
              onClick={handleCancel}
            >
              {t('staff.claimDetail.cancel')}
            </Button>
          ) : (
            <Button
              variant="outline"
              size="xl"
              className="w-full"
              onClick={() => navigate('/me/expenses')}
            >
              {t('staff.claimDetail.close')}
            </Button>
          )}
        </footer>
      ) : null}

      {showEdit && d ? (
        <NewClaimDialog
          companyId={companyId}
          actor={actor}
          currency={d.currency}
          claim={d}
          onClose={() => setShowEdit(false)}
        />
      ) : null}
    </div>
  )
}

/** One label/value pair in the 2-col detail card. */
function DetailField({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-[11.5px] font-medium text-ink-3">{label}</div>
      <div className="mt-0.5 truncate text-[14px] font-medium text-ink">{value}</div>
    </div>
  )
}
