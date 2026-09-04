/**
 * OperatorPinSheet (ADR 0049 P3b, policy-aware P3d, manager-present first-time enrollment P3e) —
 * the Business-app till's employee-pick (+ PIN, when the outlet requires one) sign-in. Reuses the
 * exact centered-dialog shell RegisterSheet/StocktakeSheet/GiftCardSellModal already use (POS
 * terminal, consistency over novelty) and CashPanelView's numeric-keypad idiom for the PIN pad.
 *
 * `requirePin` (the caller's already-fetched `useOutletPinPolicy` read, ADR 0049 P3d) branches the
 * step after the pick:
 *   'pick' — the outlet's roster (name only + `hasPin`, rule 6) as tappable rows; loading/empty/
 *            error states. A `requirePin === false` outlet shows a "tap your name to start" hint —
 *            there is no next step to type into.
 *   'pin'  — PIN-required outlets, picked employee already has one (`hasPin: true`): a masked
 *            numeric pad (4-6 digits); auto-submits at 6, or the Sign in button once at least 4
 *            digits are entered. A failed attempt clears the pad and shows a friendly, translated
 *            reason (never the raw server message) so the PIN stays uninferrable.
 *   'enroll-manager-required' — PIN-required outlets, picked employee has NO PIN yet (`hasPin:
 *            false`, P3e), and NO owner/manager is currently elevated on this till. The owner
 *            decided first-PIN enrollment must never be self-serve — this step shows who needs it
 *            and a "Sign in to manage" action (`useAuth().elevate()`, the same elevation trigger the
 *            till menu uses) instead of a PIN pad. Once a manager elevates, `elevatedRoles` updates
 *            and the sheet re-renders straight into 'enroll-create' below — no extra tap needed.
 *   'enroll-create'/'enroll-confirm' — PIN-required outlets, picked employee has NO PIN yet AND an
 *            owner/manager IS currently elevated (`useAuth().elevatedRoles` includes 'owner' or
 *            'manager'): the elevated manager sets a 4-6 digit PIN for the employee, then re-enters
 *            it to confirm (same keypad idiom as 'pin', reused via `PinEntryPad`/`PinDots` below). A
 *            mismatch clears only the confirm entry and lets the manager retry; a match writes the
 *            PIN via the EXISTING owner/manager endpoint (`useSetOperatorPin`, `features/hr/api.ts`
 *            — an upsert, so there is no "already exists" conflict to fall back from) then chains
 *            the existing sign-in with the same PIN (enrolled AND signed in in one flow).
 *   'confirming' — no-PIN outlets ONLY: picking a name mints immediately (no pad to fill in) — a
 *            brief busy state, or the same friendly error mapping as the PIN step with a Retry
 *            (never a raw server message).
 *   success — "Signed in as NAME · ROLE" before handing back to the till (Continue closes the
 *            sheet; the caller's own gate re-check on the next Pay/Send tap picks up the new
 *            operator — mirrors RegisterSheet's "hit Pay again" pattern, no auto-continue). Shown
 *            identically in every mode.
 *
 * Strings rule (rule 9): every label is an i18n key; the roster's displayName/role are literal
 * server data (free-text employee names/job titles), never translated.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, Check, KeyRound, Users, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { ApiError } from '@/lib/api'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { useSetOperatorPin } from '@/features/hr/api'
import { useOperatorSession } from './operatorSessionContext'
import { operatorSheetStep } from './operatorGate'
import { useOperatorRoster, type OperatorRosterEntry } from './api'

const PIN_MIN_LENGTH = 4
const PIN_MAX_LENGTH = 6

/** Maps the server's operator-session/set-PIN failure to a friendly, translated message (rule 9) —
 * the till never shows a raw ApiError string, and the mapping stays uniform for the 401 case (the
 * server itself is deliberately non-enumerating — see OperatorSessionController's own doc). Shared
 * by the enter-PIN step (the operator-session endpoint's own 401/423/403/409 vocabulary) AND the
 * manager-present enroll step (the elevated manager's `useSetOperatorPin` write, which only ever
 * produces the 401/403/generic cases here — there is no PIN-lockout or not-linked case on THAT
 * endpoint, but the same friendly copy still reads fine for it). */
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
  useScrollLock()
  const auth = useAuth()
  const operatorSession = useOperatorSession()
  const rosterQuery = useOperatorRoster(session, session.businessId)
  const setOperatorPinMutation = useSetOperatorPin(session)
  const panelRef = useRef<HTMLDivElement>(null)

  const [employee, setEmployee] = useState<OperatorRosterEntry | null>(null)
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [signedInAs, setSignedInAs] = useState<{ displayName: string; role: string } | null>(null)

  // P3e enroll-only state. `firstPin` holds the create-step entry while the confirm-step pad
  // fills in fresh (reusing `pin` for whichever pad is on screen — the two steps are never shown
  // at once). `mismatch` is a LOCAL check (never round-trips to the server) — distinct from
  // `error`, which is always a server failure.
  const [enrollStep, setEnrollStep] = useState<'create' | 'confirm'>('create')
  const [firstPin, setFirstPin] = useState('')
  const [mismatch, setMismatch] = useState(false)

  // ADR 0049 P3b elevation — the till's OWN roles are the outlet's own (cashier); only the ELEVATED
  // roles (a personal owner/manager login layered on top, `auth.elevate()`) ever include owner/
  // manager on a device terminal. First-PIN enrollment is manager-present ONLY — never self-serve —
  // so this checks `elevatedRoles` alone, never the merged `effectiveRoles`.
  const managerPresent = hasAnyRole(auth.elevatedRoles, 'owner', 'manager')
  // Which step to show — the pure, unit-tested decision core (operatorGate.ts). `set-pin`/
  // `manager-required` only ever arise for a require-PIN outlet's PIN-LESS employee
  // (`hasPin === false`; an unknown/missing hasPin falls through to enter-pin — W2).
  const sheetStep = employee
    ? operatorSheetStep({ requirePin, hasPin: employee.hasPin, managerElevated: managerPresent })
    : null
  const isEnrollFlow = sheetStep === 'set-pin' || sheetStep === 'manager-required'

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

  function resetPinState() {
    setPin('')
    setError(null)
    setEnrollStep('create')
    setFirstPin('')
    setMismatch(false)
  }

  function pickEmployee(entry: OperatorRosterEntry) {
    setEmployee(entry)
    resetPinState()
    // No-PIN outlet (ADR 0049 P3d): there is no pad to fill in — the pick itself IS the sign-in
    // attempt. `entry` is passed explicitly (not read back off state) since `setEmployee` above
    // hasn't committed yet in this same tick.
    if (!requirePin) void submit('', entry)
  }

  function backToRoster() {
    setEmployee(null)
    resetPinState()
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

  /** Sets the employee's first PIN via the EXISTING owner/manager write (P3e, manager-present
   * only — `isEnrollFlow` never reaches this without `managerPresent` also true, see the render
   * branch below), then chains the existing sign-in with that same PIN — enrolled AND signed in in
   * one flow. `useSetOperatorPin` is an UPSERT (a manager may set OR reset), so there is no
   * "already exists" conflict to fall back from here. */
  async function submitEnroll(finalPin: string) {
    if (!employee || busy) return
    setBusy(true)
    setError(null)
    try {
      await setOperatorPinMutation.mutateAsync({ employeeId: employee.employeeId, pin: finalPin })
      const info = await operatorSession.signIn(session.businessId, employee.employeeId, finalPin)
      setSignedInAs(info)
    } catch (err) {
      setPin('')
      setFirstPin('')
      setEnrollStep('create')
      setMismatch(false)
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  /** Advances the create → confirm → submit enroll sequence with `candidatePin` (the pad's current
   * digits) — called both by auto-submit-at-max-length (`pressDigit`) and the explicit
   * Continue/Create-PIN button. */
  function advanceEnroll(candidatePin: string) {
    if (busy) return
    if (enrollStep === 'create') {
      setFirstPin(candidatePin)
      setPin('')
      setMismatch(false)
      setEnrollStep('confirm')
      return
    }
    if (candidatePin !== firstPin) {
      setMismatch(true)
      setPin('')
      return
    }
    setMismatch(false)
    void submitEnroll(candidatePin)
  }

  function pressDigit(d: string) {
    if (busy || pin.length >= PIN_MAX_LENGTH) return
    const next = pin + d
    setPin(next)
    setError(null)
    setMismatch(false)
    if (next.length === PIN_MAX_LENGTH) {
      if (isEnrollFlow) advanceEnroll(next)
      else void submit(next)
    }
  }
  function pressBackspace() {
    if (busy) return
    setPin((p) => p.slice(0, -1))
  }

  const title = signedInAs
    ? t('operatorPin.signedInTitle')
    : employee
      ? requirePin
        ? isEnrollFlow
          ? !managerPresent
            ? t('operatorPin.enroll.managerRequiredTitle')
            : enrollStep === 'create'
              ? t('operatorPin.enroll.createTitle')
              : t('operatorPin.enroll.confirmTitle')
          : t('operatorPin.pinTitle')
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
        ) : isEnrollFlow ? (
          !managerPresent ? (
            /* ── Enroll gate: manager must be present (owner decision — never self-serve) ──── */
            <div
              className="space-y-4 px-5 py-8 text-center"
              data-testid="operator-pin-enroll-manager-required"
            >
              <p className="text-sm text-ink-3">
                {t('operatorPin.enroll.managerRequiredPrompt', { name: employee.displayName })}
              </p>
              <Button
                className="w-full"
                data-testid="operator-pin-enroll-elevate"
                onClick={() => auth.elevate()}
              >
                {t('posShell.elevateEntry')}
              </Button>
            </div>
          ) : enrollStep === 'create' ? (
            /* ── Enroll step 1: create PIN (P3e, manager sets a first sign-in PIN) ─────────── */
            <div className="px-5 py-5" data-testid="operator-pin-enroll-create">
              <p className="mb-4 text-center text-sm text-ink-3">
                {t('operatorPin.enroll.createPrompt', { name: employee.displayName })}
              </p>

              <PinDots
                length={pin.length}
                statusText={t('operatorPin.digitsEntered', { count: pin.length })}
              />

              {error ? (
                <p className="mb-3 text-center text-xs text-loss" role="alert">
                  {t(pinErrorKey(error) as Parameters<typeof t>[0])}
                </p>
              ) : null}

              <PinEntryPad
                pin={pin}
                busy={busy}
                onDigit={pressDigit}
                onBackspace={pressBackspace}
                backspaceLabel={t('operatorPin.backspace')}
              />

              <Button
                className="w-full"
                data-testid="operator-pin-enroll-continue"
                disabled={busy || pin.length < PIN_MIN_LENGTH}
                onClick={() => advanceEnroll(pin)}
              >
                {t('common.continue')}
              </Button>
            </div>
          ) : (
            /* ── Enroll step 2: confirm PIN (P3e) ──────────────────────────── */
            <div className="px-5 py-5" data-testid="operator-pin-enroll-confirm">
              <p className="mb-4 text-center text-sm text-ink-3">
                {t('operatorPin.enroll.confirmPrompt')}
              </p>

              <PinDots
                length={pin.length}
                statusText={t('operatorPin.digitsEntered', { count: pin.length })}
              />

              {mismatch ? (
                <p className="mb-3 text-center text-xs text-loss" role="alert">
                  {t('operatorPin.enroll.mismatch')}
                </p>
              ) : error ? (
                <p className="mb-3 text-center text-xs text-loss" role="alert">
                  {t(pinErrorKey(error) as Parameters<typeof t>[0])}
                </p>
              ) : null}

              <PinEntryPad
                pin={pin}
                busy={busy}
                onDigit={pressDigit}
                onBackspace={pressBackspace}
                backspaceLabel={t('operatorPin.backspace')}
              />

              <Button
                className="w-full"
                data-testid="operator-pin-enroll-submit"
                disabled={busy || pin.length < PIN_MIN_LENGTH}
                onClick={() => advanceEnroll(pin)}
              >
                {busy ? <Spinner /> : t('operatorPin.enroll.confirmAction')}
              </Button>
            </div>
          )
        ) : (
          /* ── Step 2: PIN pad (employee already has a PIN) ────────────────── */
          <div className="px-5 py-5">
            <p className="mb-4 text-center text-sm text-ink-3">
              {t('operatorPin.enterPinFor', { name: employee.displayName })}
            </p>

            <PinDots
              length={pin.length}
              statusText={t('operatorPin.digitsEntered', { count: pin.length })}
            />

            {error ? (
              <p className="mb-3 text-center text-xs text-loss" role="alert">
                {t(pinErrorKey(error) as Parameters<typeof t>[0])}
              </p>
            ) : null}

            <PinEntryPad
              pin={pin}
              busy={busy}
              onDigit={pressDigit}
              onBackspace={pressBackspace}
              backspaceLabel={t('operatorPin.backspace')}
            />

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

/** The masked-progress dots above every PIN pad (enter-PIN, enroll-create, enroll-confirm) — the
 * visible indicator is `aria-hidden` (a filled dot reveals nothing about the digit), paired with an
 * `sr-only` status paragraph carrying the actual count for assistive tech. */
function PinDots({ length, statusText }: { length: number; statusText: string }) {
  return (
    <>
      <div className="mb-5 flex justify-center gap-2.5" aria-hidden="true">
        {Array.from({ length: PIN_MAX_LENGTH }, (_, i) => (
          <span
            key={i}
            className={cn(
              'size-3 rounded-full border-2 transition-colors',
              i < length ? 'border-emerald bg-emerald' : 'border-line bg-transparent',
            )}
          />
        ))}
      </div>
      <p className="sr-only" role="status">
        {statusText}
      </p>
    </>
  )
}

/** The numeric keypad (3×4 grid) shared by every PIN pad — CashPanelView's keypad idiom. */
function PinEntryPad({
  pin,
  busy,
  onDigit,
  onBackspace,
  backspaceLabel,
}: {
  pin: string
  busy: boolean
  onDigit: (d: string) => void
  onBackspace: () => void
  backspaceLabel: string
}) {
  return (
    <div className="mb-4 grid grid-cols-3 gap-1.5">
      {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((d) => (
        <PinKeypadButton key={d} label={d} onClick={() => onDigit(d)} disabled={busy} />
      ))}
      <span aria-hidden="true" />
      <PinKeypadButton label="0" onClick={() => onDigit('0')} disabled={busy} />
      <PinKeypadButton
        label="⌫"
        ariaLabel={backspaceLabel}
        onClick={onBackspace}
        disabled={busy || pin.length === 0}
      />
    </div>
  )
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
