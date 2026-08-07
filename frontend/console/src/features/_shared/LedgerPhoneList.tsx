/**
 * LedgerPhoneList — the phone rendering of an invoice/bill list (Native Console Android
 * design): the 7-column desktop table becomes two-line tappable rows — party + status badge,
 * a mono doc-number/date meta line, and the outstanding amount with the due date right-aligned.
 */
import { Link } from 'react-router-dom'
import { Card } from '@/components/ui/Card'
import { formatMoney } from '@/lib/money'

export interface LedgerPhoneRow {
  id: string
  /** Route of the detail page. */
  to: string
  party: string
  /** Mono meta line, e.g. "INV-2026-0881 · 20 Jul 2026". */
  meta: string
  /** Already-rendered status badge (InvoiceStatusBadge / BillStatusBadge). */
  badge: React.ReactNode
  /** Small right-aligned line under the amount, e.g. the due date. */
  due: string
  outstandingMinor: number
  currency: string
}

export function LedgerPhoneList({ rows, locale }: { rows: LedgerPhoneRow[]; locale: string }) {
  return (
    <Card className="rounded-[20px] p-2.5">
      {rows.map((r) => (
        <Link
          key={r.id}
          to={r.to}
          className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-emerald"
        >
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="truncate text-sm font-bold text-ink">{r.party}</span>
              {r.badge}
            </div>
            <p className="tnum mt-1 truncate font-mono text-[11.5px] text-ink-3">{r.meta}</p>
          </div>
          <div className="shrink-0 text-right">
            <div className="tnum font-mono text-sm font-bold text-ink">
              {formatMoney(r.outstandingMinor, r.currency, locale)}
            </div>
            <div className="mt-0.5 text-[11px] font-medium text-ink-3">{r.due}</div>
          </div>
        </Link>
      ))}
    </Card>
  )
}
