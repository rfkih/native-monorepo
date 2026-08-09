/**
 * OperatorPinSheet (ADR 0049 P3b, policy-aware P3d) — the Business-app till's employee-pick (+
 * PIN, when the outlet requires one) sign-in. Reuses the exact centered-dialog shell
 * RegisterSheet/StocktakeSheet/GiftCardSellModal already use (POS terminal, consistency over
 * novelty) and CashPanelView's numeric-keypad idiom for the PIN pad.
 *
 * `requirePin` (the caller's already-fetched `useOutletPinPolicy` read, ADR 0049 P3d) branches the
 * step after the pick:
 *   'pick' — the outlet's roster (name only, rule 6) as tappable rows; loading/empty/error states.
 *            A `requirePin === false` outlet shows a "tap your name to start" hint — there is no
 *            next step to type into.
 *   'pin'  — PIN-required outlets ONLY: a masked numeric pad (4-6 digits); auto-submits at 6, or
 *            the Sign in button once at least 4 digits are entered. A failed attempt clears the
 *            pad and shows a friendly, translated reason (never the raw server message) so the PIN
 *            stays uninferrable.
 *   'confirming' — no-PIN outlets ONLY: picking a name mints immediately (no pad to fill in) — a
 *            brief busy state, or the same friendly error mapping as the PIN step with a Retry
 *            (never a raw server message).
 *   success — "Signed in as NAME · ROLE" before handing back to the till (Continue closes the
 *            sheet; the caller's own gate re-check on the next Pay/Send tap picks up the new
 *            operator — mirrors RegisterSheet's "hit Pay again" pattern, no auto-continue). Shown
 *            identically in both modes — only the PIN entry itself is skipped, never the
 *            confirmation.
 *
 * Strings rule (rule 9): every label is an i18n key; the roster's displayName/role are literal
 * server data (free-text employee names/job titles), never translated.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, Check, KeyRound, Users, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { useOperatorSession } from './operatorSessionContext'
import { useOperatorRoster, type OperatorRosterEntry } from './api'

const PIN_MIN_LENGTH = 4
const PIN_MAX_LENGTH = 6

/** Maps the server's operator-session failure to a friendly, translated message (rule 9) — the
 * till never shows a raw ApiError string, and the mapping stays uniform for the 401 case (the
 * server itself is deliberately non-enumerating — see OperatorSessionController's own doc). */
function pinErrorKey(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 401:
        return 'operatorPin.error.wrongPin'
      case 423:
        return 'operatorPin.error.locked'
      case 403:
        return 'operatorPin.error.notAssigned'
      case 409:
        return 'operatorPin.error.notLinked'
      default:
        return 'operatorPin.error.generic'
    }
  }
  return 'operatorPin.error.generic'
}

export function OperatorPinSheet({
  session,
  requirePin,
  onClose,
}: {
  session: CompanySession
  /** The outlet's require-PIN policy (ADR 0049 P3d) — the caller (Pos.tsx/ServicePos.tsx) fetches
   * this via `useOutletPinPolicy` and passes it down; the sheet itself never fetches it, so it
   * stays a pure function of its props for the branch this doc describes. */
  requirePin: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const operatorSession = useOperatorSession()
  const rosterQuery = useOperatorRoster(session, session.businessId)
  const panelRef = useRef<HTMLDivElement>(null)

  const [employee, setEmployee] = useState<OperatorRosterEntry | null>(null)
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [signedInAs, setSignedInAs] = useState<{ displayName: string; role: string } | null>(null)

  // Focus contract mirrors TillMenuSheet/MobileSheet (the SheetOverlay rules): initial focus on
  // the panel, Escape closes.
  useEffect(() => {
    panelRef.current?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const roster = rosterQuery.data ?? []

  function pickEmployee(entry: OperatorRosterEntry) {
    setEmployee(entry)
    setPin('')
    setError(null)
    // No-PIN outlet (ADR 0049 P3d): there is no pad to fill in — the pick itself IS the sign-in
    // attempt. `entry` is passed explicitly (not read back off state) since `setEmployee` above
    // hasn't committed yet in this same tick.
    if (!requirePin) void submit('', entry)
  }

  function backToRoster() {
    setEmployee(null)
    setPin('')
    setError(null)
  }

  async function submit(candidatePin: string, target: OperatorRosterEntry | null = employee) {
    if (!target || busy) return
    setBusy(true)
    setError(null)
    try {
      // requirePin === false: sign in with NO pin at all (never an empty string — the mint hook
      // omits the field entirely when undefined, mirroring the server's own "ignored, not just
      // blank" treatment at a no-PIN outlet).
      const info = requirePin
        ? await operatorSession.signIn(session.businessId, target.employeeId, candidatePin)
        : await operatorSession.signIn(session.businessId, target.employeeId)
      setSignedInAs(info)
    } catch (err) {
      setError(err)
      setPin('')
    } finally {
      setBusy(false)
    }
  }

  function pressDigit(d: string) {
    if (busy || pin.length >= PIN_MAX_LENGTH) return
    const next = pin + d
    setPin(next)
    setError(null)
    if (next.length === PIN_MAX_LENGTH) void submit(next)
  }
  function pressBackspace() {
    if (busy) return
    setPin((p) => p.slice(0, -1))
  }

  const title = signedInAs
    ? t('operatorPin.signedInTitle')
    : employee
      ? requirePin
        ? t('operatorPin.pinTitle')
        : t('operatorPin.noPinTitle')
      : t('operatorPin.title')

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-card border border-line bg-surface shadow-lg outline-none"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            {employee && !signedInAs ? (
              <button
                type="button"
                onClick={backToRoster}
                aria-label={t('common.back')}
                className="-ml-1 grid size-7 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                <ArrowLeft className="size-4" aria-hidden="true" />
              </button>
            ) : (
              <KeyRound className="size-5 text-emerald-2" aria-hidden="true" />
            )}
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" aria-hidden="true" />
          </button>
        </div>

        {signedInAs ? (
          /* ── Confirmation ────────────────────────────────────────────────── */
          <div className="space-y-4 px-5 py-6 text-center" data-testid="operator-pin-success">
            <span className="mx-auto grid size-12 place-items-center rounded-2xl bg-tint-profit">
              <Check className="size-6 text-profit-ink" aria-hidden="true" />
            </span>
            <p className="text-sm text-ink-3">
              {t('operatorPin.signedInAs', {
                name: signedInAs.displayName,
                role: signedInAs.role,
              })}
            </p>
            <Button className="w-full" onClick={onClose}>
              {t('common.continue')}
            </Button>
          </div>
        ) : !employee ? (
          /* ── Step 1: employee pick ───────────────────────────────────────── */
          <div className="px-5 py-4">
            {rosterQuery.isLoading ? (
              <ListSkeleton rows={4} avatar />
            ) : rosterQuery.isError ? (
              <div className="py-8 text-center">
                <p className="text-sm text-loss">{t('operatorPin.rosterError')}</p>
                <Button variant="outline" className="mt-4" onClick={() => void rosterQuery.refetch()}>
                  {t('common.retry')}
                </Button>
              </div>
            ) : roster.length === 0 ? (
              <div className="py-8 text-center">
                <Users className="mx-auto mb-2 size-5 text-ink-3" aria-hidden="true" />
                <p className="text-sm text-ink-3">{t('operatorPin.rosterEmpty')}</p>
              </div>
            ) : (
              <>
                {!requirePin ? (
                  <p className="mb-3 text-center text-xs text-ink-3">{t('operatorPin.noPinHint')}</p>
                ) : null}
                <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
                  {roster.map((entry) => (
                    <li key={entry.employeeId}>
                      <button
                        type="button"
                        data-testid="operator-pin-roster-row"
                        onClick={() => pickEmployee(entry)}
                        className="flex h-14 w-full items-center gap-3 px-4 text-left text-sm font-medium text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
                      >
                        <span className="grid size-9 shrink-0 place-items-center rounded-full bg-emerald-tint text-[13px] font-bold text-emerald-2">
                          {initials(entry.displayName)}
                        </span>
                        <span className="truncate">{entry.displayName}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        ) : !requirePin ? (
          /* ── No-PIN step: pick-and-mint interstitial ─────────────────────── */
          <div className="space-y-4 px-5 py-8 text-center" data-testid="operator-nopin-confirm">
            <p className="text-sm text-ink-3">
              {t('operatorPin.noPinSigningInAs', { name: employee.displayName })}
            </p>
            {busy ? (
              <Spinner className="mx-auto size-6 text-emerald-2" />
            ) : error ? (
              <>
                <p className="text-xs text-loss" role="alert">
                  {t(pinErrorKey(error) as Parameters<typeof t>[0])}
                </p>
                <Button
                  className="w-full"
                  data-testid="operator-nopin-retry"
                  onClick={() => void submit('', employee)}
                >
                  {t('common.retry')}
                </Button>
              </>
            ) : null}
          </div>
        ) : (
          /* ── Step 2: PIN pad ─────────────────────────────────────────────── */
          <div className="px-5 py-5">
            <p className="mb-4 text-center text-sm text-ink-3">
              {t('operatorPin.enterPinFor', { name: employee.displayName })}
            </p>

            <div className="mb-5 flex justify-center gap-2.5" aria-hidden="true">
              {Array.from({ length: PIN_MAX_LENGTH }, (_, i) => (
                <span
                  key={i}
                  className={cn(
                    'size-3 rounded-full border-2 transition-colors',
                    i < pin.length ? 'border-emerald bg-emerald' : 'border-line bg-transparent',
                  )}
                />
              ))}
            </div>
            <p className="sr-only" role="status">
              {t('operatorPin.digitsEntered', { count: pin.length })}
            </p>

            {error ? (
              <p className="mb-3 text-center text-xs text-loss" role="alert">
                {t(pinErrorKey(error) as Parameters<typeof t>[0])}
              </p>
            ) : null}

            {/* Numeric keypad (3×4 grid) — CashPanelView's keypad idiom */}
            <div className="mb-4 grid grid-cols-3 gap-1.5">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((d) => (
                <PinKeypadButton key={d} label={d} onClick={() => pressDigit(d)} disabled={busy} />
              ))}
              <span aria-hidden="true" />
              <PinKeypadButton label="0" onClick={() => pressDigit('0')} disabled={busy} />
              <PinKeypadButton
                label="⌫"
                ariaLabel={t('operatorPin.backspace')}
                onClick={pressBackspace}
                disabled={busy || pin.length === 0}
              />
            </div>

            <Button
              className="w-full"
              data-testid="operator-pin-submit"
              disabled={busy || pin.length < PIN_MIN_LENGTH}
              onClick={() => void submit(pin)}
            >
              {busy ? <Spinner /> : t('operatorPin.signInAction')}
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

/** Two-letter initials for the roster row's avatar circle — first + last name, uppercased. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0][0] ?? ''
  const last = parts.length > 1 ? (parts[parts.length - 1][0] ?? '') : ''
  return (first + last).toUpperCase()
}

function PinKeypadButton({
  label,
  ariaLabel,
  onClick,
  disabled,
}: {
  label: string
  ariaLabel?: string
  onClick: () => void
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className="tnum flex h-12 items-center justify-center rounded-xl border border-line bg-surface font-mono text-lg font-semibold text-ink transition-colors hover:bg-hover active:bg-emerald-tint disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
    >
      {label}
    </button>
  )
}
