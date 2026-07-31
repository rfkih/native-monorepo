/**
 * App.tsx — the whole self-order mini app: ~2 screens (menu + confirmation), mobile-first.
 *
 * Screen 1 (menu): items grouped by category, qty steppers, a sticky cart bar with the running
 * total and a "send order" action.
 * Screen 2 (confirmed): "Pesanan terkirim — silakan bayar di kasir" + the order's table label + a
 * "pesan lagi" reset back to screen 1.
 *
 * Error handling: missing `?t=` token, an invalid/rotated token (401/403 on the menu read), a full
 * unconfirmed-order queue (429 on submit), and generic network failures each get their own
 * friendly, full-screen message — never a blank page or a raw error string.
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  deriveCategories,
  fetchMenu,
  setToken,
  submitOrder,
  tokenTableLabel,
  SelfOrderApiError,
  type SelfOrderCategory,
  type SelfOrderMenuItem,
} from './api'
import { createT, type Lang } from './i18n'
import { formatMoney } from './money'

// Bilingual copy is id-default (ADR 0029) — a shared table/kiosk device, not a per-user login, so
// there is no language TOGGLE here (unlike the console's own per-outlet display toggle).
const LANG: Lang = 'id'
const INTL_LOCALE = LANG === 'id' ? 'id-ID' : 'en-US'
const t = createT(LANG)

type Screen = 'loading' | 'tokenMissing' | 'tokenInvalid' | 'networkError' | 'menu' | 'confirmed'

function readTokenFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get('t')
}

export function App() {
  const token = useMemo(readTokenFromUrl, [])
  const [screen, setScreen] = useState<Screen>('loading')
  const [categories, setCategories] = useState<SelfOrderCategory[]>([])
  const [items, setItems] = useState<SelfOrderMenuItem[]>([])
  const [cart, setCart] = useState<Record<string, number>>({})
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<'queueFull' | 'other' | null>(null)
  const [confirmation, setConfirmation] = useState<{ orderId: string; tableLabel: string | null } | null>(null)

  useEffect(() => {
    if (!token) {
      setScreen('tokenMissing')
      return
    }
    setToken(token)
    void loadMenu()
    // Runs once per mount — `token` is stable for the tab's lifetime (read once from the URL).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  async function loadMenu() {
    setScreen('loading')
    try {
      const menuItems = await fetchMenu()
      setCategories(deriveCategories(menuItems))
      setItems(menuItems)
      setScreen('menu')
    } catch (err) {
      if (err instanceof SelfOrderApiError && (err.status === 401 || err.status === 403)) {
        setScreen('tokenInvalid')
      } else {
        setScreen('networkError')
      }
    }
  }

  function setQty(itemId: string, qty: number) {
    setCart((prev) => {
      const next = { ...prev }
      if (qty <= 0) delete next[itemId]
      else next[itemId] = qty
      return next
    })
  }

  const cartLines = Object.entries(cart)
  const cartCount = cartLines.reduce((sum, [, qty]) => sum + qty, 0)
  const currency = items[0]?.currency ?? 'IDR'
  const cartTotalMinor = cartLines.reduce((sum, [itemId, qty]) => {
    const item = items.find((i) => i.id === itemId)
    return sum + (item ? item.priceMinor * qty : 0)
  }, 0)

  async function handleSubmit() {
    if (cartLines.length === 0 || submitting) return
    setSubmitting(true)
    setSubmitError(null)
    try {
      const res = await submitOrder(cartLines.map(([menuItemId, qty]) => ({ menuItemId, qty })))
      // The table label is presentational and comes from the token's public payload half — the
      // server binds the REAL label from its verified access row regardless.
      setConfirmation({ orderId: res.orderId, tableLabel: tokenTableLabel() })
      setCart({})
      setScreen('confirmed')
    } catch (err) {
      if (err instanceof SelfOrderApiError && err.status === 429) {
        setSubmitError('queueFull')
      } else {
        setSubmitError('other')
      }
    } finally {
      setSubmitting(false)
    }
  }

  function handleOrderAgain() {
    setConfirmation(null)
    setSubmitError(null)
    setScreen('menu')
    void loadMenu()
  }

  if (screen === 'loading') return <CenteredMessage text={t('loading')} />
  if (screen === 'tokenMissing') return <CenteredMessage text={t('tokenMissing')} />
  if (screen === 'tokenInvalid') return <CenteredMessage text={t('tokenInvalid')} />
  if (screen === 'networkError') {
    return (
      <CenteredMessage text={t('networkError')}>
        <RetryButton onClick={() => void loadMenu()} />
      </CenteredMessage>
    )
  }
  if (screen === 'confirmed') {
    return <ConfirmationScreen confirmation={confirmation} onOrderAgain={handleOrderAgain} />
  }

  return (
    <MenuScreen
      categories={categories}
      items={items}
      cart={cart}
      currency={currency}
      cartCount={cartCount}
      cartTotalMinor={cartTotalMinor}
      submitting={submitting}
      submitError={submitError}
      onSetQty={setQty}
      onSubmit={() => void handleSubmit()}
    />
  )
}

// ---------------------------------------------------------------------------
// CenteredMessage — full-screen friendly state (missing/invalid token, network error, loading)
// ---------------------------------------------------------------------------

function CenteredMessage({ text, children }: { text: string; children?: ReactNode }) {
  return (
    <div className="grid min-h-dvh place-items-center bg-paper px-6 text-center">
      <div className="flex max-w-sm flex-col items-center gap-4">
        <p className="text-lg font-medium text-ink-2">{text}</p>
        {children}
      </div>
    </div>
  )
}

function RetryButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-xl bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {t('retry')}
    </button>
  )
}

// ---------------------------------------------------------------------------
// MenuScreen
// ---------------------------------------------------------------------------

interface MenuScreenProps {
  categories: SelfOrderCategory[]
  items: SelfOrderMenuItem[]
  cart: Record<string, number>
  currency: string
  cartCount: number
  cartTotalMinor: number
  submitting: boolean
  submitError: 'queueFull' | 'other' | null
  onSetQty: (itemId: string, qty: number) => void
  onSubmit: () => void
}

function MenuScreen({
  categories,
  items,
  cart,
  currency,
  cartCount,
  cartTotalMinor,
  submitting,
  submitError,
  onSetQty,
  onSubmit,
}: MenuScreenProps) {
  const groups = useMemo(() => groupByCategory(categories, items), [categories, items])

  return (
    <div className="flex min-h-dvh flex-col bg-paper pb-28">
      <header className="sticky top-0 z-10 border-b border-line bg-white/90 px-4 py-4 backdrop-blur">
        <h1 className="font-display text-lg font-bold text-ink">{t('yourOrder')}</h1>
      </header>

      <main className="flex-1 px-4 py-4">
        {items.length === 0 ? (
          <p className="py-16 text-center text-sm text-ink-3">{t('emptyMenu')}</p>
        ) : (
          <div className="space-y-6">
            {groups.map((group) => (
              <section key={group.key} aria-label={group.name}>
                <h2 className="mb-2 text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  {group.name}
                </h2>
                <ul className="space-y-2">
                  {group.items.map((item) => (
                    <MenuRow
                      key={item.id}
                      item={item}
                      qty={cart[item.id] ?? 0}
                      onSetQty={(qty) => onSetQty(item.id, qty)}
                    />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </main>

      {cartCount > 0 ? (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-white px-4 py-3 shadow-[0_-8px_24px_rgba(15,23,42,0.10)]">
          {submitError ? (
            <p className="mb-2 text-xs font-medium text-rose-600" role="alert">
              {submitError === 'queueFull' ? t('queueFull') : t('submitError')}
            </p>
          ) : null}
          <button
            type="button"
            onClick={onSubmit}
            disabled={submitting}
            className="flex w-full items-center justify-between rounded-xl bg-brand-600 px-5 py-3.5 text-white shadow-sm transition-colors hover:bg-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:opacity-60"
          >
            <span className="text-sm font-semibold">
              {submitting ? t('sending') : `${t('sendOrder')} · ${t('cartCount', { n: cartCount })}`}
            </span>
            <span className="tnum font-mono text-base font-bold">
              {formatMoney(cartTotalMinor, currency, INTL_LOCALE)}
            </span>
          </button>
        </div>
      ) : (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-white px-4 py-3 text-center text-xs text-ink-3">
          {t('cartEmptyHint')}
        </div>
      )}
    </div>
  )
}

function MenuRow({
  item,
  qty,
  onSetQty,
}: {
  item: SelfOrderMenuItem
  qty: number
  onSetQty: (qty: number) => void
}) {
  const soldOut = !item.available || (item.stockQuantity != null && item.stockQuantity <= 0)

  return (
    <li
      className={`flex items-center gap-3 rounded-xl border border-line bg-white px-3 py-3 ${soldOut ? 'opacity-50' : ''}`}
    >
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
        <p className="tnum mt-0.5 font-mono text-sm text-ink-3">
          {soldOut ? t('soldOut') : formatMoney(item.priceMinor, item.currency, INTL_LOCALE)}
        </p>
      </div>

      {soldOut ? null : qty > 0 ? (
        <div className="flex shrink-0 items-center gap-2">
          <StepperButton label="−" onClick={() => onSetQty(qty - 1)} />
          <span className="tnum w-5 text-center text-sm font-bold text-ink">{qty}</span>
          <StepperButton label="+" onClick={() => onSetQty(qty + 1)} />
        </div>
      ) : (
        <button
          type="button"
          onClick={() => onSetQty(1)}
          className="shrink-0 rounded-lg border border-brand-200 bg-brand-50 px-3 py-1.5 text-xs font-bold text-brand-700 transition-colors hover:bg-brand-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {t('add')}
        </button>
      )}
    </li>
  )
}

function StepperButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label === '+' ? 'plus' : 'minus'}
      className="grid size-7 shrink-0 place-items-center rounded-full border border-line bg-white text-sm font-bold text-ink transition-colors hover:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {label}
    </button>
  )
}

// ---------------------------------------------------------------------------
// ConfirmationScreen
// ---------------------------------------------------------------------------

function ConfirmationScreen({
  confirmation,
  onOrderAgain,
}: {
  confirmation: { orderId: string; tableLabel: string | null } | null
  onOrderAgain: () => void
}) {
  return (
    <div className="grid min-h-dvh place-items-center bg-paper px-6 text-center">
      <div className="flex max-w-sm flex-col items-center gap-4">
        <span className="grid size-16 place-items-center rounded-full bg-brand-50 text-3xl">✓</span>
        <h1 className="font-display text-xl font-bold text-ink">{t('confirmedTitle')}</h1>
        {confirmation?.tableLabel ? (
          <p className="text-sm text-ink-3">{t('confirmedTable', { label: confirmation.tableLabel })}</p>
        ) : null}
        <button
          type="button"
          onClick={onOrderAgain}
          className="mt-2 rounded-xl border border-line bg-white px-5 py-2.5 text-sm font-semibold text-ink transition-colors hover:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {t('orderAgain')}
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

interface MenuGroup {
  key: string
  name: string
  items: SelfOrderMenuItem[]
}

function groupByCategory(categories: SelfOrderCategory[], items: SelfOrderMenuItem[]): MenuGroup[] {
  const byCategory = new Map<string, SelfOrderMenuItem[]>()
  const other: SelfOrderMenuItem[] = []

  for (const item of items) {
    if (item.categoryId && categories.some((c) => c.id === item.categoryId)) {
      const list = byCategory.get(item.categoryId) ?? []
      list.push(item)
      byCategory.set(item.categoryId, list)
    } else {
      other.push(item)
    }
  }

  const groups: MenuGroup[] = [...categories]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((c) => ({ key: c.id, name: c.name, items: byCategory.get(c.id) ?? [] }))
    .filter((g) => g.items.length > 0)

  if (other.length > 0) {
    groups.push({ key: '__other__', name: t('otherCategory'), items: other })
  }

  return groups
}
