/**
 * ServicePaymentModal — the service-POS counterpart of features/pos/PaymentModal.tsx (adapted, not
 * imported: the checkout/capture calls and payload shape are specific to the ticket-based service
 * POS contract). Same three-tender UX (ADR 0006), extended Phase 4 (ADR 0027) exactly like the
 * restaurant PaymentModal — see that file's class doc for the gift-card residual/full-coverage
 * design (identical here; ServicePaymentModal's `payment` field is always REQUIRED on the wire,
 * unlike restaurant's optional one, but the full-coverage wire choice — always send a payment
 * object — already accounted for that, so no branch is needed):
 *
 *   CASH  — numeric keypad + quick-cash chips; live change line; Pay fires
 *           POST {apiBase}/tickets/checkout with the payment block. CAPTURED immediately.
 *   QRIS  — two-step: checkout creates a PENDING ticket, then "Mark as paid" calls
 *           POST {apiBase}/tickets/{id}/capture. Badged "Demo · pending provider".
 *   CARD  — same two-step as QRIS.
 *
 * Copy: tender-generic strings (tender labels, keypad, change, pending banner, receipt-adjacent
 * wording) reuse the existing pos.payment.* keys verbatim — the payment UX is identical to the
 * restaurant POS's; only the request shape differs.
 *
 * Money rule (rule 8): all amounts are integer minor units; rendered via formatMoney().
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Gift, X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { GiftCardField } from '@/components/GiftCardField'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import type { GiftCardResponse, MemberResponse } from '@/features/loyalty/api'
import { checkoutErrorKey } from '@/features/pos-shell/payment/errorKeys'
import { quickChips } from '@/features/pos-shell/payment/quickChips'
import { OfflineHint } from '@/features/pos/offline/OfflineHint'
import { enqueueSale } from '@/features/pos/offline/queue'
import type { ProvisionalTotals, SaleQueueRow } from '@/features/pos/offline/db'
import type { VerticalPosConfig, TenderType } from './config'
import {
  useTicketCapture,
  useTicketCheckout,
  type PriceBreakdownResponse,
  type TicketLineInput,
  type TicketResponse,
} from './api'

interface Props {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  /** Server-authoritative grand total from the quote breakdown; drives the charge amount BEFORE
   * any gift-card tender. */
  grandTotalMinor: number
  discountMinor: number
  /** Phase 3 (ADR 0026): the committed coupon code (or null); forwarded to checkout. */
  couponCode?: string | null
  /** Phase 4 (ADR 0027): the loyalty member attached upstream on the main POS screen (read-only
   * here — attach/detach lives in MemberField, in ServicePos.tsx's SummaryPanel). */
  loyaltyMember?: MemberResponse | null
  loyaltyRedeemPoints?: number
  /** Live price breakdown from useTicketQuote — rendered in the modal header. */
  breakdown: PriceBreakdownResponse | null
  currency: string
  locale: string
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  onSuccess: (ticket: TicketResponse) => void
  onClose: () => void
  /** Phase 5 (ADR 0028): true when the terminal is offline — see PaymentModal.tsx's twin doc. */
  offline?: boolean
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}

// quickChips + the error → i18n-key mapping moved to features/pos-shell/payment (redesign P1) —
// the three payment modals carried byte-identical copies.

export function ServicePaymentModal({
  config,
  session,
  lines,
  grandTotalMinor,
  discountMinor,
  couponCode,
  loyaltyMember,
  loyaltyRedeemPoints,
  breakdown,
  currency,
  locale,
  bay,
  vehiclePlate,
  staffProfileId,
  onSuccess,
  onClose,
  offline = false,
  onOfflineSuccess,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<TenderType>('CASH')

  // Phase 4 (ADR 0027): gift-card redemption entered HERE — see features/pos/PaymentModal.tsx's
  // class doc for the residual/full-coverage design (identical here).
  const [giftCard, setGiftCard] = useState<GiftCardResponse | null>(null)
  const [giftCardRedeemMinor, setGiftCardRedeemMinor] = useState(0)
  // Offline (Phase 5, ADR 0028) — see PaymentModal.tsx's twin doc.
  const residualDueMinor = offline ? grandTotalMinor : Math.max(0, grandTotalMinor - giftCardRedeemMinor)
  const fullyCoveredByGiftCard = !offline && giftCard != null && residualDueMinor === 0
  const offlineProvisional: ProvisionalTotals | null =
    offline && breakdown
      ? {
          subtotalMinor: breakdown.subtotalMinor,
          discountMinor: breakdown.discountMinor,
          serviceChargeMinor: breakdown.serviceChargeMinor,
          taxMinor: breakdown.taxMinor,
          grandTotalMinor: breakdown.grandTotalMinor,
          currency: breakdown.currency,
          usesCachedRules: true,
        }
      : null

  const tenderOptions: { value: TenderType; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]

  const loyaltyMemberId = loyaltyMember?.id ?? null
  const effectiveLoyaltyRedeemPoints = loyaltyRedeemPoints ?? 0

  return (
    <div
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.payment.title')}
    >
      <Card className="reveal w-full max-w-sm overflow-hidden">
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('pos.payment.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        <ModalBreakdown breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

        {/* Gift-card redemption (Phase 4, ADR 0027) — unreachable offline (Phase 5, ADR 0028). */}
        {offline ? (
          <div className="border-b border-line px-5 py-3 space-y-1.5">
            <OfflineHint text={t('offline.disabled.digitalTender')} />
            <OfflineHint text={t('offline.disabled.giftCard')} />
          </div>
        ) : (
          <div className="border-b border-line px-5 py-3">
            <p className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-ink-3">
              <Gift className="size-3.5 shrink-0" aria-hidden />
              {t('pos.loyalty.giftCard.applyTitle')}
            </p>
            <GiftCardField
              session={session}
              locale={locale}
              card={giftCard}
              onApply={(card, redeem) => {
                setGiftCard(card)
                setGiftCardRedeemMinor(redeem)
              }}
              onClear={() => {
                setGiftCard(null)
                setGiftCardRedeemMinor(0)
              }}
              redeemMinor={giftCardRedeemMinor}
              onRedeemChange={setGiftCardRedeemMinor}
              dueBeforeCardMinor={grandTotalMinor}
            />
          </div>
        )}

        {offline ? (
          <CashPanel
            config={config}
            session={session}
            lines={lines}
            chargeMinor={residualDueMinor}
            discountMinor={discountMinor}
            couponCode={null}
            loyaltyMemberId={loyaltyMemberId}
            loyaltyRedeemPoints={0}
            giftCardId={null}
            giftCardRedeemMinor={0}
            currency={currency}
            locale={locale}
            bay={bay}
            vehiclePlate={vehiclePlate}
            staffProfileId={staffProfileId}
            onSuccess={onSuccess}
            offline
            offlineProvisional={offlineProvisional}
            onOfflineSuccess={onOfflineSuccess}
          />
        ) : fullyCoveredByGiftCard ? (
          <FullCoveragePanel
            config={config}
            session={session}
            lines={lines}
            discountMinor={discountMinor}
            couponCode={couponCode}
            loyaltyMemberId={loyaltyMemberId}
            loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
            giftCardId={giftCard!.id}
            giftCardRedeemMinor={giftCardRedeemMinor}
            currency={currency}
            locale={locale}
            bay={bay}
            vehiclePlate={vehiclePlate}
            staffProfileId={staffProfileId}
            onSuccess={onSuccess}
          />
        ) : (
          <>
            <div className="flex justify-center px-5 py-4">
              <Segmented
                options={tenderOptions}
                value={tender}
                onChange={setTender}
                ariaLabel={t('pos.payment.selectTender')}
              />
            </div>

            {tender === 'CASH' ? (
              <CashPanel
                config={config}
                session={session}
                lines={lines}
                chargeMinor={residualDueMinor}
                discountMinor={discountMinor}
                couponCode={couponCode}
                loyaltyMemberId={loyaltyMemberId}
                loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
                giftCardId={giftCard?.id ?? null}
                giftCardRedeemMinor={giftCardRedeemMinor}
                currency={currency}
                locale={locale}
                bay={bay}
                vehiclePlate={vehiclePlate}
                staffProfileId={staffProfileId}
                onSuccess={onSuccess}
              />
            ) : (
              <DigitalPanel
                config={config}
                session={session}
                lines={lines}
                chargeMinor={residualDueMinor}
                discountMinor={discountMinor}
                couponCode={couponCode}
                loyaltyMemberId={loyaltyMemberId}
                loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
                giftCardId={giftCard?.id ?? null}
                giftCardRedeemMinor={giftCardRedeemMinor}
                currency={currency}
                locale={locale}
                bay={bay}
                vehiclePlate={vehiclePlate}
                staffProfileId={staffProfileId}
                tenderType={tender}
                onSuccess={onSuccess}
                onClose={onClose}
              />
            )}
          </>
        )}
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inline breakdown — identical shape/copy to PaymentModal's ModalBreakdown (re-implemented, not
// imported: PaymentModal's internal components are not exported).
// ---------------------------------------------------------------------------

function ModalBreakdown({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    return (
      <div className="flex items-baseline justify-between px-5 py-3 text-sm text-ink-3 border-b border-line">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="border-b border-line px-5 py-3 space-y-1.5 text-sm">
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {breakdown.loyaltyRedeemedMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.loyalty.redeemedLabel')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>

      <AppliedPromotionChips
        promotions={breakdown.appliedPromotions}
        currency={currency}
        locale={locale}
        className="pt-1"
      />
    </div>
  )
}

function InlineEstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Full-coverage panel — the gift card covers the ENTIRE residual (Phase 4, ADR 0027). See
// features/pos/PaymentModal.tsx's FullCoveragePanel doc for the wire-shape decision — identical
// here, and ServicePaymentModal's payment field is REQUIRED on the wire anyway (unlike
// restaurant's optional one), so sending a zero-amount CASH payment is the only valid shape.
// ---------------------------------------------------------------------------

interface FullCoveragePanelProps {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string
  giftCardRedeemMinor: number
  currency: string
  locale: string
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  onSuccess: (ticket: TicketResponse) => void
}

function FullCoveragePanel({
  config,
  session,
  lines,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  bay,
  vehiclePlate,
  staffProfileId,
  onSuccess,
}: FullCoveragePanelProps) {
  const { t } = useTranslation()
  const checkout = useTicketCheckout(config, session)
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())

  function complete() {
    checkout.mutate(
      {
        idempotencyKey,
        bay,
        vehiclePlate: vehiclePlate || null,
        staffProfileId,
        discountMinor,
        lines,
        payment: { tenderType: 'CASH', tenderedMinor: 0 },
        couponCode,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
      },
      { onSuccess: (res) => res && onSuccess(res) },
    )
  }

  return (
    <div className="px-5 pb-5">
      <div className="mb-4 flex items-center gap-2.5 rounded-xl border border-emerald-line bg-emerald-tint px-4 py-3">
        <Gift className="size-5 shrink-0 text-emerald-2" aria-hidden />
        <div>
          <p className="text-sm font-bold text-emerald-2">{t('pos.loyalty.giftCard.fullyCovered')}</p>
          <p className="tnum mt-0.5 font-mono text-xs text-emerald-2/80">
            {formatMoney(giftCardRedeemMinor, currency, locale)}
          </p>
        </div>
      </div>

      {checkout.isError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {(() => {
            const key = checkoutErrorKey(checkout.error)
            return key ? t(key) : (checkout.error as Error).message
          })()}
        </p>
      ) : null}

      <Button className="w-full" disabled={checkout.isPending} onClick={complete}>
        {checkout.isPending ? <Spinner /> : t('pos.loyalty.giftCard.completeOrder')}
      </Button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Cash panel — one-shot checkout with the payment block attached
// ---------------------------------------------------------------------------

interface CashPanelProps {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  /** The amount to actually authorize — residualDueMinor (grandTotal minus any gift-card tender). */
  chargeMinor: number
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  currency: string
  locale: string
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  onSuccess: (ticket: TicketResponse) => void
  /** Phase 5 (ADR 0028): true when the terminal is offline — Pay enqueues instead of POSTing. */
  offline?: boolean
  offlineProvisional?: ProvisionalTotals | null
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}

function CashPanel({
  config,
  session,
  lines,
  chargeMinor,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  bay,
  vehiclePlate,
  staffProfileId,
  onSuccess,
  offline = false,
  offlineProvisional,
  onOfflineSuccess,
}: CashPanelProps) {
  const { t } = useTranslation()
  const checkout = useTicketCheckout(config, session)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const [offlineBusy, setOfflineBusy] = useState(false)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries — a retry after
  // an ambiguous failure replays the same key and resolves to the same ticket (review W1).
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())

  const [keyStr, setKeyStr] = useState<string>('')
  const tenderedMinor = keyStr === '' ? 0 : parseInt(keyStr, 10)
  const changeMinor = tenderedMinor - chargeMinor
  const canPay =
    tenderedMinor >= chargeMinor &&
    !checkout.isPending &&
    !offlineBusy &&
    (!offline || offlineProvisional != null)

  const chips = quickChips(chargeMinor, currency)

  function pressDigit(d: string) {
    if (keyStr === '0') return
    setKeyStr((s) => (s.length >= 12 ? s : s + d))
  }
  function pressBackspace() {
    setKeyStr((s) => s.slice(0, -1))
  }
  function pressClear() {
    setKeyStr('')
  }
  function setChip(minor: number) {
    setKeyStr(String(minor))
  }

  /** Offline replacement for checkout.mutate — see features/pos/PaymentModal.tsx's CashPanel twin
   * doc. `loyaltyMemberId` alone is forwarded (earn attribution allowed offline). */
  async function payOffline() {
    if (!canPay || !offlineProvisional) return
    setOfflineBusy(true)
    setOfflineError(null)
    try {
      const row = await enqueueSale({
        vertical: config.vertical as 'carwash' | 'barbershop',
        endpoint: `${config.apiBase}/tickets/checkout`,
        companyId: session.companyId,
        actor: session.actor,
        body: {
          businessId: session.businessId,
          lines,
          [config.location.fieldName]: bay.trim() ? bay : null,
          ...(config.vehicleField ? { vehiclePlate: vehiclePlate || null } : {}),
          staffProfileId: staffProfileId || null,
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
          payment: { tenderType: 'CASH', tenderedMinor },
          loyaltyMemberId: loyaltyMemberId || null,
        },
        provisional: offlineProvisional,
      })
      onOfflineSuccess?.(row, tenderedMinor, changeMinor)
    } catch (err) {
      setOfflineError(err instanceof Error ? err.message : String(err))
    } finally {
      setOfflineBusy(false)
    }
  }

  function pay() {
    if (!canPay) return
    if (offline) {
      void payOffline()
      return
    }
    checkout.mutate(
      {
        idempotencyKey,
        bay,
        vehiclePlate: vehiclePlate || null,
        staffProfileId,
        discountMinor,
        lines,
        payment: { tenderType: 'CASH', tenderedMinor },
        couponCode,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
      },
      {
        onSuccess: (res) => {
          if (res) onSuccess(res)
        },
      },
    )
  }

  return (
    <div className="px-5 pb-5">
      <div className="mb-3">
        <p className="mb-1.5 text-xs text-ink-3">{t('pos.payment.quickCash')}</p>
        <div className="flex flex-wrap gap-2">
          {chips.map((chip, i) => (
            <button
              key={chip}
              type="button"
              onClick={() => setChip(chip)}
              className={[
                'rounded-xl border px-3 py-1.5 text-xs font-semibold transition-colors',
                tenderedMinor === chip
                  ? 'border-brand-500 bg-brand-50 text-brand-700'
                  : 'border-line bg-surface text-ink-2 hover:bg-hover',
              ].join(' ')}
            >
              {i === 0 ? t('pos.payment.exactAmount') : formatMoney(chip, currency, locale)}
            </button>
          ))}
        </div>
      </div>

      <div className="mb-3 rounded-lg border border-line bg-paper px-4 py-3 text-right">
        <p className="text-xs text-ink-3">{t('pos.payment.tendered')}</p>
        <p className="tnum font-mono text-2xl font-medium text-ink">
          {tenderedMinor > 0 ? formatMoney(tenderedMinor, currency, locale) : '—'}
        </p>
      </div>

      <div className="mb-3 grid grid-cols-3 gap-1.5">
        {['7', '8', '9', '4', '5', '6', '1', '2', '3'].map((d) => (
          <KeypadButton key={d} label={d} onClick={() => pressDigit(d)} />
        ))}
        <KeypadButton label="C" onClick={pressClear} />
        <KeypadButton label="0" onClick={() => pressDigit('0')} />
        <KeypadButton label="⌫" onClick={pressBackspace} />
      </div>

      <div className="mb-4 flex items-baseline justify-between rounded-xl bg-tint-profit px-4 py-2.5">
        <span className="text-sm font-semibold text-brand-700">{t('pos.payment.change')}</span>
        <span className="tnum font-mono text-lg font-bold text-brand-700">
          {changeMinor >= 0 ? formatMoney(changeMinor, currency, locale) : '—'}
        </span>
      </div>

      {offlineError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {offlineError}
        </p>
      ) : null}

      {!offline && checkout.isError ? (
        <p className="mb-3 text-xs text-loss">
          {(() => {
            const key = checkoutErrorKey(checkout.error)
            return key ? t(key) : (checkout.error as Error).message
          })()}
        </p>
      ) : null}

      {!canPay && tenderedMinor > 0 && tenderedMinor < chargeMinor ? (
        <p className="mb-3 text-xs text-amber-2">{t('pos.payment.insufficientTendered')}</p>
      ) : null}

      <Button className="w-full" disabled={!canPay} onClick={pay}>
        {checkout.isPending || offlineBusy ? (
          <Spinner />
        ) : (
          t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })
        )}
      </Button>
    </div>
  )
}

function KeypadButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="tnum flex h-11 items-center justify-center rounded-xl border border-line bg-surface font-mono text-base font-semibold text-ink transition-colors hover:bg-hover active:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {label}
    </button>
  )
}

// ---------------------------------------------------------------------------
// Digital panel (QRIS / Card) — two-step: checkout → PENDING → capture
// ---------------------------------------------------------------------------

interface DigitalPanelProps {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  /** The amount to actually authorize — residualDueMinor (grandTotal minus any gift-card tender). */
  chargeMinor: number
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  currency: string
  locale: string
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  tenderType: 'QRIS' | 'CARD'
  onSuccess: (ticket: TicketResponse) => void
  onClose: () => void
}

function DigitalPanel({
  config,
  session,
  lines,
  chargeMinor,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  bay,
  vehiclePlate,
  staffProfileId,
  tenderType,
  onSuccess,
  onClose,
}: DigitalPanelProps) {
  const { t } = useTranslation()
  const checkout = useTicketCheckout(config, session)
  const capture = useTicketCapture(config, session)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W1).
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())

  // After initiation we hold the PENDING ticket to drive the "Mark as paid" step.
  const [pendingTicket, setPendingTicket] = useState<TicketResponse | null>(null)

  function initiatePayment() {
    checkout.mutate(
      {
        idempotencyKey,
        bay,
        vehiclePlate: vehiclePlate || null,
        staffProfileId,
        discountMinor,
        lines,
        payment: { tenderType },
        couponCode,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
      },
      {
        onSuccess: (res) => {
          if (res?.payment) setPendingTicket(res)
        },
      },
    )
  }

  function confirmPayment() {
    if (!pendingTicket) return
    capture.mutate(pendingTicket.ticketId, {
      onSuccess: (captured) => {
        if (captured) onSuccess(captured)
      },
    })
  }

  if (!pendingTicket) {
    return (
      <div className="px-5 pb-5">
        <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
          <Badge tone="amber" className="mb-2">
            {t('pos.payment.providerPendingBadge')}
          </Badge>
          <p className="mt-1 leading-relaxed">{t('pos.payment.pendingHint')}</p>
        </div>

        {checkout.isError ? (
          <p className="mb-3 text-xs text-loss">
            {(() => {
              const key = checkoutErrorKey(checkout.error)
              return key ? t(key) : (checkout.error as Error).message
            })()}
          </p>
        ) : null}

        <Button className="w-full" disabled={checkout.isPending} onClick={initiatePayment}>
          {checkout.isPending ? (
            <Spinner />
          ) : (
            t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })
          )}
        </Button>
      </div>
    )
  }

  const pendingAmount = pendingTicket.payment?.amountMinor ?? chargeMinor

  return (
    <div className="px-5 pb-5">
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3">
        <div className="flex items-center gap-2">
          <Badge tone="amber">{t('pos.payment.providerPendingBadge')}</Badge>
        </div>
        <p className="mt-2 text-sm leading-relaxed text-amber-2">{t('pos.payment.pendingHint')}</p>
      </div>

      <div className="mb-4 flex items-baseline justify-between rounded-lg border border-line bg-paper px-4 py-3">
        <span className="text-sm text-ink-3">{t('pos.payment.pending')}</span>
        <span className="tnum font-mono text-lg font-medium text-ink">
          {formatMoney(pendingAmount, currency, locale)}
        </span>
      </div>

      {capture.isError ? (
        <p className="mb-3 text-xs text-loss">{t('pos.payment.errorCapture')}</p>
      ) : null}

      <Button className="w-full" disabled={capture.isPending} onClick={confirmPayment}>
        {capture.isPending ? <Spinner /> : t('pos.payment.markAsPaid')}
      </Button>

      <Button
        variant="ghost"
        className="mt-2 w-full text-xs"
        disabled={capture.isPending}
        onClick={onClose}
      >
        {t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
