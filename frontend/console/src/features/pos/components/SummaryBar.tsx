/**
 * SummaryBar — the TICKET DOCK (redesign P4).
 *
 * The always-visible bottom dock of the restaurant POS: a destination pill (tap = the order
 * switcher) makes the ticket's target explicit, the dock total is the money hierarchy's 20px
 * mono tier, and the action verbs are honest — walk-in: [Charge amount]; bill: [Send n] (fires
 * the kitchen ticket DIRECTLY, no sheet detour) + [Pay total] (opens payment without expanding
 * the sheet). The coupon/member/discount stack above the action row is unchanged (walk-in cart
 * only — ADR 0026/0027 scope).
 */
import { useTranslation } from 'react-i18next'
import { ChevronUp, ChevronsUpDown, Send, ShoppingBag, Users } from 'lucide-react'
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
  onDestinationClick,
  onSend,
  onPay,
  totalPending = false,
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
  /** Opens the order switcher (walk-in / bills / floor / parked). */
  onDestinationClick: () => void
  /** Bill mode only: fires the kitchen ticket directly. */
  onSend: () => void
  onPay: () => void
  /**
   * True while the live quote is settling behind a cart change (debounce + fetch): the shown
   * total may still be the PREVIOUS cart's — it renders dimmed until the quote lands. The button
   * stays enabled; the payment modal always recomputes from the settled quote.
   */
  totalPending?: boolean
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

      {/* Main action row — destination pill · total · verbs (P4 dock) */}
      <div className="flex h-24 items-center gap-3 px-4 sm:px-5">
        {/* Destination pill — the ticket's target, always visible; tap = order switcher */}
        <button
          type="button"
          data-testid="pos-destination"
          onClick={onDestinationClick}
          aria-label={t('posShell.currentOrder')}
          className="flex h-14 min-w-0 shrink items-center gap-2.5 rounded-xl bg-ink-50 px-3.5 text-left transition-colors hover:bg-ink-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {activeBill ? (
            <Users className="size-4 shrink-0 text-ink-2" aria-hidden="true" />
          ) : (
            <ShoppingBag className="size-4 shrink-0 text-ink-2" aria-hidden="true" />
          )}
          <span className="flex min-w-0 flex-col">
            <span className="truncate text-[13px] font-bold leading-tight text-ink">
              {activeBill ? displayName : t('posShell.walkInSale')}
            </span>
            <span className="text-[11px] leading-tight text-ink-3">
              {displayCount > 0 ? t('bills.lineCount', { n: displayCount }) : t('bills.noBillsHint')}
            </span>
          </span>
          <ChevronsUpDown className="size-3.5 shrink-0 text-ink-3" aria-hidden="true" />
        </button>

        {/* Dock total — the 20px mono tier of the money hierarchy. Dimmed while the quote is
            settling (totalPending): the figure on screen may still be the previous cart's. */}
        <div className="min-w-0 flex-1 text-right sm:pr-1">
          <div
            aria-busy={totalPending}
            className={`tnum truncate font-mono text-[20px] font-bold leading-tight text-ink transition-opacity ${totalPending ? 'animate-pulse opacity-50' : ''}`}
          >
            {displayTotal}
          </div>
        </div>

        {/* Expand chevron — bill mode only (walk-in has no sheet) */}
        {activeBill ? (
          <button
            type="button"
            onClick={onExpand}
            aria-label={t('bills.viewBill')}
            className="grid size-11 shrink-0 place-items-center rounded-full bg-ink-50 text-ink-2 transition-colors hover:bg-ink-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <ChevronUp className="size-[18px]" aria-hidden="true" />
          </button>
        ) : null}

        {activeBill ? (
          <>
            {/* Send — fires the kitchen ticket directly (no sheet detour, P4) */}
            {displayCount > 0 ? (
              <button
                type="button"
                data-testid="pos-send"
                onClick={onSend}
                className="flex h-14 shrink-0 items-center gap-2 rounded-xl border border-emerald-line bg-emerald-tint px-4 text-[15px] font-bold text-emerald-2 transition-all hover:bg-emerald-tint/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                <Send className="size-[17px]" aria-hidden="true" />
                {t('bills.sendN', { n: unsentCount })}
              </button>
            ) : null}
            <button
              type="button"
              data-testid="pos-pay"
              onClick={onPay}
              className="tnum h-14 shrink-0 rounded-xl bg-emerald px-5 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <span className={totalPending ? 'animate-pulse opacity-70' : undefined}>
                {t('bills.payTotal', { total: displayTotal })}
              </span>
            </button>
          </>
        ) : (
          <button
            type="button"
            data-testid="pos-pay"
            onClick={onPay}
            disabled={displayCount === 0}
            className="tnum h-14 shrink-0 rounded-xl bg-emerald px-5 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
          >
            <span className={totalPending ? 'animate-pulse opacity-70' : undefined}>
              {t('posShell.chargeAmount', { amount: displayTotal })}
            </span>
          </button>
        )}
      </div>
    </div>
  )
}
