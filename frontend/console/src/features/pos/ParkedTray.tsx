/**
 * ParkedTray — slide-in panel listing PARKED orders for the current business.
 *
 * Each entry shows: order type, table label (DINE_IN only), line count,
 * total, and the timestamp when it was parked. Tapping an entry calls the
 * onResume callback with the orderId so the POS can fetch the full order and
 * reload the cart.
 *
 * Money rule (rule 8): amounts displayed via formatMoney().
 * Strings rule (rule 9): all labels are i18n keys.
 */
import { useTranslation } from 'react-i18next'
import { X, ShoppingBag, Table2 } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { useParkedOrders } from './api'
import type { ParkedOrderSummary } from './api'

interface Props {
  session: CompanySession
  locale: string
  onResume: (orderId: string) => void
  onClose: () => void
}

export function ParkedTray({ session, locale, onResume, onClose }: Props) {
  const { t } = useTranslation()
  const parkedQuery = useParkedOrders(session)
  const parked = parkedQuery.data ?? []

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-ink/30 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.parked.trayTitle')}
    >
      <div className="flex h-full w-full max-w-sm flex-col bg-surface shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div className="flex items-center gap-2">
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('pos.parked.trayTitle')}
            </h2>
            {parked.length > 0 ? (
              <Badge tone="amber">{parked.length}</Badge>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-paper focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* List */}
        <div className="flex-1 overflow-y-auto overscroll-contain p-5">
          {parkedQuery.isLoading ? (
            <div className="grid place-items-center py-16 text-emerald">
              <Spinner />
            </div>
          ) : parked.length === 0 ? (
            <p className="py-10 text-center text-sm text-ink-3">
              {t('pos.parked.noParked')}
            </p>
          ) : (
            <ul className="space-y-2">
              {parked.map((order) => (
                <ParkedEntry
                  key={order.orderId}
                  order={order}
                  locale={locale}
                  onResume={onResume}
                />
              ))}
            </ul>
          )}
        </div>

        {/* Note */}
        <div className="border-t border-line px-5 py-3">
          <p className="text-xs text-ink-3">{t('pos.parked.unpaidNote')}</p>
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// ParkedEntry
// ---------------------------------------------------------------------------

function ParkedEntry({
  order,
  locale,
  onResume,
}: {
  order: ParkedOrderSummary
  locale: string
  onResume: (orderId: string) => void
}) {
  const { t } = useTranslation()

  const orderTypeKey = orderTypeI18nKey(order.orderType)
  const timeStr = formatParkedTime(order.occurredAt, locale)

  return (
    <li>
      <button
        type="button"
        onClick={() => onResume(order.orderId)}
        className={cn(
          'w-full rounded-xl border border-line bg-surface px-4 py-3 text-left transition-all',
          'hover:border-emerald/40 hover:shadow-[0_4px_16px_-8px_rgba(13,106,74,0.3)]',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        )}
      >
        {/* Top row: type badge + table + total */}
        <div className="flex items-center gap-2">
          <span className="flex items-center gap-1 text-xs font-medium text-ink-2">
            {order.orderType === 'DINE_IN' ? (
              <Table2 className="size-3.5" aria-hidden="true" />
            ) : (
              <ShoppingBag className="size-3.5" aria-hidden="true" />
            )}
            {t(orderTypeKey)}
          </span>
          {order.tableLabel ? (
            <Badge tone="neutral" className="text-[10px] px-1.5 py-0">
              {order.tableLabel}
            </Badge>
          ) : null}
          <span className="ml-auto tnum font-mono text-sm font-medium text-ink">
            {formatMoney(order.totalMinor, order.currency, locale)}
          </span>
        </div>

        {/* Bottom row: line count + time + resume hint */}
        <div className="mt-1.5 flex items-center justify-between text-xs text-ink-3">
          <span>{t('pos.parked.lineCount', { n: order.lineCount })}</span>
          <span>{timeStr}</span>
        </div>

        <div className="mt-2 text-xs font-medium text-emerald-2">{t('pos.parked.resume')}</div>
      </button>
    </li>
  )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function orderTypeI18nKey(orderType: string): string {
  switch (orderType) {
    case 'DINE_IN':
      return 'pos.orderType.dineIn'
    case 'TAKEAWAY':
      return 'pos.orderType.takeaway'
    case 'DELIVERY':
      return 'pos.orderType.delivery'
    default:
      return 'pos.orderType.dineIn'
  }
}

function formatParkedTime(occurredAt: string, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, {
      hour: '2-digit',
      minute: '2-digit',
      day: 'numeric',
      month: 'short',
    }).format(new Date(occurredAt))
  } catch {
    return occurredAt
  }
}
