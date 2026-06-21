/**
 * TableManagement — owner/manager panel for creating and managing restaurant tables.
 *
 * Renders as a drawer-like panel (slide-in from the right) accessible from the POS
 * when orderType = DINE_IN. Provides:
 *   - Table list with occupancy badges and active/inactive status.
 *   - Create table form (label + capacity + optional area).
 *   - Activate / deactivate toggle per table.
 *
 * Money rule (rule 8): N/A here (no monetary amounts).
 * Strings rule (rule 9): all labels are i18n keys.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X, Plus } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import {
  useTables,
  useCreateTable,
  useActivateTable,
  useDeactivateTable,
} from './api'

interface Props {
  session: CompanySession
  onClose: () => void
}

export function TableManagement({ session, onClose }: Props) {
  const { t } = useTranslation()
  const tablesQuery = useTables(session)
  const tables = tablesQuery.data ?? []

  const [showCreate, setShowCreate] = useState(false)

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-ink/30 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.table.management')}
    >
      <div className="flex h-full w-full max-w-sm flex-col bg-surface shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('pos.table.management')}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-paper focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Table list */}
        <div className="flex-1 overflow-y-auto overscroll-contain p-5">
          {tablesQuery.isLoading ? (
            <div className="grid place-items-center py-16 text-emerald">
              <Spinner />
            </div>
          ) : tables.length === 0 ? (
            <p className="py-10 text-center text-sm text-ink-3">{t('pos.table.noTables')}</p>
          ) : (
            <ul className="space-y-2">
              {tables.map((tbl) => (
                <TableRow key={tbl.tableId} session={session} table={tbl} />
              ))}
            </ul>
          )}
        </div>

        {/* Create form / CTA */}
        <div className="border-t border-line p-5">
          {showCreate ? (
            <CreateTableForm
              session={session}
              onDone={() => setShowCreate(false)}
              onCancel={() => setShowCreate(false)}
            />
          ) : (
            <Button
              variant="outline"
              className="w-full"
              onClick={() => setShowCreate(true)}
            >
              <Plus className="size-4" />
              {t('pos.table.addTable')}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// TableRow
// ---------------------------------------------------------------------------

function TableRow({
  session,
  table,
}: {
  session: CompanySession
  table: import('./api').TableResponse
}) {
  const { t } = useTranslation()
  const activate = useActivateTable(session)
  const deactivate = useDeactivateTable(session)
  const isPending = activate.isPending || deactivate.isPending

  return (
    <li className="flex items-center gap-3 rounded-xl border border-line bg-surface px-4 py-3">
      {/* Label + area */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-medium text-ink">{table.label}</span>
          {table.occupied ? (
            <Badge tone="amber" className="text-[10px] px-1.5 py-0">
              {t('pos.table.occupied')}
            </Badge>
          ) : null}
          {!table.active ? (
            <Badge tone="neutral" className="text-[10px] px-1.5 py-0">
              {t('pos.table.inactive')}
            </Badge>
          ) : null}
        </div>
        <div className="mt-0.5 text-xs text-ink-3">
          {t('pos.table.capacity', { n: table.capacity })}
          {table.area ? ` · ${table.area}` : ''}
        </div>
      </div>

      {/* Activate / deactivate */}
      {isPending ? (
        <Spinner />
      ) : table.active ? (
        <button
          type="button"
          onClick={() => deactivate.mutate(table.tableId)}
          className={cn(
            'text-xs text-ink-3 hover:text-rose focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
            'rounded px-2 py-1',
          )}
        >
          {t('pos.table.deactivate')}
        </button>
      ) : (
        <button
          type="button"
          onClick={() => activate.mutate(table.tableId)}
          className={cn(
            'text-xs text-emerald-2 hover:text-emerald focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
            'rounded px-2 py-1',
          )}
        >
          {t('pos.table.activate')}
        </button>
      )}
    </li>
  )
}

// ---------------------------------------------------------------------------
// CreateTableForm
// ---------------------------------------------------------------------------

function CreateTableForm({
  session,
  onDone,
  onCancel,
}: {
  session: CompanySession
  onDone: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const create = useCreateTable(session)

  const [label, setLabel] = useState('')
  const [capacity, setCapacity] = useState('2')
  const [area, setArea] = useState('')
  const [error, setError] = useState<string | null>(null)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    const cap = parseInt(capacity, 10)
    if (!label.trim()) {
      setError(t('pos.table.labelRequired'))
      return
    }
    if (isNaN(cap) || cap < 1) {
      setError(t('pos.table.capacityInvalid'))
      return
    }
    create.mutate(
      { label: label.trim(), capacity: cap, area: area.trim() || undefined },
      {
        onSuccess: () => {
          onDone()
        },
        onError: (err) => {
          setError((err as Error).message)
        },
      },
    )
  }

  const inputCls = cn(
    'w-full rounded-lg border bg-surface px-3 py-2 text-sm text-ink',
    'transition-colors placeholder:text-ink-3/50',
    'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10',
    'border-line-strong',
  )

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <p className="text-sm font-medium text-ink">{t('pos.table.addTable')}</p>

      {/* Label */}
      <div>
        <label htmlFor="tbl-label" className="mb-1 block text-xs font-medium text-ink-3">
          {t('pos.table.labelField')}
        </label>
        <input
          id="tbl-label"
          type="text"
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          placeholder="T1"
          className={inputCls}
          maxLength={20}
          required
        />
      </div>

      {/* Capacity */}
      <div>
        <label htmlFor="tbl-capacity" className="mb-1 block text-xs font-medium text-ink-3">
          {t('pos.table.capacityField')}
        </label>
        <input
          id="tbl-capacity"
          type="number"
          inputMode="numeric"
          min="1"
          max="999"
          value={capacity}
          onChange={(e) => setCapacity(e.target.value)}
          className={inputCls}
          required
        />
      </div>

      {/* Area (optional) */}
      <div>
        <label htmlFor="tbl-area" className="mb-1 block text-xs font-medium text-ink-3">
          {t('pos.table.areaField')}
        </label>
        <input
          id="tbl-area"
          type="text"
          value={area}
          onChange={(e) => setArea(e.target.value)}
          placeholder={t('pos.table.areaPlaceholder')}
          className={inputCls}
          maxLength={50}
        />
      </div>

      {error ? (
        <p className="text-xs text-rose" role="alert">
          {error}
        </p>
      ) : null}

      <div className="flex gap-2">
        <Button
          variant="outline"
          type="button"
          onClick={onCancel}
          className="flex-1"
          disabled={create.isPending}
        >
          {t('common.cancel')}
        </Button>
        <Button type="submit" className="flex-1" disabled={create.isPending}>
          {create.isPending ? <Spinner /> : t('common.continue')}
        </Button>
      </div>
    </form>
  )
}
