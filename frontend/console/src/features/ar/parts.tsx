/**
 * Shared AR components: the modal overlay (mirrors org/parts.tsx — each feature keeps its own
 * copy, see groups/hr precedent), the select field classes, and the invoice status badge. Plain
 * (non-component) helpers live in ./format.ts so this file only exports components (keeps
 * react-refresh/only-export-components clean). One source so Customers/Invoices/InvoiceDetail/
 * NewInvoice/ArAging can't drift.
 */

import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/Badge'
import type { InvoiceStatus } from './api'

export const SELECT_CLASSES =
  'w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'


/** Status pill — color-coded by InvoiceStatus. Green (profit tone) reserved for PAID only. */
export function InvoiceStatusBadge({ status }: { status: InvoiceStatus }) {
  const { t } = useTranslation()
  const tone: 'profit' | 'loss' | 'amber' | 'info' | 'neutral' =
    status === 'PAID'
      ? 'profit'
      : status === 'VOID'
        ? 'loss'
        : status === 'PARTIALLY_PAID'
          ? 'amber'
          : status === 'ISSUED'
            ? 'info'
            : 'neutral'
  return <Badge tone={tone}>{t(`ar.invoices.status.${status}` as Parameters<typeof t>[0])}</Badge>
}
