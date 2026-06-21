import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Minus, Plus, Trash2, Utensils, Info } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import {
  useMenu,
  useSeedMenu,
  useQuote,
  type MenuItem,
  type OrderResponse,
  type PaymentResponse,
  type PriceBreakdownResponse,
} from './api'
import { PaymentModal } from './PaymentModal'
import { ReceiptView } from './ReceiptView'

const CATEGORY_ORDER = ['mains', 'drinks', 'desserts']

export function Pos() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  return <PosInner session={company} />
}

function PosInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const menuQuery = useMenu(session)
  const seed = useSeedMenu(session)

  const [cart, setCart] = useState<Record<string, number>>({})
  // discountInput is the raw string the user types; discountMinor is the parsed integer.
  const [discountInput, setDiscountInput] = useState<string>('')
  const [discountError, setDiscountError] = useState<string | null>(null)

  // Modal state: null = no modal, 'payment' = payment modal, 'receipt' = receipt overlay
  const [modal, setModal] = useState<'payment' | 'receipt' | null>(null)
  const [placedOrder, setPlacedOrder] = useState<OrderResponse | null>(null)
  const [placedPayment, setPlacedPayment] = useState<PaymentResponse | null>(null)

  const items = menuQuery.data ?? []
  const byId = new Map(items.map((i) => [i.id, i]))
  const currency = items[0]?.currency ?? session.baseCurrency

  // Parse discount: the input is in major units (e.g. "5000" IDR or "5.00" USD).
  // Convert to minor units for the API. For IDR exponent=0 means major=minor.
  const discountMinor = parseDiscountInput(discountInput, currency)

  const cartLines = Object.entries(cart)
    .filter(([, qty]) => qty > 0)
    .map(([menuItemId, qty]) => ({ menuItemId, qty }))

  const lineCount = Object.values(cart).reduce((a, b) => a + b, 0)

  // Live price quote from the server
  const quoteQuery = useQuote(session, cartLines, discountMinor)
  const breakdown = quoteQuery.data ?? null

  // Authoritative grand total: use server breakdown when available, else fall back to
  // client-side subtotal (before the first quote returns).
  const clientSubtotalMinor = cartLines.reduce(
    (sum, { menuItemId, qty }) => sum + (byId.get(menuItemId)?.priceMinor ?? 0) * qty,
    0,
  )
  const grandTotalMinor = breakdown?.grandTotalMinor ?? clientSubtotalMinor

  function add(id: string) {
    setCart((c) => ({ ...c, [id]: (c[id] ?? 0) + 1 }))
  }
  function dec(id: string) {
    setCart((c) => {
      const next = { ...c }
      const q = (next[id] ?? 0) - 1
      if (q <= 0) delete next[id]
      else next[id] = q
      return next
    })
  }

  function handleDiscountChange(raw: string) {
    setDiscountInput(raw)
    if (raw === '' || raw === '0') {
      setDiscountError(null)
      return
    }
    const parsed = Number(raw)
    if (isNaN(parsed) || parsed < 0) {
      setDiscountError(t('pos.discountInvalid'))
    } else {
      setDiscountError(null)
    }
  }

  function openPayment() {
    setModal('payment')
  }

  function handlePaymentSuccess(order: OrderResponse, payment: PaymentResponse) {
    setPlacedOrder(order)
    setPlacedPayment(payment)
    setCart({})
    setDiscountInput('')
    setDiscountError(null)
    setModal('receipt')
  }

  function handleNewOrder() {
    setPlacedOrder(null)
    setPlacedPayment(null)
    setModal(null)
  }

  const grouped = groupByCategory(items)

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
      {/* Menu */}
      <div>
        <header className="mb-5">
          <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
            {t('pos.title')}
          </h1>
          <p className="mt-1 text-sm text-ink-3">{t('pos.subtitle', { name: session.name })}</p>
        </header>

        {menuQuery.isLoading ? (
          <div className="grid place-items-center py-24 text-emerald">
            <Spinner />
          </div>
        ) : items.length === 0 ? (
          <Card className="p-10 text-center">
            <div className="mx-auto grid size-12 place-items-center rounded-full bg-emerald-tint text-emerald">
              <Utensils className="size-6" />
            </div>
            <h2 className="mt-4 font-display text-xl font-semibold text-ink">{t('pos.emptyMenu')}</h2>
            <p className="mx-auto mt-1.5 max-w-xs text-sm text-ink-3">{t('pos.emptyMenuHint')}</p>
            <Button className="mt-5" onClick={() => seed.mutate()} disabled={seed.isPending}>
              {seed.isPending ? <Spinner /> : null} {t('pos.loadSample')}
            </Button>
          </Card>
        ) : (
          <div className="space-y-7">
            {grouped.map(([category, group]) => (
              <section key={category}>
                <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-ink-3">
                  {t(`pos.category.${category}`, category)}
                </h2>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                  {group.map((item) => (
                    <ItemCard
                      key={item.id}
                      item={item}
                      qty={cart[item.id] ?? 0}
                      locale={locale}
                      onAdd={() => add(item.id)}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>
        )}
      </div>

      {/* Cart rail */}
      <div className="lg:sticky lg:top-24 lg:self-start">
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
            <h2 className="font-display text-lg font-semibold text-ink">{t('pos.cart')}</h2>
            {lineCount > 0 ? <Badge tone="emerald">{lineCount}</Badge> : null}
          </div>

          {lineCount === 0 ? (
            <p className="px-5 py-10 text-center text-sm text-ink-3">{t('pos.cartEmpty')}</p>
          ) : (
            <>
              {/* Line items */}
              <ul className="divide-y divide-line">
                {Object.entries(cart).map(([id, qty]) => {
                  const item = byId.get(id)
                  if (!item) return null
                  return (
                    <li key={id} className="flex items-center gap-3 px-5 py-3">
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-ink">{item.name}</div>
                        <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
                          {formatMoney(item.priceMinor, item.currency, locale)}
                        </div>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <button
                          type="button"
                          aria-label={t('pos.decreaseQty', { name: item.name })}
                          onClick={() => dec(id)}
                          className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                        >
                          {qty === 1 ? <Trash2 className="size-3.5" /> : <Minus className="size-3.5" />}
                        </button>
                        <span className="tnum w-5 text-center font-mono text-sm text-ink">{qty}</span>
                        <button
                          type="button"
                          aria-label={t('pos.increaseQty', { name: item.name })}
                          onClick={() => add(id)}
                          className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                        >
                          <Plus className="size-3.5" />
                        </button>
                      </div>
                    </li>
                  )
                })}
              </ul>

              {/* Discount input */}
              <div className="border-t border-line px-5 py-3">
                <label htmlFor="pos-discount" className="block text-xs font-medium text-ink-3 mb-1.5">
                  {t('pos.addDiscount')}
                </label>
                <input
                  id="pos-discount"
                  type="number"
                  inputMode="decimal"
                  min="0"
                  step="1"
                  value={discountInput}
                  onChange={(e) => handleDiscountChange(e.target.value)}
                  placeholder="0"
                  aria-describedby={discountError ? 'pos-discount-error' : undefined}
                  className={cn(
                    'w-full rounded-lg border bg-surface px-3 py-2 font-mono text-sm text-ink tnum',
                    'transition-colors placeholder:text-ink-3/50',
                    'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10',
                    discountError ? 'border-rose' : 'border-line-strong',
                  )}
                />
                {discountError ? (
                  <p id="pos-discount-error" className="mt-1 text-xs text-rose" role="alert">
                    {discountError}
                  </p>
                ) : null}
              </div>

              {/* Price breakdown */}
              <div className="border-t border-line px-5 py-4">
                <PriceBreakdown
                  breakdown={breakdown}
                  isLoading={quoteQuery.isFetching && !quoteQuery.isPlaceholderData}
                  currency={currency}
                  locale={locale}
                  clientSubtotalMinor={clientSubtotalMinor}
                />
              </div>
            </>
          )}

          <div className={cn('px-5 py-4', lineCount > 0 ? 'border-t border-line' : '')}>
            <Button
              className="w-full"
              disabled={lineCount === 0 || !!discountError}
              onClick={openPayment}
            >
              {t('pos.charge')} · {formatMoney(grandTotalMinor, currency, locale)}
            </Button>
          </div>
        </Card>
      </div>

      {/* Payment modal */}
      {modal === 'payment' ? (
        <PaymentModal
          session={session}
          lines={cartLines}
          breakdown={breakdown}
          grandTotalMinor={grandTotalMinor}
          discountMinor={discountMinor}
          currency={currency}
          locale={locale}
          onSuccess={handlePaymentSuccess}
          onClose={() => setModal(null)}
        />
      ) : null}

      {/* Receipt overlay */}
      {modal === 'receipt' && placedOrder && placedPayment ? (
        <ReceiptView
          order={placedOrder}
          payment={placedPayment}
          locale={locale}
          onNew={handleNewOrder}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// PriceBreakdown sub-component
// ---------------------------------------------------------------------------

function PriceBreakdown({
  breakdown,
  isLoading,
  currency,
  locale,
  clientSubtotalMinor,
}: {
  breakdown: PriceBreakdownResponse | null
  isLoading: boolean
  currency: string
  locale: string
  clientSubtotalMinor: number
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    // Show a simple subtotal row while the first quote is in flight
    return (
      <div className="flex items-baseline justify-between">
        <span className="text-sm text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-sm text-ink">
          {isLoading ? (
            <span className="inline-block h-3.5 w-16 animate-pulse rounded bg-paper" />
          ) : (
            formatMoney(clientSubtotalMinor, currency, locale)
          )}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="space-y-2 text-sm">
      {/* Subtotal */}
      <div className="flex items-baseline justify-between">
        <span className="text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.subtotalMinor, currency, locale)}
        </span>
      </div>

      {/* Discount — only shown when > 0 */}
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between">
          <span className="text-ink-3">{t('pos.discount')}</span>
          <span className="tnum font-mono text-rose">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Service charge */}
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.serviceCharge')}
          {illustrative ? (
            <EstimatedBadge hint={t('pos.illustrativeHint')} />
          ) : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>

      {/* Tax */}
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.tax')}
          {illustrative ? (
            <EstimatedBadge hint={t('pos.illustrativeHint')} />
          ) : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>

      {/* Grand total */}
      <div className="flex items-baseline justify-between border-t border-line pt-2 mt-1">
        <span className="font-medium text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {isLoading ? (
            <span className="inline-block h-5 w-20 animate-pulse rounded bg-paper" />
          ) : (
            formatMoney(breakdown.grandTotalMinor, currency, locale)
          )}
        </span>
      </div>
    </div>
  )
}

/** Small amber "Estimated" badge with a title tooltip for the illustrative rates hint. */
function EstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint} className="inline-flex items-center gap-0.5">
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
      <Info className="size-3 text-amber-2/70" aria-hidden="true" />
    </span>
  )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Parse the discount field: the user types in MAJOR units (e.g. "5000" IDR = Rp 5.000).
 * We convert to minor units for the API. Returns 0 for empty / invalid input.
 */
function parseDiscountInput(input: string, currency: string): number {
  if (!input || input.trim() === '') return 0
  const major = Number(input)
  if (isNaN(major) || major < 0) return 0
  const exp = isoMinorExponent(currency)
  return Math.round(major * 10 ** exp)
}

// ---------------------------------------------------------------------------
// Item card
// ---------------------------------------------------------------------------

function ItemCard({
  item,
  qty,
  locale,
  onAdd,
}: {
  item: MenuItem
  qty: number
  locale: string
  onAdd: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onAdd}
      aria-label={t('pos.addItem', { name: item.name })}
      className={cn(
        'relative flex flex-col items-start rounded-card border bg-surface p-4 text-left transition-all',
        'hover:-translate-y-0.5 hover:border-emerald/40 hover:shadow-[0_10px_30px_-18px_rgba(13,106,74,0.5)]',
        qty > 0 ? 'border-emerald ring-1 ring-emerald/20' : 'border-line',
      )}
    >
      {qty > 0 ? (
        <span className="tnum absolute right-3 top-3 grid size-6 place-items-center rounded-full bg-emerald font-mono text-xs font-medium text-white">
          {qty}
        </span>
      ) : null}
      <span className="font-medium text-ink">{item.name}</span>
      <span className="tnum mt-2 font-mono text-sm text-emerald-2">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
    </button>
  )
}

function NoCompany() {
  const { t } = useTranslation()
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
      <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
      <Link
        to="/onboarding"
        className="mt-5 inline-block rounded-lg bg-emerald px-4 py-2.5 text-sm font-medium text-white hover:bg-emerald-2"
      >
        {t('nav.onboarding')}
      </Link>
    </Card>
  )
}

function groupByCategory(items: MenuItem[]): [string, MenuItem[]][] {
  const map = new Map<string, MenuItem[]>()
  for (const item of items) {
    const list = map.get(item.category) ?? []
    list.push(item)
    map.set(item.category, list)
  }
  const order = (c: string) => {
    const i = CATEGORY_ORDER.indexOf(c)
    return i === -1 ? CATEGORY_ORDER.length : i
  }
  return [...map.entries()].sort((a, b) => order(a[0]) - order(b[0]))
}
