/**
 * OfflineHint — a tiny inline hint (icon + text) appended under a control that is disabled while
 * offline (coupon field, points redemption, gift-card tender, digital tenders, open bills/parking/
 * table floor). Presentation-only — every caller supplies its own already-translated `text`.
 */
import { WifiOff } from 'lucide-react'
import { cn } from '@/lib/cn'

export function OfflineHint({ text, className }: { text: string; className?: string }) {
  return (
    <p className={cn('flex items-center gap-1.5 text-[11px] text-ink-3', className)}>
      <WifiOff className="size-3 shrink-0" aria-hidden="true" />
      {text}
    </p>
  )
}
