/**
 * BillsTray — slide-in panel listing OPEN bills for the current business.
 *
 * Bills are grouped by table (+ a "no table" group for takeaway/counter bills).
 * Each entry shows guest label, table, item count, and running total.
 *
 * "+ Open bill" opens a dialog to pick a table + enter a guest label.
 *
 * Money rule (rule 8): amounts via formatMoney().
 * Strings rule (rule 9): all labels are i18n keys — no hardcoded text.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X, ReceiptText, Table2, Plus, User } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import type { TableResponse } from './api'
import { useBills, useOpenBill, type BillSummaryResponse } from './billsApi'

interface Props {
  session: CompanySession
  locale: string
  tables: TableResponse[]
  onOpenBill: (billId: string) => void
  onClose: () => void
}

export function BillsTray({ session, locale, tables, onOpenBill, onClose }: Props) {
  const { t } = useTranslation()
  const billsQuery = useBills(session)
  const bills = billsQuery.data ?? []

  const [showOpenDialog, setShowOpenDialog] = useState(false)

  // Group bills by tableId — null tableId goes into "no table". Pass the tables so each group
  // header can show the real table label instead of falling back to "No table".
  const byTable = groupByTable(bills, tables)

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-black/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.trayTitle')}
    >
      <div className="reveal flex h-full w-full max-w-sm flex-col bg-surface shadow-lg">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div className="flex items-center gap-2">
            <h2 className="font-display text-lg font-semibold text-ink">{t('bills.trayTitle')}</h2>
            {bills.length > 0 ? (
              <Badge tone="emerald">{bills.length}</Badge>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Open bill button */}
        <div className="border-b border-line px-5 py-3">
          <Button
            className="w-full"
            onClick={() => setShowOpenDialog(true)}
          >
            <Plus className="size-4" aria-hidden="true" />
            {t('bills.openBill')}
          </Button>
        </div>

        {/* Bills list */}
        <div className="flex-1 overflow-y-auto overscroll-contain p-5">
          {billsQuery.isLoading ? (
            <div className="grid place-items-center py-16 text-brand-500">
              <Spinner />
            </div>
          ) : bills.length === 0 ? (
            <div className="py-10 text-center">
              <ReceiptText className="mx-auto mb-3 size-8 text-ink-3/40" />
              <p className="text-sm text-ink-3">{t('bills.noBills')}</p>
            </div>
          ) : (
            <div className="space-y-4">
              {byTable.map(({ tableId, tableLabel, entries }) => (
                <div key={tableId ?? '__no_table__'}>
                  {/* Group header */}
                  <div className="mb-2 flex items-center gap-1.5">
                    {tableId ? (
                      <Table2 className="size-3.5 text-ink-3" aria-hidden="true" />
                    ) : (
                      <User className="size-3.5 text-ink-3" aria-hidden="true" />
                    )}
                    <span className="text-[11px] font-bold uppercase tracking-[0.05em] text-ink-3">
                      {tableLabel ?? t('bills.noTable')}
                    </span>
                  </div>
                  <ul className="space-y-2">
                    {entries.map((bill) => (
                      <BillEntry
                        key={bill.id}
                        bill={bill}
                        locale={locale}
                        onOpen={onOpenBill}
                      />
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Footer note */}
        <div className="border-t border-line px-5 py-3">
          <p className="text-xs text-ink-3">{t('bills.unpaidNote')}</p>
        </div>
      </div>

      {/* Open bill dialog */}
      {showOpenDialog ? (
        <OpenBillDialog
          session={session}
          tables={tables}
          onCreated={(billId) => {
            setShowOpenDialog(false)
            onOpenBill(billId)
          }}
          onClose={() => setShowOpenDialog(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// BillEntry
// ---------------------------------------------------------------------------

function BillEntry({
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
    <li>
      <button
        type="button"
        onClick={() => onOpen(bill.id)}
        className={cn(
          'w-full rounded-2xl border border-line bg-surface px-4 py-3 text-left transition-all',
          'hover:border-brand-300 hover:shadow-md',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        )}
      >
        <div className="flex items-center gap-2">
          <span className="min-w-0 flex-1 truncate font-medium text-ink">{bill.guestLabel}</span>
          <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
            {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between text-xs text-ink-3">
          <span>{t('bills.lineCount', { n: bill.lineCount })}</span>
          <span className="text-xs font-semibold text-brand-700">{t('bills.open')}</span>
        </div>
      </button>
    </li>
  )
}

// ---------------------------------------------------------------------------
// OpenBillDialog
// ---------------------------------------------------------------------------

function OpenBillDialog({
  session,
  tables,
  onCreated,
  onClose,
}: {
  session: CompanySession
  tables: TableResponse[]
  onCreated: (billId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const openBill = useOpenBill(session)
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null)
  const [guestLabel, setGuestLabel] = useState('')
  const [touched, setTouched] = useState(false)

  const guestLabelError = touched && guestLabel.trim() === '' ? t('bills.guestLabelRequired') : null
  const canSubmit = guestLabel.trim().length > 0 && !openBill.isPending

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (!canSubmit) return
    openBill.mutate(
      { tableId: selectedTableId, guestLabel: guestLabel.trim() },
      {
        onSuccess: (res) => {
          if (res?.id) {
            onCreated(res.id)
          }
        },
      },
    )
  }

  const activeTables = tables.filter((tbl) => tbl.active)

  return (
    /* Nested overlay — sits above the tray */
    <div
      className="fixed inset-0 z-[60] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.openBillTitle')}
    >
      <div className="w-full max-w-sm rounded-2xl border border-line bg-surface shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h3 className="font-display text-lg font-semibold text-ink">{t('bills.openBillTitle')}</h3>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} noValidate>
          <div className="space-y-5 px-5 py-4">
            {/* Guest label */}
            <div>
              <label htmlFor="bill-guest-label" className="mb-1.5 block text-sm font-medium text-ink">
                {t('bills.guestLabel')}
              </label>
              <input
                id="bill-guest-label"
                type="text"
                autoFocus
                maxLength={128}
                value={guestLabel}
                onChange={(e) => setGuestLabel(e.target.value)}
                onBlur={() => setTouched(true)}
                placeholder={t('bills.guestLabelPlaceholder')}
                aria-describedby={guestLabelError ? 'bill-guest-error' : undefined}
                className={cn(
                  'w-full rounded-xl border bg-surface px-3 py-2 text-sm text-ink',
                  'transition-colors placeholder:text-ink-3/50',
                  'focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/12',
                  guestLabelError ? 'border-loss' : 'border-line',
                )}
              />
              {guestLabelError ? (
                <p id="bill-guest-error" className="mt-1 text-xs text-loss" role="alert">
                  {guestLabelError}
                </p>
              ) : null}
            </div>

            {/* Table picker (optional) */}
            <div>
              <p className="mb-2 text-sm font-medium text-ink">{t('bills.selectTable')}</p>
              <div className="flex flex-wrap gap-2">
                {/* No table option */}
                <button
                  type="button"
                  onClick={() => setSelectedTableId(null)}
                  aria-pressed={selectedTableId === null}
                  className={cn(
                    'w-[68px] rounded-[13px] border-[1.5px] py-2 text-center transition-colors',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                    selectedTableId === null
                      ? 'border-brand-500 bg-brand-500'
                      : 'border-line bg-surface hover:bg-hover',
                  )}
                >
                  <div className={cn('text-sm font-bold', selectedTableId === null ? 'text-white' : 'text-ink')}>
                    —
                  </div>
                  <div className={cn('text-[10px]', selectedTableId === null ? 'text-white/80' : 'text-ink-3')}>
                    {t('bills.noTable')}
                  </div>
                </button>

                {activeTables.map((tbl) => {
                  const selected = selectedTableId === tbl.tableId
                  return (
                    <button
                      key={tbl.tableId}
                      type="button"
                      onClick={() => setSelectedTableId(tbl.tableId)}
                      aria-pressed={selected}
                      aria-label={t('pos.table.selectLabel', { label: tbl.label })}
                      className={cn(
                        'w-[68px] rounded-[13px] border-[1.5px] py-2 text-center transition-colors',
                        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                        selected
                          ? 'border-brand-500 bg-brand-500'
                          : 'border-line bg-surface hover:bg-hover',
                      )}
                    >
                      <div className={cn('text-sm font-bold', selected ? 'text-white' : 'text-ink')}>
                        {tbl.label}
                      </div>
                      <div className={cn('text-[10px]', selected ? 'text-white/80' : 'text-ink-3')}>
                        {t('pos.table.capacity', { n: tbl.capacity })}
                      </div>
                    </button>
                  )
                })}
              </div>
            </div>

            {openBill.isError ? (
              <p className="text-xs text-loss" role="alert">
                {(openBill.error as Error).message}
              </p>
            ) : null}
          </div>

          <div className="flex gap-2 border-t border-line px-5 py-4">
            <Button type="button" variant="outline" className="flex-1" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" className="flex-1" disabled={!canSubmit}>
              {openBill.isPending ? <Spinner /> : t('bills.startBill')}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

interface TableGroup {
  tableId: string | null
  tableLabel: string | null
  entries: BillSummaryResponse[]
}

function groupByTable(bills: BillSummaryResponse[], tables: TableResponse[]): TableGroup[] {
  const labelById = new Map(tables.map((tbl) => [tbl.tableId, tbl.label]))
  const map = new Map<string, TableGroup>()

  for (const bill of bills) {
    const key = bill.tableId ?? '__no_table__'
    if (!map.has(key)) {
      map.set(key, {
        tableId: bill.tableId,
        tableLabel: bill.tableId ? (labelById.get(bill.tableId) ?? null) : null,
        entries: [],
      })
    }
    map.get(key)!.entries.push(bill)
  }

  // Sort: tables first (alphabetically by tableId), then no-table group last
  return [...map.values()].sort((a, b) => {
    if (a.tableId === null) return 1
    if (b.tableId === null) return -1
    return a.tableId.localeCompare(b.tableId)
  })
}
