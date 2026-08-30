/**
 * PosSwitch — picks WHICH POS surface to render for the current outlet's vertical. Thin by design:
 * Pos (restaurant) and ServicePos (carwash / barbershop, each with their own VerticalPosConfig)
 * mount their OWN OutletGate internally (identical to how MenuManagement/Kitchen already work), so
 * re-resolving the outlet list here and letting the chosen surface re-resolve it again is
 * intentional and idempotent — this component never renders ticket/order UI itself.
 *
 * Vertical resolution fails OPEN to 'restaurant' on a missing/null vertical — identical semantics to
 * components/OutletGate.tsx's own fail-open (never brick a POS terminal on cache staleness).
 *
 * CatalogSwitch is the /catalog counterpart: restaurant outlets already have a catalog manager
 * (MenuManagement, mounted at /menu) so it redirects there; carwash and barbershop each render
 * CatalogManagement over their own config; every other vertical is still "coming soon".
 */
import { lazy, Suspense } from 'react'
import { Navigate } from 'react-router-dom'
import { ListSkeleton, PosSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { VerticalComingSoon } from '@/components/VerticalComingSoon'
import { useSession } from '@/lib/session'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import { ServicePos } from './ServicePos'
import { CatalogManagement } from './CatalogManagement'
import { carwashConfig } from './carwashConfig'
import { barbershopConfig } from './barbershopConfig'

const Pos = lazy(() => import('@/features/pos/Pos').then((m) => ({ default: m.Pos })))

// Mirrors CatalogManagementInner's page shape (header + Packages/Add-ons/Washers cards) — the
// vertical isn't known yet at this point, so the ghost is the shared chrome, not a vertical-specific
// one.
function CatalogSwitchSkeleton() {
  return (
    <div className="min-h-[100dvh] bg-paper">
      <div className="flex h-16 items-center gap-3 border-b border-line bg-surface px-4 sm:px-6">
        <Skeleton className="size-9 shrink-0 rounded-full" />
        <Skeleton className="h-4 w-32" />
      </div>
      <div className="mx-auto max-w-3xl space-y-6 px-4 py-6 sm:px-6">
        <ListSkeleton rows={3} />
        <ListSkeleton rows={2} />
        <ListSkeleton rows={2} />
      </div>
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
      <Suspense fallback={<PosSkeleton />}>
        <Pos />
      </Suspense>
    )
  }

  if (status === 'loading') {
    return <PosSkeleton />
  }

  const effectiveOutlet = outlets.find((o) => o.id === effectiveOutletId)
  const vertical = effectiveOutlet?.vertical ?? 'restaurant'

  if (vertical === 'carwash') {
    return <ServicePos config={carwashConfig} />
  }
  if (vertical === 'barbershop') {
    return <ServicePos config={barbershopConfig} />
  }
  if (vertical === 'restaurant') {
    return (
      <Suspense fallback={<PosSkeleton />}>
        <Pos />
      </Suspense>
    )
  }
  return <VerticalComingSoon vertical={vertical} />
}

export function CatalogSwitch() {
  const { company } = useSession()
  const { outlets, effectiveOutletId, status } = useResolvedOutlets()

  // No company yet — render CatalogManagement anyway so it owns its own NoCompany screen (mirrors
  // PosSwitch's own `!company` branch above). The config passed here is never actually consulted:
  // CatalogManagement bails out to <NoCompany /> before reading anything off it.
  if (!company) {
    return <CatalogManagement config={carwashConfig} />
  }

  if (status === 'loading') {
    return <CatalogSwitchSkeleton />
  }

  const effectiveOutlet = outlets.find((o) => o.id === effectiveOutletId)
  const vertical = effectiveOutlet?.vertical ?? 'restaurant'

  if (vertical === 'restaurant') {
    return <Navigate to="/menu" replace />
  }
  if (vertical === 'carwash') {
    return <CatalogManagement config={carwashConfig} />
  }
  if (vertical === 'barbershop') {
    return <CatalogManagement config={barbershopConfig} />
  }
  return <VerticalComingSoon vertical={vertical} />
}
