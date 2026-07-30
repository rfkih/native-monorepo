/**
 * MemberField — shared POS loyalty-member attach control (Phase 4, ADR 0027). Used by BOTH the
 * restaurant POS (features/pos/Pos.tsx summary area) and the service POS (features/servicepos/
 * ServicePos.tsx summary panel) — lives in components/ (not inside either feature), mirroring
 * CouponField's placement and controlled-component shape.
 *
 * Flow: enter a phone → "Look up". Found → attaches immediately (onAttach). Not found (404) →
 * an inline "no member with this phone" note plus an optional display-name field and an "Enroll"
 * action (POST /api/v1/loyalty/members), which also attaches on success. Once attached, renders a
 * chip (display name + masked phone tail + points balance) with a Clear affordance, PLUS a
 * points-redeem numeric field (clamped client-side to `maxRedeemable`; the server re-clamps
 * authoritatively at checkout — see loyaltyref/LoyaltyRefAdvice.java's 409 loyalty-balance-insufficient
 * as the fail-safe for a stale client-side max).
 *
 * Points convention (v1): 1 point = 1 minor unit of the sale currency (task instruction) — so a
 * points balance/redeem amount renders via formatMoney(), exactly like any other amount, never a
 * bare number formatted separately from currency.
 *
 * Strings rule (rule 9): every label is an i18n key.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, TriangleAlert, User, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import {
  isMemberNotFound,
  useEnrollMember,
  useLookupMember,
  type MemberResponse,
} from '@/features/loyalty/api'

interface Props {
  session: CompanySession
  currency: string
  locale: string
  /** The attached member, or null. Controlled — the parent threads `member.id` into the live
   * quote + checkout as `loyaltyMemberId`. */
  member: MemberResponse | null
  onAttach: (member: MemberResponse) => void
  onClear: () => void
  /** Points to redeem (== minor units, v1 convention), controlled by the parent. */
  redeemPoints: number
  /** Client-side clamp ceiling — min(member.pointsBalance, remaining deductible total). */
  maxRedeemable: number
  onRedeemChange: (points: number) => void
  disabled?: boolean
  className?: string
}

export function MemberField({
  session,
  currency,
  locale,
  member,
  onAttach,
  onClear,
  redeemPoints,
  maxRedeemable,
  onRedeemChange,
  disabled,
  className,
}: Props) {
  const { t } = useTranslation()
  const [phone, setPhone] = useState('')
  const [notFound, setNotFound] = useState(false)
  const [enrollName, setEnrollName] = useState('')
  const [genericError, setGenericError] = useState<string | null>(null)

  const lookup = useLookupMember(session)
  const enroll = useEnrollMember(session)

  function submitLookup() {
    const trimmed = phone.trim()
    if (!trimmed || disabled) return
    setNotFound(false)
    setGenericError(null)
    lookup.mutate(trimmed, {
      onSuccess: (found) => {
        if (found) onAttach(found)
      },
      onError: (err) => {
        if (isMemberNotFound(err)) {
          setNotFound(true)
        } else {
          setGenericError(t('pos.loyalty.member.lookupError'))
        }
      },
    })
  }

  function submitEnroll() {
    const trimmed = phone.trim()
    if (!trimmed || disabled) return
    setGenericError(null)
    enroll.mutate(
      { phone: trimmed, displayName: enrollName.trim() || null },
      {
        onSuccess: (created) => {
          if (created) onAttach(created)
        },
        onError: () => setGenericError(t('pos.loyalty.member.enrollError')),
      },
    )
  }

  function clampRedeem(raw: number) {
    if (isNaN(raw) || raw < 0) return 0
    return Math.min(Math.round(raw), Math.max(0, maxRedeemable))
  }

  if (!member) {
    return (
      <div className={cn('flex flex-col gap-2', className)}>
        <div className="flex items-center gap-2">
          <input
            type="tel"
            value={phone}
            disabled={disabled || enroll.isPending}
            onChange={(e) => {
              setPhone(e.target.value)
              setNotFound(false)
              setGenericError(null)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                submitLookup()
              }
            }}
            placeholder={t('pos.loyalty.member.phonePlaceholder')}
            aria-label={t('pos.loyalty.member.phoneLabel')}
            className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 font-mono text-[13px] text-ink placeholder:font-sans placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
          <button
            type="button"
            onClick={submitLookup}
            disabled={disabled || phone.trim() === '' || lookup.isPending}
            className="h-10 shrink-0 rounded-xl border border-emerald-line bg-emerald-tint px-3.5 text-[13px] font-bold text-emerald-2 transition-colors hover:bg-brand-100/60 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {lookup.isPending ? <Spinner className="size-3.5" /> : t('pos.loyalty.member.lookUp')}
          </button>
        </div>

        {notFound ? (
          <div className="flex flex-col gap-2 rounded-xl border border-dashed border-line bg-paper p-3">
            <p className="text-xs text-ink-3">{t('pos.loyalty.member.notFound')}</p>
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={enrollName}
                disabled={disabled || enroll.isPending}
                onChange={(e) => setEnrollName(e.target.value)}
                placeholder={t('pos.loyalty.member.namePlaceholder')}
                aria-label={t('pos.loyalty.member.nameLabel')}
                className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 text-[13px] text-ink placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
              />
              <button
                type="button"
                onClick={submitEnroll}
                disabled={disabled || enroll.isPending}
                className="h-10 shrink-0 rounded-xl bg-emerald px-3.5 text-[13px] font-bold text-on-emerald transition-colors hover:bg-emerald-2 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                {enroll.isPending ? <Spinner className="size-3.5" /> : t('pos.loyalty.member.enroll')}
              </button>
            </div>
          </div>
        ) : null}

        {genericError ? (
          <p className="text-xs text-loss" role="alert">
            {genericError}
          </p>
        ) : null}
      </div>
    )
  }

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center gap-2">
        <Badge tone="profit" className="flex-1 justify-between px-3 py-1.5 text-[13px]">
          <span className="flex min-w-0 items-center gap-1.5">
            <User className="size-3.5 shrink-0" aria-hidden />
            <span className="truncate font-semibold">{member.displayName}</span>
            <span className="tnum shrink-0 font-mono text-[11px] opacity-80">{member.phoneTail}</span>
          </span>
          <span className="tnum shrink-0 font-mono text-[11px] font-semibold">
            {t('pos.loyalty.member.pointsBadge', { amount: formatMoney(member.pointsBalance, currency, locale) })}
          </span>
        </Badge>
        <button
          type="button"
          onClick={onClear}
          disabled={disabled}
          aria-label={t('pos.loyalty.member.clear')}
          className="grid size-9 shrink-0 place-items-center rounded-xl text-ink-3 transition-colors hover:bg-hover hover:text-loss disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <X className="size-4" />
        </button>
      </div>

      {maxRedeemable > 0 ? (
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="loyalty-redeem" className="shrink-0 text-xs font-medium text-ink-2">
            {t('pos.loyalty.member.redeemLabel')}
          </label>
          <input
            id="loyalty-redeem"
            type="number"
            inputMode="numeric"
            min={0}
            max={maxRedeemable}
            step={1}
            value={redeemPoints === 0 ? '' : String(redeemPoints)}
            disabled={disabled}
            onChange={(e) => onRedeemChange(clampRedeem(Number(e.target.value)))}
            placeholder="0"
            className="h-9 w-28 rounded-xl border border-line bg-surface px-2.5 text-right font-mono text-[13px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
          <button
            type="button"
            disabled={disabled}
            onClick={() => onRedeemChange(maxRedeemable)}
            className="h-9 shrink-0 rounded-xl border border-line bg-surface px-2.5 text-[12px] font-semibold text-ink-2 transition-colors hover:bg-hover disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('pos.loyalty.member.useMax')}
          </button>
          {redeemPoints > 0 ? (
            <span className="tnum flex items-center gap-1 text-[11px] font-semibold text-emerald-2">
              <CheckCircle2 className="size-3 shrink-0" aria-hidden />
              − {formatMoney(redeemPoints, currency, locale)}
            </span>
          ) : null}
        </div>
      ) : member.pointsBalance === 0 ? (
        <p className="flex items-center gap-1.5 text-xs text-ink-3">
          <TriangleAlert className="size-3.5 shrink-0" aria-hidden />
          {t('pos.loyalty.member.noPoints')}
        </p>
      ) : null}
    </div>
  )
}
