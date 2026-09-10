/**
 * InventoryChrome — the one page frame every `/inventory/*` screen draws (ADR 0075 N2: one page,
 * one chrome). These routes render OUTSIDE the dashboard Shell, so the frame is theirs to own:
 *
 * - below `sm` it is the phone `ScreenHeader` (44px back that POPS, title over a subtitle line,
 *   trailing icon actions) over a page that scrolls the DOCUMENT — not an inner container — so
 *   rule N4's scroll restore works when Back returns to the list;
 * - from `sm` up it is a 64px bar (bordered back circle, title + subtitle, then the caller's
 *   actions) over the same document-scrolled page.
 *
 * `backFallback` is where the arrow lands when there is nothing to pop (a deep link), never the
 * destination — BackButton decides (rule N1).
 */
import type { ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { BackButton } from '@/components/mobile/BackButton'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { cn } from '@/lib/cn'

/** The 44px icon action used in the phone header's trailing slot (the stocktake host's idiom). */
export const ICON_BUTTON =
  'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

export function InventoryChrome({
  title,
  subtitle,
  backFallback,
  phoneTrailing,
  desktopActions,
  children,
}: {
  title: string
  subtitle?: ReactNode
  backFallback: string
  /** Icon buttons for the phone header (44px each). */
  phoneTrailing?: ReactNode
  /** Controls for the desktop bar, right-aligned. */
  desktopActions?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="min-h-[100dvh] bg-paper">
      <ScreenHeader
        className="sm:hidden"
        title={title}
        subtitle={subtitle}
        backFallback={backFallback}
        trailing={phoneTrailing}
      />
      <header className="sticky top-0 z-20 hidden h-16 items-center gap-3.5 border-b border-line bg-paper px-6 sm:flex">
        <BackButton
          fallback={backFallback}
          className="grid size-[34px] shrink-0 place-items-center rounded-[10px] border border-line text-ink-2 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ArrowLeft className="size-[17px]" aria-hidden="true" />
        </BackButton>
        <div className="flex min-w-0 flex-col">
          <h1 className="truncate text-[16px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {title}
          </h1>
          {subtitle != null ? (
            <div className="mt-0.5 flex min-w-0 items-center gap-1 text-xs font-medium leading-tight text-ink-3">
              {subtitle}
            </div>
          ) : null}
        </div>
        <div className="flex-1" />
        {desktopActions}
      </header>
      {children}
    </div>
  )
}

/** The uppercase 10.5px micro-heading the design uses over every list and field. */
export function MicroLabel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'text-[10.5px] font-semibold uppercase tracking-[0.04em] text-ink-3',
        className,
      )}
    >
      {children}
    </div>
  )
}
