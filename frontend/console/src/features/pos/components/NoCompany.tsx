/**
 * NoCompany.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  Utensils,
} from 'lucide-react'
import type { } from '@/lib/session'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// NoCompany
// ---------------------------------------------------------------------------

export function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <div className="w-full max-w-md rounded-[20px] border border-line bg-surface p-10 text-center shadow-sm">
        <div className="mx-auto mb-4 grid size-14 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
          <Utensils className="size-6" aria-hidden="true" />
        </div>
        <h2 className="font-display text-xl font-bold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-6 inline-block rounded-xl bg-emerald px-5 py-2.5 text-sm font-semibold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </div>
    </div>
  )
}
