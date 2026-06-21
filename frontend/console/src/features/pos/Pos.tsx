import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Minus, Plus, Trash2, Utensils, Info } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import {
  useMenu,
  useCategories,
  useSeedMenu,
  useQuote,
  type MenuItem,
  type CategoryResponse,
  type OrderResponse,
  type PaymentResponse,
  type PriceBreakdownResponse,
  type OrderLineInput,
} from './api'
import { PaymentModal } from './PaymentModal'
import { ReceiptView } from './ReceiptView'
import { ModifierModal } from './ModifierModal'

// ---------------------------------------------------------------------------
// Cart line — extends OrderLineInput with the display info we need client-side
// ---------------------------------------------------------------------------

interface CartLine {
  menuItemId: string
  qty: number
  selectedOptionIds: string[]
  /** Effective unit price (base + Σ modifier deltas) in minor units. */
  effectiveUnitPriceMinor: number
  /** Human-readable names of selected options — shown under the item in the cart. */
  selectedOptionNames: string[]
}

export function Pos() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  return <PosInner session={company} />
}

function PosInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const menuQuery = useMenu(session)
  const categoriesQuery = useCategories(session)
  const seed = useSeedMenu(session)

  // Cart: list of CartLine (order preserved, same item may appear multiple times with diff modifiers
  // but typically a single line per menuItemId with qty; for simplicity we merge same-option configs).
  const [cart, setCart] = useState<CartLine[]>([])

  // discountInput is the raw string the user types; discountMinor is the parsed integer.
  const [discountInput, setDiscountInput] = useState<string>('')
  const [discountError, setDiscountError] = useState<string | null>(null)

  // Modal state
  const [modal, setModal] = useState<'payment' | 'receipt' | null>(null)
  const [modifierItem, setModifierItem] = useState<MenuItem | null>(null)
  const [placedOrder, setPlacedOrder] = useState<OrderResponse | null>(null)
  const [placedPayment, setPlacedPayment] = useState<PaymentResponse | null>(null)

  // Active category tab — null means "all" / first available
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null)

  const items = menuQuery.data ?? []
  const categories = (categoriesQuery.data ?? []).filter((c) => c.active)

  const currency = items[0]?.currency ?? session.baseCurrency

  // Parse discount: the input is in major units (e.g. "5000" IDR or "5.00" USD).
  const discountMinor = parseDiscountInput(discountInput, currency)

  // Build API-shaped lines for quote/checkout
  const cartLines: OrderLineInput[] = cart.map(({ menuItemId, qty, selectedOptionIds }) => ({
    menuItemId,
    qty,
    selectedOptionIds,
  }))

  const lineCount = cart.reduce((sum, l) => sum + l.qty, 0)

  // Live price quote from the server
  const quoteQuery = useQuote(session, cartLines, discountMinor)
  const breakdown = quoteQuery.data ?? null

  // Authoritative grand total: use server breakdown when available, else fall back to
  // client-side subtotal (before the first quote returns).
  const clientSubtotalMinor = cart.reduce(
    (sum, l) => sum + l.effectiveUnitPriceMinor * l.qty,
    0,
  )
  const grandTotalMinor = breakdown?.grandTotalMinor ?? clientSubtotalMinor

  // ---------------------------------------------------------------------------
  // Category grouping
  // ---------------------------------------------------------------------------

  // Derive the ordered category list: prefer backend categories, fall back to item.category strings.
  const orderedCategories = deriveCategories(items, categories)

  // Selected category tab value — default to first available
  const resolvedCategoryId: string = activeCategoryId ?? orderedCategories[0]?.id ?? ''

  // Segment options for the category bar
  const categoryOptions = orderedCategories.map((c) => ({ value: c.id, label: c.name }))

  // Filter items by active category
  const visibleItems = items.filter((item) => {
    if (orderedCategories.length === 0) return true
    const cat = orderedCategories.find((c) => c.id === resolvedCategoryId)
    if (!cat) return true
    // Match by categoryId (UUID) or by category string (legacy)
    if (item.categoryId) return item.categoryId === resolvedCategoryId
    return item.category === cat.legacyKey
  })

  // ---------------------------------------------------------------------------
  // Cart manipulation
  // ---------------------------------------------------------------------------

  function handleItemTap(item: MenuItem) {
    // If the item has modifier groups, open the picker first.
    if (item.modifierGroups.length > 0) {
      setModifierItem(item)
      return
    }
    // No modifiers — add directly with base price.
    addToCart(item.id, [], item.priceMinor, [])
  }

  function addToCart(
    menuItemId: string,
    selectedOptionIds: string[],
    effectiveUnitPriceMinor: number,
    selectedOptionNames: string[],
  ) {
    setCart((prev) => {
      // Find a matching line (same item + same option selection)
      const key = lineKey(menuItemId, selectedOptionIds)
      const idx = prev.findIndex((l) => lineKey(l.menuItemId, l.selectedOptionIds) === key)
      if (idx !== -1) {
        const next = [...prev]
        next[idx] = { ...next[idx], qty: next[idx].qty + 1 }
        return next
      }
      return [
        ...prev,
        { menuItemId, qty: 1, selectedOptionIds, effectiveUnitPriceMinor, selectedOptionNames },
      ]
    })
  }

  function handleModifierConfirm(selectedOptionIds: string[], effectivePriceMinor: number) {
    if (!modifierItem) return
    // Collect names of selected options for the cart display
    const names = modifierItem.modifierGroups
      .flatMap((g) => g.options)
      .filter((o) => selectedOptionIds.includes(o.id))
      .map((o) => o.name)
    addToCart(modifierItem.id, selectedOptionIds, effectivePriceMinor, names)
    setModifierItem(null)
  }

  function decCartLine(index: number) {
    setCart((prev) => {
      const next = [...prev]
      const line = next[index]
      if (line.qty <= 1) {
        next.splice(index, 1)
      } else {
        next[index] = { ...line, qty: line.qty - 1 }
      }
      return next
    })
  }

  // ---------------------------------------------------------------------------
  // Discount
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Payment
  // ---------------------------------------------------------------------------

  function openPayment() {
    setModal('payment')
  }

  function handlePaymentSuccess(order: OrderResponse, payment: PaymentResponse) {
    setPlacedOrder(order)
    setPlacedPayment(payment)
    setCart([])
    setDiscountInput('')
    setDiscountError(null)
    setModal('receipt')
  }

  function handleNewOrder() {
    setPlacedOrder(null)
    setPlacedPayment(null)
    setModal(null)
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

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
          <div>
            {/* Category tab bar — shown when there are backend categories */}
            {categoryOptions.length > 1 ? (
              <div className="mb-5 overflow-x-auto">
                <Segmented
                  options={categoryOptions}
                  value={resolvedCategoryId}
                  onChange={(v) => setActiveCategoryId(v)}
                  ariaLabel={t('pos.categories')}
                />
              </div>
            ) : null}

            {/* Item grid */}
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {visibleItems.map((item) => (
                <ItemCard
                  key={item.id}
                  item={item}
                  qty={cartQtyFor(cart, item.id)}
                  locale={locale}
                  onAdd={() => handleItemTap(item)}
                />
              ))}
            </div>
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
                {cart.map((line, idx) => {
                  const item = items.find((i) => i.id === line.menuItemId)
                  if (!item) return null
                  return (
                    <li key={`${line.menuItemId}-${idx}`} className="px-5 py-3">
                      <div className="flex items-center gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-medium text-ink">{item.name}</div>
                          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
                            {formatMoney(line.effectiveUnitPriceMinor, item.currency, locale)}
                          </div>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            aria-label={t('pos.decreaseQty', { name: item.name })}
                            onClick={() => decCartLine(idx)}
                            className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                          >
                            {line.qty === 1 ? <Trash2 className="size-3.5" /> : <Minus className="size-3.5" />}
                          </button>
                          <span className="tnum w-5 text-center font-mono text-sm text-ink">{line.qty}</span>
                          <button
                            type="button"
                            aria-label={t('pos.increaseQty', { name: item.name })}
                            onClick={() => handleItemTap(item)}
                            className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                          >
                            <Plus className="size-3.5" />
                          </button>
                        </div>
                      </div>
                      {/* Modifier names under the line */}
                      {line.selectedOptionNames.length > 0 ? (
                        <div className="mt-1 pl-0 flex flex-wrap gap-1">
                          {line.selectedOptionNames.map((name) => (
                            <span
                              key={name}
                              className="text-[11px] text-ink-3 leading-tight"
                            >
                              {name}
                            </span>
                          ))}
                        </div>
                      ) : null}
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

      {/* Modifier picker modal */}
      {modifierItem ? (
        <ModifierModal
          item={modifierItem}
          locale={locale}
          onConfirm={handleModifierConfirm}
          onClose={() => setModifierItem(null)}
        />
      ) : null}

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
      <div className="flex items-baseline justify-between">
        <span className="text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.subtotalMinor, currency, locale)}
        </span>
      </div>

      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between">
          <span className="text-ink-3">{t('pos.discount')}</span>
          <span className="tnum font-mono text-rose">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.serviceCharge')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.tax')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>

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
  const unavailable = !item.available

  return (
    <button
      type="button"
      onClick={unavailable ? undefined : onAdd}
      disabled={unavailable}
      aria-label={
        unavailable
          ? t('pos.soldOutLabel', { name: item.name })
          : t('pos.addItem', { name: item.name })
      }
      aria-disabled={unavailable}
      className={cn(
        'relative flex flex-col items-start rounded-card border bg-surface p-4 text-left transition-all',
        unavailable
          ? 'cursor-not-allowed opacity-50'
          : [
              'hover:-translate-y-0.5 hover:border-emerald/40 hover:shadow-[0_10px_30px_-18px_rgba(13,106,74,0.5)]',
              qty > 0 ? 'border-emerald ring-1 ring-emerald/20' : 'border-line',
            ],
        !unavailable && qty > 0 ? 'border-emerald ring-1 ring-emerald/20' : !unavailable ? 'border-line' : 'border-line',
      )}
    >
      {!unavailable && qty > 0 ? (
        <span className="tnum absolute right-3 top-3 grid size-6 place-items-center rounded-full bg-emerald font-mono text-xs font-medium text-white">
          {qty}
        </span>
      ) : null}

      {unavailable ? (
        <span className="absolute right-3 top-3">
          <Badge tone="neutral" className="text-[10px] px-1.5 py-0">
            {t('pos.soldOut')}
          </Badge>
        </span>
      ) : null}

      <span className={cn('font-medium', unavailable ? 'text-ink-3' : 'text-ink')}>{item.name}</span>
      <span className={cn('tnum mt-2 font-mono text-sm', unavailable ? 'text-ink-3/50' : 'text-emerald-2')}>
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>

      {item.modifierGroups.length > 0 && !unavailable ? (
        <span className="mt-1.5 text-[11px] text-ink-3">{t('pos.hasOptions')}</span>
      ) : null}
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function parseDiscountInput(input: string, currency: string): number {
  if (!input || input.trim() === '') return 0
  const major = Number(input)
  if (isNaN(major) || major < 0) return 0
  const exp = isoMinorExponent(currency)
  return Math.round(major * 10 ** exp)
}

/**
 * A virtual category row: may be a real backend category (with a UUID id) or a synthetic
 * fallback built from the item.category string (legacyKey).
 */
interface VirtualCategory {
  id: string
  name: string
  /** The raw category string key used by legacy items not yet linked to a backend category. */
  legacyKey: string
}

/**
 * Derive an ordered list of virtual categories for the tab bar.
 *
 * Priority:
 * 1. Active backend categories ordered by displayOrder.
 * 2. Fallback: unique item.category strings for items not covered by any backend category,
 *    appended after backend categories.
 */
function deriveCategories(
  items: MenuItem[],
  backendCategories: CategoryResponse[],
): VirtualCategory[] {
  const result: VirtualCategory[] = backendCategories.map((c) => ({
    id: c.id,
    name: c.name,
    legacyKey: c.name.toLowerCase(),
  }))

  // Find items whose categoryId doesn't match any backend category
  const backendIds = new Set(backendCategories.map((c) => c.id))
  const legacyKeys = new Set<string>()
  for (const item of items) {
    if (!item.categoryId || !backendIds.has(item.categoryId)) {
      if (item.category && !legacyKeys.has(item.category)) {
        legacyKeys.add(item.category)
        // Check if a backend category already covers this key
        const covered = backendCategories.some(
          (c) => c.name.toLowerCase() === item.category.toLowerCase(),
        )
        if (!covered) {
          result.push({ id: item.category, name: item.category, legacyKey: item.category })
        }
      }
    }
  }

  return result
}

/**
 * Sum qty across all cart lines for a given menuItemId (supports multiple lines with
 * different modifier selections for the same item).
 */
function cartQtyFor(cart: CartLine[], menuItemId: string): number {
  return cart.filter((l) => l.menuItemId === menuItemId).reduce((s, l) => s + l.qty, 0)
}

/**
 * A stable string key for a cart line — used to find existing lines when adding.
 * Items with identical menuItemId + sorted selectedOptionIds are merged.
 */
function lineKey(menuItemId: string, selectedOptionIds: string[]): string {
  return `${menuItemId}::${[...selectedOptionIds].sort().join(',')}`
}
