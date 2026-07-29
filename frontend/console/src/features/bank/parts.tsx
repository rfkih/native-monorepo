/**
 * Shared Bank components: the modal overlay (mirrors ar/parts.tsx and ap/parts.tsx — each feature
 * keeps its own copy), the select field classes, the statement-line status badge, and a plain
 * (non-money) count tile for the "Unreconciled" KPI. Plain helpers live in ./format.ts so this
 * file only exports components (keeps react-refresh/only-export-components clean).
 */

import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { cn } from '@/lib/cn'
import type { StatementLineStatus } from './api'

export const SELECT_CLASSES =
  'h-11 rounded-xl border border-line bg-surface px-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

/** Simple modal overlay — closes on backdrop click or Escape. */
export function DialogOverlay({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-2xl p-6">{children}</Card>
    </div>
  )
}

/** Status pill — RECONCILED is the only "profit" (green) tone; green stays reserved for that. */
export function StatementStatusBadge({ status }: { status: StatementLineStatus }) {
  const { t } = useTranslation()
  return (
    <Badge tone={status === 'RECONCILED' ? 'profit' : 'amber'}>
      {t(`bank.reconcile.status.${status}` as Parameters<typeof t>[0])}
    </Badge>
  )
}

/** A single headline count (not money), with a loading skeleton — mirrors financeUi's KpiTile. */
export function CountTile({
  label,
  value,
  locale,
  loading,
}: {
  label: string
  value: number
  locale: string
  loading: boolean
}) {
  return (
    <Card className="p-5">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-3 h-7 w-16 animate-pulse rounded bg-ink-100" />
      ) : (
        <div className={cn('tnum mt-2 font-mono text-[25px] font-semibold text-ink')}>
          {new Intl.NumberFormat(locale).format(value)}
        </div>
      )}
    </Card>
  )
}
