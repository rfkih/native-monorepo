/**
 * GiftCardSellModal — sells (mints) a gift card at the till (Phase 4, ADR 0027). A distinct POS
 * action (opened from a header button), NOT a cart line — the vertical's checkout/ticket contract
 * is untouched by this flow. Shared by BOTH POS surfaces (restaurant Pos.tsx, service-POS
 * ServicePos.tsx) — lives in components/, config-driven by `vertical` exactly like
 * features/loyalty/api.ts's `useSellGiftCard`.
 *
 * Two steps:
 *   'form'    — amount entry (a digit keypad building up integer minor units, the exact CashPanel
 *               convention from features/pos/PaymentModal.tsx) + a tender picker (record-only; a
 *               gift-card sale always captures immediately — GiftCardSaleWriter.java has no
 *               pending/provider step, unlike a checkout payment).
 *   'success' — the minted card's PRINTABLE code, large mono + a copy affordance, plus an optional
 *               thermal-style print (ThermalReceipt is reused — "cheap" per the task's framing).
 *
 * Money rule (rule 8): amountMinor is an integer minor unit; formatMoney() for every display.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Gift, Printer, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import type { Vertical } from '@/features/org/api'
import { useSellGiftCard, type GiftCardSaleResponse } from '@/features/loyalty/api'
import { ThermalReceipt } from '@/features/pos/ThermalReceipt'

interface Props {
  vertical: Vertical
  session: CompanySession
  currency: string
  locale: string
  onClose: () => void
}

type TenderTab = 'CASH' | 'QRIS' | 'CARD'

export function GiftCardSellModal({ vertical, session, currency, locale, onClose }: Props) {
  const { t } = useTranslation()
  // Covers every render branch below (form / success / print) — one mounted instance, one onClose
  // target throughout the flow.
  useBackDismiss(onClose)
  const sell = useSellGiftCard(vertical, session)

  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())
  const [tender, setTender] = useState<TenderTab>('CASH')
  const [amountStr, setAmountStr] = useState('')
  const [result, setResult] = useState<GiftCardSaleResponse | null>(null)
  const [showPrint, setShowPrint] = useState(false)
  const [copied, setCopied] = useState(false)

  const amountMinor = amountStr === '' ? 0 : parseInt(amountStr, 10)
  const canSell = amountMinor > 0 && !sell.isPending

  const tenderOptions: { value: TenderTab; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]

  function pressDigit(d: string) {
    if (amountStr === '0') return
    setAmountStr((s) => (s.length >= 12 ? s : s + d))
  }
  function pressBackspace() {
    setAmountStr((s) => s.slice(0, -1))
  }
  function pressClear() {
    setAmountStr('')
  }

  function submit() {
    if (!canSell) return
    sell.mutate(
      { idempotencyKey, amountMinor, currency, tenderType: tender },
      { onSuccess: (res) => res && setResult(res) },
    )
  }

  async function copyCode() {
    if (!result) return
    try {
      await navigator.clipboard.writeText(result.code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch {
      /* clipboard unavailable — the code is still selectable/visible on screen */
    }
  }

  if (result) {
    if (showPrint) {
      return (
        <ThermalReceipt
          businessName={session.name}
          title={t('pos.loyalty.giftCard.sellReceiptTitle')}
          reference={result.code}
          dateTime={new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
            new Date(result.occurredAt),
          )}
          metaRows={[]}
          lineItems={[
            {
              qty: 1,
              name: t('pos.loyalty.giftCard.cardValue'),
              priceLabel: formatMoney(result.amountMinor, result.currency, locale),
              modifiers: [],
            },
          ]}
          totalRows={[]}
          grandTotalLabel={formatMoney(result.amountMinor, result.currency, locale)}
          paymentRows={
            result.tenderType
              ? [{ label: t('pos.receipt.tender'), valueLabel: t(tenderKey(result.tenderType)) }]
              : []
          }
          footerNote={t('pos.receipt.thankYou')}
          onPrint={() => window.print()}
          onAction={onClose}
          actionLabel={t('common.close')}
        />
      )
    }

    return (
      <div
        className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('pos.loyalty.giftCard.sellSuccessTitle')}
      >
        <Card className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain p-6 text-center">
          <div className="mx-auto mb-3 grid size-12 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
            <Gift className="size-6" aria-hidden />
          </div>
          <h2 className="font-display text-lg font-bold text-ink">{t('pos.loyalty.giftCard.sellSuccessTitle')}</h2>
          <p className="mt-1 text-sm text-ink-3">
            {formatMoney(result.amountMinor, result.currency, locale)}
          </p>

          <div className="mt-5 rounded-2xl border-[1.5px] border-dashed border-emerald-line bg-emerald-tint/50 px-4 py-5">
            <p className="mb-2 text-[11px] font-bold uppercase tracking-[0.08em] text-emerald-2">
              {t('pos.loyalty.giftCard.printableCode')}
            </p>
            <p className="tnum select-all break-all font-mono text-[22px] font-bold tracking-[0.08em] text-ink">
              {result.code}
            </p>
          </div>

          <div className="mt-4 flex gap-2">
            <Button variant="outline" className="flex-1" onClick={copyCode}>
              {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
              {copied ? t('common.copied') : t('common.copy')}
            </Button>
            <Button variant="outline" className="flex-1" onClick={() => setShowPrint(true)}>
              <Printer className="size-4" />
              {t('pos.receipt.print')}
            </Button>
          </div>

          <Button className="mt-2 w-full" onClick={onClose}>
            {t('common.done')}
          </Button>
        </Card>
      </div>
    )
  }

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.loyalty.giftCard.sellTitle')}
    >
      <Card className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain">
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="font-display text-lg font-semibold text-ink">{t('pos.loyalty.giftCard.sellTitle')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="px-5 pt-4">
          <div className="mb-3 rounded-lg border border-line bg-paper px-4 py-3 text-right">
            <p className="text-xs text-ink-3">{t('pos.loyalty.giftCard.amountToLoad')}</p>
            <p className="tnum font-mono text-2xl font-medium text-ink">
              {amountMinor > 0 ? formatMoney(amountMinor, currency, locale) : '—'}
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

          <div className="mb-4 flex justify-center">
            <Segmented
              options={tenderOptions}
              value={tender}
              onChange={setTender}
              ariaLabel={t('pos.payment.selectTender')}
            />
          </div>
        </div>

        <div className="px-5 pb-5">
          {sell.isError ? (
            <p className="mb-3 text-xs text-loss" role="alert">
              {t('pos.loyalty.giftCard.sellError')}
            </p>
          ) : null}
          <Button className="w-full" disabled={!canSell} onClick={submit}>
            {sell.isPending ? <Spinner /> : t('pos.loyalty.giftCard.sellAction')}
          </Button>
        </div>
      </Card>
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

function tenderKey(tenderType: string): string {
  switch (tenderType) {
    case 'CASH':
      return 'pos.payment.tenderCash'
    case 'QRIS':
      return 'pos.payment.tenderQris'
    case 'CARD':
      return 'pos.payment.tenderCard'
    default:
      return tenderType
  }
}
