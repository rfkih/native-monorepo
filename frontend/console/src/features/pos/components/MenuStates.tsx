/**
 * MenuStates.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowRight,
  Tag,
  Utensils,
} from 'lucide-react'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// MenuSkeleton
// ---------------------------------------------------------------------------

export function MenuSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-3 min-[1180px]:grid-cols-4">
      {/* Matches the loaded menu grid (Pos.tsx) — 2 cols on phone, else it jumps 1→2 on load. */}
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="overflow-hidden rounded-xl border border-line bg-surface" aria-hidden="true">
          <div className="shimmer h-[104px] w-full" />
          <div className="space-y-2 p-3">
            <div className="shimmer h-3.5 w-3/4 rounded-md" />
            <div className="shimmer h-3 w-1/3 rounded-md" />
          </div>
        </div>
      ))}
    </div>
  )
}


// ---------------------------------------------------------------------------
// EmptyMenu
// ---------------------------------------------------------------------------

export function EmptyMenu() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
      <div className="mb-5 grid size-16 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
        <Utensils className="size-7" aria-hidden="true" />
      </div>
      <h2 className="font-display text-xl font-bold text-ink">{t('pos.emptyMenu')}</h2>
      <p className="mx-auto mt-2 max-w-xs text-sm text-ink-3">{t('pos.emptyMenuHint')}</p>
    </div>
  )
}

/**
 * A category tab with no items — a designed answer instead of a silent void. Explains WHY it is
 * empty (items join a category by matching name until real category links exist) and, for
 * owner/manager, offers the one action that fixes it. Cashiers get the explanation only.
 */

export function EmptyCategory({ name, canManage }: { name: string; canManage: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="reveal flex flex-col items-center justify-center px-6 py-16 text-center">
      <div className="mb-4 grid size-14 place-items-center rounded-2xl bg-ink-50 text-ink-3">
        <Tag className="size-6" aria-hidden="true" />
      </div>
      <h2 className="font-display text-lg font-bold text-ink">
        {t('pos.category.emptyTitle', { name })}
      </h2>
      <p className="mx-auto mt-1.5 max-w-sm text-sm leading-relaxed text-ink-3">
        {t('pos.category.emptyHint')}
      </p>
      {canManage ? (
        <Link
          to="/menu"
          className={cn(
            'mt-5 inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-4 py-2',
            'text-sm font-semibold text-emerald-2 transition-colors hover:border-emerald-line',
            'focus-visible:outline-2 focus-visible:outline-brand-500',
          )}
        >
          {t('pos.category.emptyCta')} <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      ) : null}
    </div>
  )
}
