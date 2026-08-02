/**
 * SummaryPanel.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import {
  Minus,
  Plus,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Field, TextInput } from '@/components/ui/Field'
import type { CompanySession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { CouponField } from '@/components/CouponField'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { MemberField } from '@/components/MemberField'
import type { MemberResponse } from '@/features/loyalty/api'
import { OfflineHint } from '@/features/pos/offline/OfflineHint'
import type { VerticalPosConfig } from './../config'
import { BreakdownPanel } from './BreakdownPanel'
import type {
  AppliedPromotionResponse,
  CatalogItemResponse,
  PriceBreakdownResponse,
  StaffProfileResponse,
} from '../api'

export const SELECT_CLASS =
  'h-[52px] w-full rounded-xl border border-line bg-surface px-4 text-[15px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

export interface AddonLine {
  item: CatalogItemResponse
  qty: number
}


// ---------------------------------------------------------------------------
// SummaryPanel
// ---------------------------------------------------------------------------

export interface SummaryPanelProps {
  config: VerticalPosConfig
  currency: string
  locale: string
  selectedPackage: CatalogItemResponse | null
  addonLines: Map<string, AddonLine>
  onRemovePackage: () => void
  onSetAddonQty: (itemId: string, qty: number) => void
  bay: string
  onBayChange: (v: string) => void
  vehiclePlate: string
  onVehicleChange: (v: string) => void
  staffProfiles: StaffProfileResponse[]
  staffProfileId: string | null
  onStaffChange: (v: string | null) => void
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
  /** Phase 4 (ADR 0027): the attached loyalty member. */
  attachedMember: MemberResponse | null
  onMemberAttach: (member: MemberResponse) => void
  onMemberClear: () => void
  loyaltyRedeemPoints: number
  maxRedeemablePoints: number
  onLoyaltyRedeemChange: (points: number) => void
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  canCharge: boolean
  onCharge: () => void
}

export function SummaryPanel({
  config,
  currency,
  locale,
  selectedPackage,
  addonLines,
  onRemovePackage,
  onSetAddonQty,
  bay,
  onBayChange,
  vehiclePlate,
  onVehicleChange,
  staffProfiles,
  staffProfileId,
  onStaffChange,
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
  breakdown,
  grandTotalMinor,
  canCharge,
  onCharge,
}: SummaryPanelProps) {
  const { t } = useTranslation()
  const hasSelection = !!selectedPackage || addonLines.size > 0

  return (
    <div className="flex flex-col gap-5 p-5">
      <div>
        <h2 className="mb-2 text-[11px] font-bold uppercase tracking-[.08em] text-ink-3">
          {t('servicePos.summary.title')}
        </h2>
        {!hasSelection ? (
          <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
            {t(config.primaryItemLabels.summaryEmptyKey)}
          </p>
        ) : (
          <ul className="space-y-2">
            {selectedPackage ? (
              <li className="flex items-center justify-between gap-2 rounded-xl border border-line bg-paper px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-ink">{selectedPackage.name}</p>
                  <p className="tnum font-mono text-xs text-ink-3">
                    {formatMoney(selectedPackage.priceMinor, currency, locale)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={onRemovePackage}
                  aria-label={t('servicePos.summary.removeItem', { name: selectedPackage.name })}
                  className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-3 hover:bg-hover hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <X className="size-3.5" />
                </button>
              </li>
            ) : null}
            {[...addonLines.values()].map(({ item, qty }) => (
              <li
                key={item.id}
                className="flex items-center justify-between gap-2 rounded-xl border border-line bg-paper px-3 py-2.5"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
                  <p className="tnum font-mono text-xs text-ink-3">
                    {formatMoney(item.priceMinor * qty, currency, locale)}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <button
                    type="button"
                    onClick={() => onSetAddonQty(item.id, qty - 1)}
                    aria-label={t('pos.decreaseQty', { name: item.name })}
                    className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                  >
                    <Minus className="size-3" />
                  </button>
                  <span className="tnum w-5 text-center text-sm font-semibold text-ink">{qty}</span>
                  <button
                    type="button"
                    onClick={() => onSetAddonQty(item.id, qty + 1)}
                    aria-label={t('pos.increaseQty', { name: item.name })}
                    className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                  >
                    <Plus className="size-3" />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-4">
        <Field label={t(config.location.labelKey)} htmlFor="svc-location">
          <TextInput
            id="svc-location"
            value={bay}
            onChange={(e) => onBayChange(e.target.value)}
            placeholder={t(config.location.placeholderKey)}
            required={config.location.required}
          />
        </Field>

        {config.vehicleField ? (
          <Field label={t('carwashPos.vehiclePlate')} htmlFor="svc-plate">
            <TextInput
              id="svc-plate"
              value={vehiclePlate}
              onChange={(e) => onVehicleChange(e.target.value.toUpperCase())}
              placeholder={t('carwashPos.vehiclePlatePlaceholder')}
            />
          </Field>
        ) : null}

        {config.attribution.enabled ? (
          <Field
            label={
              <span className="inline-flex items-center gap-1.5">
                {t(config.attribution.labelKey)}
                {config.attribution.required ? (
                  <Badge tone="amber" className="px-1.5 py-0 text-[10px]">
                    {t('common.required')}
                  </Badge>
                ) : null}
              </span>
            }
            htmlFor="svc-staff"
            hint={
              config.attribution.required && config.attribution.requiredHintKey
                ? t(config.attribution.requiredHintKey)
                : undefined
            }
          >
            <select
              id="svc-staff"
              className={SELECT_CLASS}
              value={staffProfileId ?? ''}
              onChange={(e) => onStaffChange(e.target.value || null)}
              required={config.attribution.required}
              aria-required={config.attribution.required}
            >
              <option value="">{t('servicePos.noneOption')}</option>
              {staffProfiles.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.displayLabel}
                </option>
              ))}
            </select>
          </Field>
        ) : null}
      </div>

      <BreakdownPanel breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

      <AppliedPromotionChips promotions={appliedPromotions} currency={currency} locale={locale} />

      <CouponField
        code={couponCode}
        status={couponStatus}
        onApply={onCouponApply}
        onClear={onCouponClear}
        disabled={offline}
      />
      {offline ? <OfflineHint text={t('offline.disabled.coupon')} /> : null}

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
      />
      {offline ? <OfflineHint text={t('offline.disabled.member')} /> : null}

      {showDiscountInput ? (
        <div>
          <label htmlFor="svc-discount" className="mb-1.5 block text-sm font-medium text-ink-2">
            {t('pos.addDiscount')}
          </label>
          <input
            id="svc-discount"
            type="number"
            min="0"
            step="any"
            value={discountInput}
            onChange={(e) => onDiscountChange(e.target.value)}
            placeholder="0"
            aria-describedby={discountInvalid ? 'svc-discount-error' : undefined}
            className={cn(
              'h-11 w-full rounded-xl border bg-surface px-3 text-sm text-ink placeholder:text-ink-3/50 transition-colors',
              'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
              discountInvalid ? 'border-loss' : 'border-line',
            )}
          />
          {discountInvalid ? (
            <p id="svc-discount-error" className="mt-1 text-xs text-loss" role="alert">
              {t('pos.discountInvalid')}
            </p>
          ) : null}
        </div>
      ) : null}

      <Button size="xl" data-testid="service-charge" className="tnum w-full font-mono" disabled={!canCharge} onClick={onCharge}>
        {t('servicePos.chargeButton', { amount: formatMoney(grandTotalMinor, currency, locale) })}
      </Button>
    </div>
  )
}
