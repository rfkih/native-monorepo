/**
 * ServiceStates.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import type { } from '@/lib/session'
import type { } from '@/features/loyalty/api'
import type { } from './../config'


export function EstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Empty / loading states
// ---------------------------------------------------------------------------

export function EmptyCatalog({ message }: { message: string }) {
  return (
    <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
      {message}
    </p>
  )
}

export function CatalogSkeleton() {
  return (
    <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}>
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="overflow-hidden rounded-xl border border-line bg-surface p-4" aria-hidden="true">
          <div className="shimmer h-3.5 w-3/4 rounded-md" />
          <div className="shimmer mt-2 h-3 w-1/3 rounded-md" />
        </div>
      ))}
    </div>
  )
}

export function ChipsSkeleton() {
  return (
    <div className="flex flex-wrap gap-2" aria-hidden="true">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="shimmer h-10 w-24 rounded-full" />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// NoCompany
// ---------------------------------------------------------------------------

export function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <Card className="w-full max-w-md p-10 text-center">
        <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('servicePos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-5 inline-block rounded-xl bg-emerald px-4 py-2.5 text-sm font-bold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </Card>
    </div>
  )
}
