/**
 * PaymentSurfaceFrame — the shared modal chrome of every POS payment surface (redesign P3):
 * backdrop overlay + Card + title row + close button. Extracted VERBATIM from the three payment
 * modals (their frames differed only in z-index and an optional subtitle).
 *
 * pos-shell rule: stateless presentation only — no hooks beyond i18n/back-dismiss, no fetching,
 * no mutations.
 */
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { cn } from '@/lib/cn'

export function PaymentSurfaceFrame({
  zIndexClass = 'z-40',
  subtitle,
  onClose,
  backDismissEnabled = true,
  children,
}: {
  /** Restaurant/service modals sit at z-40; the bill modal stacks over the bill sheet at z-[60]. */
  zIndexClass?: string
  /** Optional second line under the title (the bill modal shows the guest label). */
  subtitle?: string | null
  onClose: () => void
  /** Pass false while a charge is in flight — Back must not dismiss a payment mid-capture. */
  backDismissEnabled?: boolean
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose, backDismissEnabled)
  return (
    <div
      className={cn('fixed inset-0 grid place-items-center bg-black/40 p-4 backdrop-blur-sm', zIndexClass)}
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.payment.title')}
    >
      <Card className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain">
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          {subtitle ? (
            <div>
              <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
              <p className="mt-0.5 text-xs text-ink-3">{subtitle}</p>
            </div>
          ) : (
            <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
          )}
          <button
            type="button"
            onClick={onClose}
            aria-label={t('pos.payment.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>
        {children}
      </Card>
    </div>
  )
}
