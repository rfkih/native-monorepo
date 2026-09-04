/**
 * OutletGate — blocks the POS surfaces (POS, Menu, Kitchen) until a REAL outlet OF THE
 * REQUIRED VERTICAL is resolved.
 *
 * ADR 0012: sales and all restaurant data must be keyed on an OUTLET id, never the
 * business-unit id. The gate renders a spinner while outlets resolve (no gate flash), an
 * error panel with retry when the outlet list cannot load, a blocking "no outlet" screen
 * when the company has none (unreachable for companies created after the default-outlet
 * seeding; still possible on partial hydration or for pre-ADR dev tenants), a per-vertical
 * coming-soon panel when `requiredVertical` is set and the effective outlet belongs to a
 * DIFFERENT vertical (e.g. this gate requires 'restaurant' but the effective outlet is a
 * carwash/barbershop one — each of those has its own POS, just not behind THIS gate), and
 * otherwise hands its children a session whose businessId IS the effective outlet id. ADR 0070:
 * the tree is flat, so the resolved outlet is the whole story — there is no level above it to
 * thread alongside.
 */

import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Store } from 'lucide-react'
import { Spinner } from '@/components/ui/Spinner'
import { VerticalComingSoon } from '@/components/VerticalComingSoon'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import type { Vertical } from '@/features/org/api'
import type { CompanySession } from '@/lib/session'

export function OutletGate({
  company,
  requiredVertical,
  children,
}: {
  company: CompanySession
  /** When set, the effective outlet's vertical must match or a coming-soon panel renders. */
  requiredVertical?: Vertical
  children: (session: CompanySession) => ReactNode
}) {
  const { t } = useTranslation()
  const { status, effectiveOutletId, refetch } = useResolvedOutlets()

  if (status === 'loading') {
    return (
      <div className="grid min-h-[60vh] place-items-center">
        <Spinner className="size-6 text-ink-3" />
      </div>
    )
  }

  if (status === 'error') {
    return (
      <div className="grid min-h-[60vh] place-items-center p-6">
        <div className="w-full max-w-sm rounded-[20px] border border-line bg-surface p-8 text-center shadow-sm">
          <p className="text-[15px] font-semibold text-ink">{t('outletGate.errorTitle')}</p>
          <p className="mt-2 text-sm text-ink-3">{t('outletGate.errorBody')}</p>
          <button
            type="button"
            onClick={refetch}
            className="mt-5 w-full rounded-xl bg-emerald py-2.5 text-sm font-semibold text-on-emerald transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('outletGate.retry')}
          </button>
        </div>
      </div>
    )
  }

  if (status === 'empty' || !effectiveOutletId) {
    return (
      <div className="grid min-h-[60vh] place-items-center p-6">
        <div className="w-full max-w-sm rounded-[20px] border border-line bg-surface p-8 text-center shadow-sm">
          <span className="mx-auto grid size-12 place-items-center rounded-2xl bg-emerald-tint">
            <Store className="size-6 text-emerald-2" aria-hidden="true" />
          </span>
          <p className="mt-4 text-[15px] font-semibold text-ink">{t('outletGate.title')}</p>
          <p className="mt-2 text-sm leading-relaxed text-ink-3">{t('outletGate.body')}</p>
          <Link
            to="/org"
            className="mt-5 inline-block w-full rounded-xl bg-emerald py-2.5 text-sm font-semibold text-on-emerald transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('outletGate.cta')}
          </Link>
        </div>
      </div>
    )
  }

  // Vertical gate: the vertical comes from the COMPANY, not the outlet — ADR 0070 moved it up
  // when the division level (which used to own it) was removed. FAIL OPEN to 'restaurant' on a
  // null/missing value: the V14 backfill guarantees the column server-side, so a null here can
  // only be an older server or cache staleness — never brick a live POS terminal on that.
  if (requiredVertical) {
    const effectiveVertical = company?.vertical ?? 'restaurant'
    if (effectiveVertical !== requiredVertical) {
      return <VerticalComingSoon vertical={effectiveVertical} />
    }
  }

  return (
    <>
      {children({
        ...company,
        businessId: effectiveOutletId,
      })}
    </>
  )
}
