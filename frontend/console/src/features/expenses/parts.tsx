/**
 * Shared presentational bits for the expenses feature (employee self-service + manager surfaces —
 * ADR 0030, Phases E6/E7). Split out so a component file only exports components
 * (react-refresh/only-export-components) and so `MyExpenses`/`ExpenseInbox`/`ExpensesList` can't
 * drift on the status-tone mapping — mirrors `features/ar/parts.tsx`'s `InvoiceStatusBadge`.
 */

import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/Badge'
import type { ClaimStatus } from './api'

/** Mirrors Badge's (unexported) `Tone` union. */
type StatusTone = 'neutral' | 'amber' | 'emerald' | 'profit' | 'info' | 'loss'

const STATUS_TONE: Record<ClaimStatus, StatusTone> = {
  DRAFT: 'neutral',
  SUBMITTED: 'amber',
  APPROVED: 'profit',
  REFUSED: 'loss',
  CANCELLED: 'neutral',
  VOIDED: 'loss',
  REIMBURSED: 'profit',
}

export function ClaimStatusBadge({ status }: { status: ClaimStatus }) {
  const { t } = useTranslation()
  return <Badge tone={STATUS_TONE[status]}>{t(`expenses.status.${status}`)}</Badge>
}
