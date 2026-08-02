/**
 * SummaryBar.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import {
  ChevronUp,
  Send,
} from 'lucide-react'
import type { CompanySession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { CouponField } from '@/components/CouponField'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { MemberField } from '@/components/MemberField'
import { OfflineHint } from '../offline/OfflineHint'
import type { AppliedPromotionResponse } from '../api'
import { type BillSummaryResponse } from '../billsApi'
import type { } from '../lib/categories'
import type { MemberResponse } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// SummaryBar — bottom bar (~96px)
// ---------------------------------------------------------------------------

export function SummaryBar({
  activeBill,
  lineCount,
  grandTotalMinor,
  currency,
  locale,
  discountInput,
  discountInvalid,
  onDiscountChange,
  showDiscountInput,
  offline,
  couponCode,
  couponStatus,
  onCouponApply,
  onCouponClear,
  appliedPromotions,
  session,
  attachedMember,
  onMemberAttach,
  onMemberClear,
  loyaltyRedeemPoints,
  maxRedeemablePoints,
  onLoyaltyRedeemChange,
  onExpand,
  onSend,
  onPay,
}: {
  activeBill: BillSummaryResponse | null
  lineCount: number
  grandTotalMinor: number
  currency: string
  locale: string
  discountInput: string
  discountInvalid: boolean
  onDiscountChange: (v: string) => void
  /** Manual discount is owner/manager-only (ADR 0026 §5) — hidden for a cashier session. */
  showDiscountInput: boolean
  /** Phase 5 (ADR 0028): disables the coupon field (points redemption is hidden separately, via
   * `maxRedeemablePoints` already forced to 0 by the caller). */
  offline: boolean
  couponCode: string | null
  couponStatus: 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null
  onCouponApply: (code: string) => void
  onCouponClear: () => void
  appliedPromotions: AppliedPromotionResponse[]
  session: CompanySession
  /** Phase 4 (ADR 0027): the attached loyalty member — walk-in cart mode only, mirrors the coupon
   * scope decision (bills are a follow-up). */
  attachedMember: MemberResponse | null
  onMemberAttach: (member: MemberResponse) => void
  onMemberClear: () => void
  loyaltyRedeemPoints: number
  maxRedeemablePoints: number
  onLoyaltyRedeemChange: (points: number) => void
  onExpand: () => void
  onSend: () => void
  onPay: () => void
}) {
  const { t } = useTranslation()
  const displayTotal = activeBill
    ? formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale)
    : formatMoney(grandTotalMinor, currency, locale)
  const displayName = activeBill?.guestLabel ?? ''
  const displayCount = activeBill?.lineCount ?? lineCount

  // Count "unsent" lines — we don't have the data at summary level; show total items
  const unsentCount = displayCount // simplified: show all items as "send" target when a bill is active

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-30 flex flex-col rounded-t-[28px] bg-surface shadow-[0_-12px_32px_rgba(15,23,42,.10)]"
      style={{ boxSizing: 'border-box' }}
    >
      {/* Drag handle */}
      <span className="absolute left-1/2 top-2.5 h-1 w-10 -translate-x-1/2 rounded-full bg-line" aria-hidden="true" />

      {/* Coupon + manual discount — walk-in cart mode only (not bill mode). Coupons on bills are a
          follow-up (ADR 0026 §"Consequences"); the manual discount input is owner/manager-only. */}
      {!activeBill && lineCount > 0 ? (
        <div className="flex flex-col gap-2.5 px-5 pt-5 pb-1">
          <CouponField
            code={couponCode}
            status={couponStatus}
            onApply={onCouponApply}
            onClear={onCouponClear}
            disabled={offline}
            className="max-w-sm"
          />
          {offline ? <OfflineHint text={t('offline.disabled.coupon')} className="max-w-sm" /> : null}
          <AppliedPromotionChips promotions={appliedPromotions} currency={currency} locale={locale} />
          <MemberField
            session={session}
            currency={currency}
            locale={locale}
            member={attachedMember}
            onAttach={onMemberAttach}
            onClear={onMemberClear}
            redeemPoints={loyaltyRedeemPoints}
            maxRedeemable={maxRedeemablePoints}
            onRedeemChange={onLoyaltyRedeemChange}
            disabled={offline}
            className="max-w-sm"
          />
          {offline ? <OfflineHint text={t('offline.disabled.member')} className="max-w-sm" /> : null}
          {showDiscountInput ? (
            <div className="flex items-center gap-2">
              <label
                htmlFor="pos-discount"
                className="shrink-0 text-sm font-medium text-ink-2"
              >
                {t('pos.addDiscount')}
              </label>
              <input
                id="pos-discount"
                type="number"
                min="0"
                step="any"
                value={discountInput}
                onChange={(e) => onDiscountChange(e.target.value)}
                placeholder="0"
                aria-describedby={discountInvalid ? 'pos-discount-error' : undefined}
                className={cn(
                  'h-11 w-40 rounded-xl border bg-surface px-3 text-sm text-ink placeholder:text-ink-3/50 transition-colors',
                  'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
                  discountInvalid ? 'border-loss' : 'border-line',
                )}
              />
              {discountInvalid ? (
                <p id="pos-discount-error" className="text-xs text-loss" role="alert">
                  {t('pos.discountInvalid')}
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {/* Main action row */}
      <div className="flex h-24 items-center gap-3.5 px-5">
        {/* Chevron expand */}
        <button
          type="button"
          onClick={onExpand}
          aria-label={t('bills.viewBill')}
          className="grid size-11 shrink-0 place-items-center rounded-full bg-ink-50 text-ink-2 transition-colors hover:bg-ink-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ChevronUp className="size-[18px]" aria-hidden="true" />
        </button>

        {/* Item / bill info */}
        <div className="min-w-0 flex-1">
          <div className="text-[15px] font-bold text-ink">
            {displayCount > 0
              ? t('bills.itemsAndBill', { n: displayCount, bill: displayName })
              : displayName}
          </div>
          <div className="mt-0.5 text-[11px] text-ink-3">
            {displayCount > 0
              ? t('bills.unsent', { n: unsentCount })
              : t('bills.noBillsHint')}
          </div>
        </div>

        {/* Send button (secondary) */}
        {displayCount > 0 ? (
          <button
            type="button"
            data-testid="pos-send"
            onClick={onSend}
            className="flex h-14 items-center gap-2 rounded-xl border border-emerald-line bg-emerald-tint px-5 text-[15px] font-bold text-emerald-2 transition-all hover:bg-emerald-tint/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Send className="size-[17px]" aria-hidden="true" />
            {t('bills.sendN', { n: unsentCount })}
          </button>
        ) : null}

        {/* Pay button (primary) */}
        <button
          type="button"
          data-testid="pos-pay"
          onClick={onPay}
          disabled={displayCount === 0 && !activeBill}
          className="tnum h-14 rounded-xl bg-emerald px-6 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
        >
          {t('bills.payTotal', { total: displayTotal })}
        </button>
      </div>
    </div>
  )
}
