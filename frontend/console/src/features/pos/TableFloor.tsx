/**
 * TableFloor — table-first entry point for the POS bills flow.
 *
 * Shows the restaurant floor as a grid of table cards. Occupancy is derived
 * from the live open-bills list (a table is occupied iff some open bill has
 * bill.tableId === table.tableId — the table's own `occupied` flag from the
 * API is NOT used for this purpose).
 *
 * Tap a free table   → POST /api/v1/bills (guestLabel = table.label) → enter bill mode.
 * Tap occupied table → find its open bill → enter bill mode.
 *
 * A separate "Takeaway / Counter" section lists no-table open bills and offers
 * a "+ New takeaway" action (guestLabel defaults to "Walk-in <n>").
 *
 * Money rule (rule 8): amounts via formatMoney().
 * Strings rule (rule 9): all labels are i18n keys — no hardcoded text.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  X,
  Table2,
  Plus,
  Settings,
  Users,
} from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import type { TableResponse } from './api'
import { useBills, useOpenBill, type BillSummaryResponse } from './billsApi'
import { TableManagement } from './TableManagement'

interface Props {
  session: CompanySession
  locale: string
  tables: TableResponse[]
  onOpenBill: (billId: string) => void
  onClose: () => void
}

export function TableFloor({ session, locale, tables, onOpenBill, onClose }: Props) {
  const { t } = useTranslation()
  const billsQuery = useBills(session)
  const openBill = useOpenBill(session)
  const bills = billsQuery.data ?? []

  const [showTableMgmt, setShowTableMgmt] = useState(false)
  const [openingTableId, setOpeningTableId] = useState<string | null>(null)
  const [openingTakeaway, setOpeningTakeaway] = useState(false)

  // Derive occupancy from the live bills list — ignore the API's occupied flag.
  const occupiedTableIds = new Set(
    bills.filter((b) => b.tableId != null).map((b) => b.tableId as string),
  )

  // Active tables only (inactive are managed in TableManagement, not shown on floor).
  const activeTables = tables.filter((tbl) => tbl.active)

  // Takeaway bills: bills with no tableId.
  const takeawayBills = bills.filter((b) => b.tableId == null)

  // Next walk-in suffix: count existing takeaway bills for a simple auto-suffix.
  const nextWalkinSuffix = takeawayBills.length + 1

  function handleFreeTap(table: TableResponse) {
    if (openBill.isPending) return
    setOpeningTableId(table.tableId)
    openBill.mutate(
      { tableId: table.tableId, guestLabel: table.label },
      {
        onSuccess: (res) => {
          setOpeningTableId(null)
          if (res?.id) {
            onOpenBill(res.id)
          }
        },
        onError: () => {
          setOpeningTableId(null)
        },
      },
    )
  }

  function handleOccupiedTap(table: TableResponse) {
    // Find the most recent open bill for this table.
    const tableBills = bills.filter((b) => b.tableId === table.tableId)
    if (tableBills.length === 0) return
    // Use last in list (server returns in creation order; last = most recent).
    const target = tableBills[tableBills.length - 1]
    onOpenBill(target.id)
  }

  function handleNewTakeaway() {
    if (openBill.isPending) return
    setOpeningTakeaway(true)
    const label = t('bills.walkinLabel', { n: nextWalkinSuffix })
    openBill.mutate(
      { tableId: null, guestLabel: label },
      {
        onSuccess: (res) => {
          setOpeningTakeaway(false)
          if (res?.id) {
            onOpenBill(res.id)
          }
        },
        onError: () => {
          setOpeningTakeaway(false)
        },
      },
    )
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-black/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.floorTitle')}
    >
      {/* Backdrop click closes the panel */}
      <div className="absolute inset-0" aria-hidden="true" onClick={onClose} />

      <div className="reveal relative flex h-full w-full max-w-lg flex-col bg-surface shadow-lg">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div className="flex items-center gap-2.5">
            <Table2 className="size-5 text-brand-500" aria-hidden="true" />
            <h2 className="font-display text-lg font-semibold text-ink">{t('bills.floorTitle')}</h2>
            {occupiedTableIds.size > 0 ? (
              <Badge tone="emerald">{occupiedTableIds.size}</Badge>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setShowTableMgmt(true)}
              aria-label={t('pos.table.management')}
              title={t('pos.table.management')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <Settings className="size-4" aria-hidden="true" />
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('common.close')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <X className="size-4" />
            </button>
          </div>
        </div>

        {/* Scrollable body */}
        <div className="flex-1 overflow-y-auto overscroll-contain px-5 py-5 space-y-6">
          {/* Error from opening a bill */}
          {openBill.isError ? (
            <p className="rounded-xl border border-loss/30 bg-loss/10 px-4 py-3 text-sm text-loss" role="alert">
              {(openBill.error as Error).message}
            </p>
          ) : null}

          {/* ── Dine-in floor grid ─────────────────────────────────────────── */}
          <section aria-label={t('bills.dineInSection')}>
            <p className="mb-3 text-[11px] font-bold uppercase tracking-[0.05em] text-ink-3">
              {t('bills.dineInSection')}
            </p>

            {billsQuery.isLoading ? (
              <div className="grid place-items-center py-12 text-brand-500">
                <Spinner />
              </div>
            ) : activeTables.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-line px-5 py-8 text-center">
                <Table2 className="mx-auto mb-2 size-8 text-ink-3/40" aria-hidden="true" />
                <p className="text-sm text-ink-3">{t('pos.table.noTables')}</p>
                <button
                  type="button"
                  onClick={() => setShowTableMgmt(true)}
                  className="mt-2 text-xs text-brand-600 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
                >
                  {t('pos.table.addTable')}
                </button>
              </div>
            ) : (
              <div
                className="grid gap-3"
                style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(110px, 1fr))' }}
                role="group"
                aria-label={t('bills.tableGrid')}
              >
                {activeTables.map((tbl) => {
                  const occupied = occupiedTableIds.has(tbl.tableId)
                  const tableBills = bills.filter((b) => b.tableId === tbl.tableId)
                  const runningTotal = tableBills.reduce((s, b) => s + b.runningTotalMinor, 0)
                  const itemCount = tableBills.reduce((s, b) => s + b.lineCount, 0)
                  const currency =
                    tableBills[0]?.currency ?? session.baseCurrency
                  const isOpening = openingTableId === tbl.tableId

                  return (
                    <TableCard
                      key={tbl.tableId}
                      table={tbl}
                      occupied={occupied}
                      runningTotalMinor={runningTotal}
                      itemCount={itemCount}
                      currency={currency}
                      locale={locale}
                      isOpening={isOpening}
                      onTap={() => {
                        if (occupied) {
                          handleOccupiedTap(tbl)
                        } else {
                          handleFreeTap(tbl)
                        }
                      }}
                    />
                  )
                })}
              </div>
            )}
          </section>

          {/* ── Takeaway / Counter section ────────────────────────────────── */}
          <section aria-label={t('bills.takeawaySection')}>
            <div className="mb-3 flex items-center justify-between">
              <p className="text-[11px] font-bold uppercase tracking-[0.05em] text-ink-3">
                {t('bills.takeawaySection')}
              </p>
              {takeawayBills.length > 0 ? (
                <Badge tone="neutral" className="text-[10px]">
                  {takeawayBills.length}
                </Badge>
              ) : null}
            </div>

            <div className="space-y-2">
              {/* Existing takeaway / no-table bills */}
              {takeawayBills.map((bill) => (
                <TakeawayBillRow
                  key={bill.id}
                  bill={bill}
                  locale={locale}
                  onOpen={onOpenBill}
                />
              ))}

              {/* New takeaway action */}
              <button
                type="button"
                onClick={handleNewTakeaway}
                disabled={openBill.isPending}
                className={cn(
                  'flex w-full items-center gap-3 rounded-2xl border border-dashed border-line px-4 py-3 text-left transition-colors',
                  'hover:border-brand-400 hover:bg-brand-50 hover:text-brand-700',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                  openBill.isPending && 'cursor-not-allowed opacity-60',
                )}
                aria-label={t('bills.newTakeaway')}
              >
                {openingTakeaway ? (
                  <Spinner />
                ) : (
                  <Plus className="size-4 shrink-0 text-brand-500" aria-hidden="true" />
                )}
                <span className="text-sm font-medium text-ink-2">{t('bills.newTakeaway')}</span>
              </button>
            </div>
          </section>
        </div>

        {/* Footer note */}
        <div className="border-t border-line px-5 py-3">
          <p className="text-xs text-ink-3">{t('bills.unpaidNote')}</p>
        </div>
      </div>

      {/* Table management panel — nested above the floor panel */}
      {showTableMgmt ? (
        <TableManagement
          session={session}
          onClose={() => setShowTableMgmt(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// TableCard
// ---------------------------------------------------------------------------

interface TableCardProps {
  table: TableResponse
  occupied: boolean
  runningTotalMinor: number
  itemCount: number
  currency: string
  locale: string
  isOpening: boolean
  onTap: () => void
}

function TableCard({
  table,
  occupied,
  runningTotalMinor,
  itemCount,
  currency,
  locale,
  isOpening,
  onTap,
}: TableCardProps) {
  const { t } = useTranslation()

  return (
    <button
      type="button"
      onClick={onTap}
      disabled={isOpening}
      aria-label={
        occupied
          ? t('bills.occupiedTapLabel', { label: table.label })
          : t('bills.freeTapLabel', { label: table.label })
      }
      className={cn(
        'relative flex flex-col items-center rounded-2xl border-[1.5px] px-3 py-4 text-center transition-all',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        occupied
          ? [
              'border-emerald-500 bg-emerald-50 shadow-sm',
              'hover:border-emerald-600 hover:shadow-md',
              'dark:bg-emerald-950/30 dark:border-emerald-400',
            ]
          : [
              'border-line bg-surface',
              'hover:border-brand-400 hover:shadow-md hover:-translate-y-0.5',
            ],
        isOpening && 'opacity-60 cursor-not-allowed',
      )}
    >
      {isOpening ? (
        <span className="absolute inset-0 grid place-items-center rounded-2xl bg-surface/70">
          <Spinner />
        </span>
      ) : null}

      {/* Table icon */}
      <Table2
        className={cn('mb-1.5 size-5', occupied ? 'text-emerald-600 dark:text-emerald-400' : 'text-ink-3')}
        aria-hidden="true"
      />

      {/* Label */}
      <span
        className={cn(
          'font-display text-[17px] font-bold leading-tight',
          occupied ? 'text-emerald-700 dark:text-emerald-300' : 'text-ink',
        )}
      >
        {table.label}
      </span>

      {/* Capacity */}
      <span className={cn('mt-0.5 text-[10.5px]', occupied ? 'text-emerald-600/80' : 'text-ink-3')}>
        {t('pos.table.capacity', { n: table.capacity })}
      </span>

      {/* Occupied details */}
      {occupied ? (
        <div className="mt-2 w-full space-y-0.5">
          <div className="tnum rounded-lg bg-emerald-100 px-2 py-1 font-mono text-[11px] font-semibold text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200">
            {formatMoney(runningTotalMinor, currency, locale)}
          </div>
          <div className="text-[10px] text-emerald-600 dark:text-emerald-400">
            {t('bills.itemCount', { n: itemCount })}
          </div>
        </div>
      ) : null}
    </button>
  )
}

// ---------------------------------------------------------------------------
// TakeawayBillRow
// ---------------------------------------------------------------------------

function TakeawayBillRow({
  bill,
  locale,
  onOpen,
}: {
  bill: BillSummaryResponse
  locale: string
  onOpen: (billId: string) => void
}) {
  const { t } = useTranslation()

  return (
    <button
      type="button"
      onClick={() => onOpen(bill.id)}
      className={cn(
        'flex w-full items-center gap-3 rounded-2xl border border-line bg-surface px-4 py-3 text-left transition-all',
        'hover:border-brand-300 hover:shadow-md',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
      )}
      aria-label={t('bills.openExistingBill', { label: bill.guestLabel })}
    >
      <Users className="size-4 shrink-0 text-ink-3" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-ink">{bill.guestLabel}</div>
        <div className="text-xs text-ink-3">{t('bills.lineCount', { n: bill.lineCount })}</div>
      </div>
      <div className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
        {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
      </div>
      <span className="text-xs font-semibold text-brand-700">{t('bills.open')}</span>
    </button>
  )
}
