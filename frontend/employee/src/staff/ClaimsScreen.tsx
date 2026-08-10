/**
 * Klaim saya (/me/expenses tab, ADR 0049 P5 redesign) — the caller's own expense claims: two
 * headline stat tiles (pending, reimbursed this year), the paginated claim list, and a floating
 * "new claim" action. Reuses the console's employee-facing expense hooks unchanged
 * (features/expenses/api + parts + format) — see Beranda's own doc comment for the pattern this
 * mirrors.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight, Plus, ReceiptText, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { ClaimStatusBadge } from '@/features/expenses/parts'
import { formatDate } from '@/features/expenses/format'
import { useMyClaims, type MyExpenseClaim } from '@/features/expenses/api'
import { NewClaimDialog } from '@/features/expenses/NewClaimDialog'
import { isNotLinked } from '@/features/me/api'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { StaffHeader, useTenant } from './ui'

const PAGE_SIZE = 10

export function ClaimsScreen() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const { companyId, actor } = useTenant()
  const year = new Date().getFullYear()

  const [page, setPage] = useState(0)
  const [showNew, setShowNew] = useState(false)

  // The pending/reimbursed tiles have no dedicated employee-facing aggregate endpoint (unlike the
  // manager's useOrgUnitExpenseSummary) — this reads a generously sized first page as a best-effort
  // window, the same first-page approximation Beranda's own draftCount already relies on.
  const stats = useMyClaims({ companyId, actor, page: 0, size: 100, enabled: true })
  const list = useMyClaims({ companyId, actor, page, size: PAGE_SIZE, enabled: true })
  const notLinked = isNotLinked(stats.error)

  const statClaims = stats.data?.content ?? []
  const pendingMinor = statClaims
    .filter((c) => c.status === 'DRAFT' || c.status === 'SUBMITTED')
    .reduce((sum, c) => sum + c.amountMinor, 0)
  const reimbursedMinor = statClaims
    .filter((c) => c.status === 'REIMBURSED' && c.expenseDate.startsWith(String(year)))
    .reduce((sum, c) => sum + c.amountMinor, 0)
  const currency = statClaims[0]?.currency ?? 'IDR'

  const list0 = list.data

  return (
    <>
      <StaffHeader title={t('staff.claims.title')} />

      <div className="pb-28">
        {stats.isLoading ? (
          <div className="flex flex-col gap-3 p-4">
            <StatCardsSkeleton cards={2} />
            <ListSkeleton rows={5} avatar />
          </div>
        ) : notLinked ? (
          <div className="p-4 pt-6">
            <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
          </div>
        ) : stats.isError ? (
          <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('me.error')}
          </Card>
        ) : (
          <>
            <section className="grid grid-cols-2 gap-2.5 px-4 pt-4">
              <Card className="p-4">
                <div className="text-[11.5px] font-medium text-ink-3">{t('staff.claims.pending')}</div>
                <div className="tnum mt-1.5 font-mono text-[17px] font-bold leading-tight text-ink">
                  {formatMoney(pendingMinor, currency, locale)}
                </div>
              </Card>
              <Card className="p-4">
                <div className="text-[11.5px] font-medium text-ink-3">
                  {t('staff.claims.reimbursedYtd')}
                </div>
                <div className="tnum mt-1.5 font-mono text-[17px] font-bold leading-tight text-ink">
                  {formatMoney(reimbursedMinor, currency, locale)}
                </div>
              </Card>
            </section>

            <section className="px-4 pt-3.5">
              {list.isLoading ? (
                <ListSkeleton rows={5} avatar />
              ) : list.isError ? (
                <Card className="p-8 text-center text-sm text-loss">
                  <TriangleAlert className="mx-auto mb-2 size-5" />
                  {t('me.error')}
                </Card>
              ) : !list0 || list0.content.length === 0 ? (
                <EmptyState title={t('staff.claims.empty')} hint={t('staff.claims.emptyHint')} />
              ) : (
                <>
                  <div className="flex flex-col gap-2.5">
                    {list0.content.map((c) => (
                      <ClaimRow key={c.id} claim={c} locale={locale} />
                    ))}
                  </div>
                  {list0.totalPages > 1 ? (
                    <div className="mt-3.5 flex items-center justify-center gap-3">
                      <button
                        type="button"
                        onClick={() => setPage((p) => p - 1)}
                        disabled={page <= 0}
                        aria-label={t('staff.claims.prevPage')}
                        className="grid size-11 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-brand-500"
                      >
                        <ChevronLeft className="size-[18px]" aria-hidden />
                      </button>
                      <span className="tnum text-[13px] text-ink-3">
                        {t('staff.claims.page', { page: page + 1, total: list0.totalPages })}
                      </span>
                      <button
                        type="button"
                        onClick={() => setPage((p) => p + 1)}
                        disabled={page >= list0.totalPages - 1}
                        aria-label={t('staff.claims.nextPage')}
                        className="grid size-11 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-brand-500"
                      >
                        <ChevronRight className="size-[18px]" aria-hidden />
                      </button>
                    </div>
                  ) : null}
                </>
              )}
            </section>
          </>
        )}
      </div>

      {!notLinked ? (
        <button
          type="button"
          onClick={() => setShowNew(true)}
          className="fixed right-4 z-40 flex items-center gap-2 rounded-full bg-brand-600 px-5 py-3.5 text-[14px] font-bold text-white shadow-lg transition-transform active:scale-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          style={{ bottom: 'calc(82px + env(safe-area-inset-bottom))' }}
        >
          <Plus className="size-[18px]" aria-hidden />
          {t('staff.claims.newClaim')}
        </button>
      ) : null}

      {showNew ? (
        <NewClaimDialog
          companyId={companyId}
          actor={actor}
          currency={currency}
          claim={null}
          onClose={() => setShowNew(false)}
        />
      ) : null}
    </>
  )
}

/** One claim row — icon tile, category + status, date/merchant meta, amount; links into the detail
 *  screen. Mirrors Beranda's TodoCard row idiom. */
function ClaimRow({ claim, locale }: { claim: MyExpenseClaim; locale: string }) {
  return (
    <Link
      to={`/me/expenses/${claim.id}`}
      viewTransition
      className="flex items-center gap-3 rounded-[18px] border border-line bg-surface p-3.5 shadow-sm transition-colors hover:border-brand-100 hover:bg-brand-50/40 active:scale-[0.99]"
    >
      <span className="grid size-[38px] shrink-0 place-items-center rounded-[13px] bg-brand-50 text-brand-700">
        <ReceiptText className="size-[18px]" strokeWidth={1.9} aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="truncate text-[14px] font-bold text-ink">{claim.categoryName}</span>
          <ClaimStatusBadge status={claim.status} />
        </span>
        <span className="mt-0.5 block truncate text-[12.5px] text-ink-3">
          {formatDate(claim.expenseDate, locale)}
          {claim.merchant ? ` · ${claim.merchant}` : ''}
        </span>
      </span>
      <span className="tnum shrink-0 font-mono text-[14px] font-bold text-ink">
        {formatMoney(claim.amountMinor, claim.currency, locale)}
      </span>
    </Link>
  )
}
