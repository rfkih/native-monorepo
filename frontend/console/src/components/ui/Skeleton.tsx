/**
 * Skeleton loading primitives — the app's loading language is SKELETON-FIRST for surface
 * loads (spinners remain for in-flight ACTIONS: buttons, saves, payments).
 *
 * Follows the idiom Dashboard already established: `animate-pulse` blocks on `bg-ink-100`
 * (the token flips with the theme automatically). Three layers share ONE geometry so a cold
 * start reads as one continuous surface instead of three different loading screens:
 *
 *   index.html static boot skeleton (paints before the JS bundle)
 *     → <AppSkeleton /> (auth restore, session/grants gates, back-office route chunks)
 *     → each screen's own data skeletons (e.g. Pos's MenuSkeleton, Dashboard's tiles).
 *
 * <PosSkeleton /> is the till counterpart: /pos route chunks + outlet resolution render a
 * menu-grid + order-rail ghost instead of a dashboard-shaped one.
 *
 * All blocks are aria-hidden; the wrappers carry aria-busy so screen readers hear "busy",
 * not a parade of empty groups. No user-facing strings (rule 9 satisfied by silence).
 */
import { cn } from '@/lib/cn'

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('animate-pulse rounded-lg bg-ink-100', className)} />
}

/**
 * Composite content skeletons — for a screen's FIRST data load (React Query v5's `isLoading`
 * is first-load only; refetches keep the stale content rendered, so these never flash on a
 * filter change or invalidation). Pick the one that mirrors the loaded layout; compose with
 * raw <Skeleton /> blocks when a screen's shape is special. Shown immediately, no artificial
 * delay: a skeleton→content swap reads as a crossfade, unlike a spinner→content jump.
 */

/** Rows in a bordered card — the standard list/table surface (invoices, vendors, team…). */
export function ListSkeleton({
  rows = 6,
  avatar = false,
  className,
}: {
  rows?: number
  avatar?: boolean
  className?: string
}) {
  return (
    <div
      aria-busy="true"
      className={cn('overflow-hidden rounded-card border border-line bg-surface', className)}
    >
      <div className="divide-y divide-line">
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className="flex items-center gap-3 px-4 py-3.5 sm:px-5">
            {avatar ? <Skeleton className="size-9 shrink-0 rounded-xl" /> : null}
            <div className="min-w-0 flex-1">
              <Skeleton className="h-4 w-40 max-w-[60%]" />
              <Skeleton className="mt-1.5 h-3 w-24 max-w-[40%]" />
            </div>
            <Skeleton className="h-4 w-20 shrink-0" />
          </div>
        ))}
      </div>
    </div>
  )
}

const STAT_GRID: Record<number, string> = {
  2: 'sm:grid-cols-2',
  3: 'sm:grid-cols-3',
  4: 'sm:grid-cols-4',
}

/** A row of stat tiles (label + big figure), like the dashboard/aging headline cards. */
export function StatCardsSkeleton({ cards = 3, className }: { cards?: 2 | 3 | 4; className?: string }) {
  return (
    <div aria-busy="true" className={cn('grid gap-4', STAT_GRID[cards] ?? 'sm:grid-cols-3', className)}>
      {Array.from({ length: cards }, (_, i) => (
        <div key={i} className="rounded-card border border-line bg-surface p-5">
          <Skeleton className="h-3 w-20" />
          <Skeleton className="mt-3 h-7 w-28 max-w-full" />
        </div>
      ))}
    </div>
  )
}

/** Stacked label + input ghosts — detail drawers and dialogs that load a record before editing. */
export function FormSkeleton({ fields = 4, className }: { fields?: number; className?: string }) {
  return (
    <div aria-busy="true" className={cn('flex flex-col gap-5', className)}>
      {Array.from({ length: fields }, (_, i) => (
        <div key={i}>
          <Skeleton className="h-3.5 w-24" />
          <Skeleton className="mt-2 h-10 rounded-xl" />
        </div>
      ))}
    </div>
  )
}

/** Full-screen shell ghost — same geometry as Shell (240px sidebar at lg+, 64px topbar). */
export function AppSkeleton() {
  return (
    <div aria-busy="true" className="min-h-screen bg-paper lg:grid lg:grid-cols-[240px_1fr]">
      {/* Desktop sidebar rail — mirrors Shell's aside */}
      <aside className="hidden h-screen flex-col border-r border-line bg-surface px-4 py-5 lg:flex">
        <div className="flex items-center gap-2.5 px-2">
          <Skeleton className="size-9 rounded-xl" />
          <Skeleton className="h-4 w-24" />
        </div>
        <div className="mt-8 flex flex-col gap-7">
          {[0, 1, 2].map((group) => (
            <div key={group} className="flex flex-col gap-2.5">
              <Skeleton className="h-2.5 w-16" />
              <Skeleton className="h-9 rounded-xl" />
              <Skeleton className="h-9 rounded-xl" />
              <Skeleton className="h-9 w-4/5 rounded-xl" />
            </div>
          ))}
        </div>
      </aside>

      <div className="flex min-h-screen flex-col">
        {/* Topbar */}
        <div className="flex h-16 items-center gap-3 border-b border-line bg-surface px-4 sm:px-6">
          <Skeleton className="size-9 rounded-xl lg:hidden" />
          <Skeleton className="h-4 w-40 max-w-[40vw]" />
          <div className="flex-1" />
          <Skeleton className="size-9 rounded-full" />
          <Skeleton className="size-9 rounded-full" />
          <Skeleton className="hidden h-9 w-28 rounded-xl sm:block" />
        </div>
        {/* Content: page title + stat cards + main panel */}
        <div className="mx-auto w-full max-w-[1100px] flex-1 px-4 py-6 sm:px-6">
          <Skeleton className="h-7 w-48 max-w-[60vw]" />
          <Skeleton className="mt-2 h-4 w-72 max-w-[80vw]" />
          <div className="mt-6 grid gap-4 sm:grid-cols-3">
            <Skeleton className="h-28 rounded-card" />
            <Skeleton className="h-28 rounded-card" />
            <Skeleton className="h-28 rounded-card" />
          </div>
          <Skeleton className="mt-4 h-64 rounded-card sm:h-80" />
        </div>
      </div>
    </div>
  )
}

/** Full-screen till ghost — header, category chips, menu-tile grid, order rail at lg+. */
export function PosSkeleton() {
  return (
    <div aria-busy="true" className="flex min-h-screen flex-col bg-paper">
      {/* POS header */}
      <div className="flex h-14 items-center gap-3 border-b border-line bg-surface px-3 sm:px-4">
        <Skeleton className="size-9 rounded-xl" />
        <Skeleton className="h-4 w-28" />
        <div className="flex-1" />
        <Skeleton className="size-9 rounded-full" />
        <Skeleton className="size-9 rounded-full" />
      </div>
      <div className="flex min-h-0 flex-1">
        {/* Menu side: category chips + tile grid */}
        <div className="min-w-0 flex-1 p-3 sm:p-4">
          <div className="flex gap-2 overflow-hidden">
            {[0, 1, 2, 3, 4].map((chip) => (
              <Skeleton key={chip} className="h-9 w-24 shrink-0 rounded-full" />
            ))}
          </div>
          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
            {[0, 1, 2, 3, 4, 5, 6, 7].map((tile) => (
              <Skeleton key={tile} className="h-28 rounded-2xl" />
            ))}
          </div>
        </div>
        {/* Order rail (tablet+) */}
        <div className="hidden w-[340px] shrink-0 flex-col gap-3 border-l border-line bg-surface p-4 lg:flex">
          <Skeleton className="h-5 w-32" />
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-12 rounded-xl" />
          ))}
          <div className="flex-1" />
          <Skeleton className="h-14 rounded-xl" />
        </div>
      </div>
    </div>
  )
}
