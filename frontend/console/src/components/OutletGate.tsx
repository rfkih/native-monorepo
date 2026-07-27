/**
 * OutletGate — blocks the POS surfaces (POS, Menu, Kitchen) until a REAL outlet is resolved.
 *
 * ADR 0012: sales and all restaurant data must be keyed on an OUTLET id, never the
 * business-unit id. The gate renders a spinner while outlets resolve (no gate flash), an
 * error panel with retry when the outlet list cannot load, a blocking "no outlet" screen
 * when the company has none (unreachable for companies created after the default-outlet
 * seeding; still possible on partial hydration or for pre-ADR dev tenants), and otherwise
 * hands its children a session whose businessId IS the effective outlet id.
 */

import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Store } from 'lucide-react'
import { Spinner } from '@/components/ui/Spinner'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import type { CompanySession } from '@/lib/session'

export function OutletGate({
  company,
  children,
}: {
  company: CompanySession
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

  return <>{children({ ...company, businessId: effectiveOutletId })}</>
}
