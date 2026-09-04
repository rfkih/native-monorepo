/**
 * Shared AP components: the modal overlay (mirrors org/parts.tsx — each feature keeps its own
 * copy, see groups/hr precedent), the select field classes, and the bill status badge. Plain
 * (non-component) helpers live in ./format.ts so this file only exports components (keeps
 * react-refresh/only-export-components clean). One source so Vendors/Bills/BillDetail/
 * NewBill/ApAging can't drift.
 */

import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import type { BillStatus } from './api'

export const SELECT_CLASSES =
  'w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

/** Simple modal overlay — closes on backdrop click, Escape, or the phone/browser Back button. */
export function DialogOverlay({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  useBackDismiss(onClose)
  useScrollLock()
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
      <Card className="w-full max-w-md p-6">{children}</Card>
    </div>
  )
}

/** Status pill — color-coded by BillStatus. Green (profit tone) reserved for PAID only. */
export function BillStatusBadge({ status }: { status: BillStatus }) {
  const { t } = useTranslation()
  const tone: 'profit' | 'loss' | 'amber' | 'info' | 'neutral' =
    status === 'PAID'
      ? 'profit'
      : status === 'VOID'
        ? 'loss'
        : status === 'PARTIALLY_PAID'
          ? 'amber'
          : status === 'POSTED'
            ? 'info'
            : 'neutral'
  return <Badge tone={tone}>{t(`ap.bills.status.${status}` as Parameters<typeof t>[0])}</Badge>
}
