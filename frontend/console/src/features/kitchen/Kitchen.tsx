/**
 * Kitchen Display System (KDS) — a live board of open bills (dine-in tickets)
 * that the kitchen uses to track what to cook.
 *
 * Data: reuses the existing bills hooks (useBills + useBill per ticket).
 * Prices are intentionally NOT shown — a KDS is about food, not money.
 *
 * Bump (Ready) logic is local-only because the backend has no kitchen-status
 * endpoint yet.  A bumped ticket is hidden from the active board.
 * Re-appear rule: if a bill's lineCount later exceeds the value stored when
 * it was bumped (new items were added), the ticket un-bumps automatically so
 * the kitchen sees the new round.
 *
 * No hardcoded strings — all user-facing copy uses the kitchen.* i18n block.
 */

import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeft,
  ChefHat,
  CheckCircle2,
  Moon,
  Sun,
  Utensils,
  RefreshCw,
} from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { useSession, type CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { apiFetch } from '@/lib/api'
import { Spinner } from '@/components/ui/Spinner'
import { useBill, type BillSummaryResponse } from '@/features/pos/billsApi'
import { useTables, type TableResponse } from '@/features/pos/api'
import { useBumpStore } from './bumpStore'

// ---------------------------------------------------------------------------
// KDS-scoped bills query — same cache key as useBills so the POS and KDS
// share data, but with a refetchInterval so the board stays live.
// ---------------------------------------------------------------------------

function useBillsLive(session: CompanySession) {
  return useQuery({
    queryKey: ['bills', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<BillSummaryResponse[]>('/api/v1/bills', {
        tenant: { companyId: session.companyId, actor: session.actor },
        query: { businessId: session.businessId, status: 'OPEN' },
      }),
    staleTime: 10_000,
    refetchInterval: 8_000,
  })
}

// ---------------------------------------------------------------------------
// Entry guard
// ---------------------------------------------------------------------------

export function Kitchen() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  return <KitchenInner session={company} />
}

// ---------------------------------------------------------------------------
// Inner — session is guaranteed non-null
// ---------------------------------------------------------------------------

function KitchenInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const { theme, toggle } = useTheme()
  const locale = localeOf(i18n.language)

  // Auto-refreshes every 8 s so new tables/rounds appear without a manual reload.
  const billsQuery = useBillsLive(session)
  const tablesQuery = useTables(session)

  const tables = tablesQuery.data ?? []
  const openBills = billsQuery.data ?? []

  // Derive bumped state from the local store.
  const { bumpedMap, bump, isBumped } = useBumpStore()

  // Active tickets: open bills that have not been locally bumped (or have new items since bump).
  const activeTickets = openBills.filter((b) => !isBumped(b.id, b.lineCount))

  // Last-refreshed timestamp string — cosmetic "Live" indicator.
  const updatedAt = billsQuery.dataUpdatedAt
  const updatedStr = updatedAt
    ? new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(
        new Date(updatedAt),
      )
    : null

  return (
    <div className="flex min-h-[100dvh] flex-col bg-paper">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="sticky top-0 z-10 border-b border-line bg-surface/95 backdrop-blur-sm">
        <div className="flex items-center gap-x-3 gap-y-2 px-4 py-3 sm:px-6">
          {/* Back to POS */}
          <Link
            to="/pos"
            aria-label={t('kitchen.backToPos')}
            title={t('kitchen.backToPos')}
            className="grid size-9 shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <ArrowLeft className="size-4" />
          </Link>

          {/* Identity */}
          <div className="flex min-w-0 flex-1 items-center gap-2.5">
            <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-sm">
              <ChefHat className="size-[18px]" />
            </span>
            <div className="min-w-0">
              <div className="truncate font-display text-[17px] font-bold leading-tight text-ink">
                {t('kitchen.title')}
              </div>
              <div className="flex items-center gap-1.5 text-[11px] leading-tight text-ink-3">
                {/* Pulsing live dot — skipped when prefers-reduced-motion */}
                <span className="relative flex size-2 shrink-0" aria-hidden="true">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-400 opacity-75 motion-reduce:hidden" />
                  <span className="relative inline-flex size-2 rounded-full bg-brand-500" />
                </span>
                {t('kitchen.live')}
                {updatedStr ? (
                  <span className="ml-1 text-ink-3/70">
                    {t('kitchen.updatedAt', { time: updatedStr })}
                  </span>
                ) : null}
              </div>
            </div>
          </div>

          {/* Active ticket count badge */}
          {activeTickets.length > 0 ? (
            <div className="flex items-center gap-1.5 rounded-full bg-brand-500 px-3 py-1 text-xs font-bold text-white shadow-sm">
              <ChefHat className="size-3.5" aria-hidden="true" />
              {t('kitchen.activeCount', { count: activeTickets.length })}
            </div>
          ) : null}

          {/* Refresh indicator */}
          {billsQuery.isFetching ? (
            <span className="text-ink-3" aria-label={t('kitchen.refreshing')} title={t('kitchen.refreshing')}>
              <RefreshCw className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
            </span>
          ) : null}

          {/* Theme toggle */}
          <button
            type="button"
            onClick={toggle}
            aria-label={t('a11y.toggleTheme')}
            title={t('a11y.toggleTheme')}
            className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
          </button>
        </div>
      </header>

      {/* ── Board ──────────────────────────────────────────────────────────── */}
      <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8">
        {billsQuery.isLoading ? (
          <div className="flex items-center justify-center py-24 text-brand-500">
            <Spinner />
          </div>
        ) : billsQuery.isError ? (
          <div className="mx-auto mt-16 max-w-sm rounded-2xl border border-loss/30 bg-loss/10 px-6 py-8 text-center">
            <p className="font-display text-base font-semibold text-loss" role="alert">
              {t('kitchen.loadError')}
            </p>
          </div>
        ) : activeTickets.length === 0 ? (
          <AllCaughtUp />
        ) : (
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {activeTickets.map((bill) => (
              <TicketCard
                key={bill.id}
                bill={bill}
                tables={tables}
                session={session}
                bumpedMap={bumpedMap}
                onBump={() => bump(bill.id, bill.lineCount)}
              />
            ))}
          </div>
        )}
      </main>
    </div>
  )
}

// ---------------------------------------------------------------------------
// TicketCard — a single kitchen ticket
// ---------------------------------------------------------------------------

interface TicketCardProps {
  bill: BillSummaryResponse
  tables: TableResponse[]
  session: CompanySession
  bumpedMap: Record<string, number>
  onBump: () => void
}

function TicketCard({ bill, tables, session, onBump }: TicketCardProps) {
  const { t } = useTranslation()

  // Resolve table label: prefer the tables list, fall back to guestLabel.
  const tableLabel = bill.tableId
    ? (tables.find((tbl) => tbl.tableId === bill.tableId)?.label ?? bill.guestLabel)
    : bill.guestLabel

  // Fetch full bill detail to get line items.
  const detailQuery = useBill(session, bill.id)
  const lines = detailQuery.data?.lines ?? []
  const unpaidLines = lines.filter((l) => !l.paid)
  const itemCount = unpaidLines.reduce((s, l) => s + l.qty, 0)

  return (
    <article
      className={cn(
        'flex flex-col rounded-2xl border-2 border-line bg-surface shadow-md',
        'transition-shadow duration-200 hover:shadow-lg',
        'focus-within:ring-2 focus-within:ring-brand-500/40',
      )}
      aria-label={t('kitchen.ticketAriaLabel', { label: tableLabel })}
    >
      {/* ── Ticket header ── */}
      <div className="flex items-center justify-between border-b border-line px-5 py-4">
        <div className="min-w-0">
          <p className="truncate font-display text-2xl font-bold leading-tight text-ink">
            {tableLabel}
          </p>
          {itemCount > 0 ? (
            <p className="mt-0.5 text-sm font-medium text-ink-3">
              {t('kitchen.itemCount', { count: itemCount })}
            </p>
          ) : null}
        </div>
        {detailQuery.isLoading ? (
          <span className="text-ink-3" aria-hidden="true">
            <Spinner />
          </span>
        ) : null}
      </div>

      {/* ── Item lines ── */}
      <div className="flex-1 space-y-4 px-5 py-4">
        {detailQuery.isError ? (
          <p className="text-sm text-loss" role="alert">
            {t('kitchen.lineLoadError')}
          </p>
        ) : unpaidLines.length === 0 && !detailQuery.isLoading ? (
          <p className="text-sm text-ink-3">{t('kitchen.noItems')}</p>
        ) : (
          unpaidLines.map((line) => (
            <div key={line.id}>
              {/* qty × name — large, readable at distance */}
              <p className="font-display text-lg font-bold leading-snug text-ink">
                <span className="mr-2 font-mono text-brand-600">{line.qty}&times;</span>
                {line.nameSnapshot}
              </p>
              {/* Modifier list — muted, one per line */}
              {line.modifiers.length > 0 ? (
                <ul className="mt-1 space-y-0.5 pl-7" aria-label={t('kitchen.modifiersAriaLabel', { name: line.nameSnapshot })}>
                  {line.modifiers.map((mod) => (
                    <li key={mod.optionId} className="text-sm text-ink-3">
                      + {mod.nameSnapshot}
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          ))
        )}
      </div>

      {/* ── Ready button ── */}
      <div className="border-t border-line px-5 py-4">
        <button
          type="button"
          onClick={onBump}
          className={cn(
            'flex w-full items-center justify-center gap-2 rounded-xl py-3 text-base font-bold',
            'bg-brand-500 text-white shadow-sm',
            'transition-all hover:bg-brand-600 active:scale-[0.98]',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700',
            'motion-reduce:transition-none',
          )}
          aria-label={t('kitchen.readyAriaLabel', { label: tableLabel })}
        >
          <CheckCircle2 className="size-5" aria-hidden="true" />
          {t('kitchen.ready')}
        </button>
      </div>
    </article>
  )
}

// ---------------------------------------------------------------------------
// AllCaughtUp — empty state
// ---------------------------------------------------------------------------

function AllCaughtUp() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center px-6 py-28 text-center">
      <div className="mb-5 grid size-20 place-items-center rounded-3xl bg-brand-50 text-brand-500">
        <ChefHat className="size-9" aria-hidden="true" />
      </div>
      <h2 className="font-display text-2xl font-bold text-ink">{t('kitchen.emptyTitle')}</h2>
      <p className="mx-auto mt-2 max-w-xs text-base text-ink-3">{t('kitchen.emptyHint')}</p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// NoCompany — shown when the session has no company (mirrors Pos pattern)
// ---------------------------------------------------------------------------

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <div className="w-full max-w-md rounded-card border border-line bg-surface p-10 text-center shadow-sm">
        <div className="mx-auto mb-4 grid size-14 place-items-center rounded-2xl bg-brand-50 text-brand-500">
          <Utensils className="size-6" aria-hidden="true" />
        </div>
        <h2 className="font-display text-xl font-bold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('kitchen.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-6 inline-block rounded-xl bg-brand-500 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-600"
        >
          {t('nav.onboarding')}
        </Link>
      </div>
    </div>
  )
}
