/**
 * BackButton — the ONE in-app back control (navigation contract, rule N1).
 *
 * Back is an ACTION, not a destination, so this is a `<button>` that POPS history — never a
 * `<Link>`. A linking back arrow pushes a new entry on every press, so two "backs" then need three
 * hardware-Back presses to undo, and the back guard's `confirmLeave` (`go(-2)`) can land the user
 * FORWARD of where they started. That is the single biggest source of the "the app jumps around"
 * report.
 *
 * `fallback` is used ONLY when there is nothing to pop — a deep link, a shared URL, an app cold-
 * started on a sub-page. `backIntentFor` (backGuardProtocol, unit-tested) owns that decision and
 * the sentinel arithmetic; nothing here re-derives it. The fallback navigation REPLACES rather than
 * pushes: a back press must never grow the stack, whichever branch it takes.
 *
 * The caller supplies `className` and optionally the icon, because the surrounding headers legit-
 * imately differ (the 44px phone ScreenHeader target vs the 38px bordered circle the POS-chrome
 * pages use). Everything about the BEHAVIOUR is fixed here.
 */
import type { ReactNode } from 'react'
import { ChevronLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { backIntentFor } from './backGuardProtocol'

export function BackButton({
  fallback,
  className,
  label,
  children,
}: {
  /** Where to go when history has nothing to pop. Never the normal path. */
  fallback: string
  className?: string
  /** Already-translated accessible name; defaults to the generic "Back". Do NOT name a destination
   *  here — the control no longer guarantees one (that was the old `backToPos` label's lie). */
  label?: string
  /** Icon override; defaults to the ScreenHeader chevron. */
  children?: ReactNode
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const name = label ?? t('common.back')

  return (
    <button
      type="button"
      onClick={() => {
        const intent = backIntentFor(window.history.state)
        if (intent.kind === 'pop') navigate(intent.delta)
        else navigate(fallback, { replace: true })
      }}
      aria-label={name}
      title={name}
      className={className}
    >
      {children ?? <ChevronLeft className="size-[22px]" aria-hidden />}
    </button>
  )
}
