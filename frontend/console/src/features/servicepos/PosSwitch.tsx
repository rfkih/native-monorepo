/**
 * PosSwitch — picks WHICH POS surface to render for the current outlet's vertical. Thin by design:
 * Pos (restaurant) and ServicePos (carwash) each mount their OWN OutletGate internally (identical
 * to how MenuManagement/Kitchen already work), so re-resolving the outlet list here and letting the
 * chosen surface re-resolve it again is intentional and idempotent — this component never renders
 * ticket/order UI itself.
 *
 * Vertical resolution fails OPEN to 'restaurant' on a missing/null vertical — identical semantics to
 * components/OutletGate.tsx's own fail-open (never brick a POS terminal on cache staleness).
 *
 * CatalogSwitch is the /catalog counterpart: restaurant outlets already have a catalog manager
 * (MenuManagement, mounted at /menu) so it redirects there; carwash renders CatalogManagement;
 * every other vertical is still "coming soon".
 */
import { lazy, Suspense } from 'react'
import { Navigate } from 'react-router-dom'
import { Spinner } from '@/components/ui/Spinner'
import { VerticalComingSoon } from '@/components/VerticalComingSoon'
import { useSession } from '@/lib/session'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import { ServicePos } from './ServicePos'
import { CatalogManagement } from './CatalogManagement'
import { carwashConfig } from './carwashConfig'

const Pos = lazy(() => import('@/features/pos/Pos').then((m) => ({ default: m.Pos })))

function CenteredSpinner() {
  return (
    <div className="grid min-h-[60vh] place-items-center">
      <Spinner className="size-6 text-ink-3" />
    </div>
  )
}

export function PosSwitch() {
  const { company } = useSession()
  const { outlets, effectiveOutletId, status } = useResolvedOutlets()

  // No company at all — let Pos own its NoCompany screen (mirrors Pos.tsx's own `!company` gate;
  // useResolvedOutlets would otherwise report 'loading' forever with no company to resolve against).
  if (!company) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <Pos />
      </Suspense>
    )
  }

  if (status === 'loading') {
    return <CenteredSpinner />
  }

  const effectiveOutlet = outlets.find((o) => o.id === effectiveOutletId)
  const vertical = effectiveOutlet?.vertical ?? 'restaurant'

  if (vertical === 'carwash') {
    return <ServicePos config={carwashConfig} />
  }
  if (vertical === 'restaurant') {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <Pos />
      </Suspense>
    )
  }
  return <VerticalComingSoon vertical={vertical} />
}

export function CatalogSwitch() {
  const { company } = useSession()
  const { outlets, effectiveOutletId, status } = useResolvedOutlets()

  if (!company) {
    return <CatalogManagement />
  }

  if (status === 'loading') {
    return <CenteredSpinner />
  }

  const effectiveOutlet = outlets.find((o) => o.id === effectiveOutletId)
  const vertical = effectiveOutlet?.vertical ?? 'restaurant'

  if (vertical === 'restaurant') {
    return <Navigate to="/menu" replace />
  }
  if (vertical === 'carwash') {
    return <CatalogManagement />
  }
  return <VerticalComingSoon vertical={vertical} />
}
