import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Check, Minus, Plus, Trash2, Utensils } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { useCheckout, useMenu, useSeedMenu, type MenuItem, type OrderResponse } from './api'

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
  const checkout = useCheckout(session)
  const seed = useSeedMenu(session)

  const [cart, setCart] = useState<Record<string, number>>({})
  const [placed, setPlaced] = useState<OrderResponse | null>(null)

  const items = menuQuery.data ?? []
  const byId = new Map(items.map((i) => [i.id, i]))
  const currency = items[0]?.currency ?? session.baseCurrency
  const totalMinor = Object.entries(cart).reduce(
    (sum, [id, qty]) => sum + (byId.get(id)?.priceMinor ?? 0) * qty,
    0,
  )
  const lineCount = Object.values(cart).reduce((a, b) => a + b, 0)

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
  function charge() {
    const lines = Object.entries(cart).map(([menuItemId, qty]) => ({ menuItemId, qty }))
    checkout.mutate(lines, {
      onSuccess: (res) => {
        if (res) {
          setPlaced(res)
          setCart({})
        }
      },
    })
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
                        aria-label="−"
                        onClick={() => dec(id)}
                        className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                      >
                        {qty === 1 ? <Trash2 className="size-3.5" /> : <Minus className="size-3.5" />}
                      </button>
                      <span className="tnum w-5 text-center font-mono text-sm text-ink">{qty}</span>
                      <button
                        type="button"
                        aria-label="+"
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
          )}

          <div className="border-t border-line px-5 py-4">
            <div className="flex items-baseline justify-between">
              <span className="text-sm text-ink-3">{t('pos.total')}</span>
              <span className="tnum font-mono text-2xl font-medium text-ink">
                {formatMoney(totalMinor, currency, locale)}
              </span>
            </div>
            {checkout.isError ? (
              <p className="mt-2 text-xs text-rose">{(checkout.error as Error).message}</p>
            ) : null}
            <Button
              className="mt-4 w-full"
              disabled={lineCount === 0 || checkout.isPending}
              onClick={charge}
            >
              {checkout.isPending ? (
                <Spinner />
              ) : (
                <>
                  {t('pos.charge')} · {formatMoney(totalMinor, currency, locale)}
                </>
              )}
            </Button>
          </div>
        </Card>
      </div>

      {placed ? (
        <ChargeSuccess order={placed} locale={locale} onNew={() => setPlaced(null)} />
      ) : null}
    </div>
  )
}

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
  return (
    <button
      type="button"
      onClick={onAdd}
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

function ChargeSuccess({
  order,
  locale,
  onNew,
}: {
  order: OrderResponse
  locale: string
  onNew: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-ink/30 p-5 backdrop-blur-sm">
      <Card className="reveal w-full max-w-sm p-8 text-center">
        <div className="mx-auto grid size-14 place-items-center rounded-full bg-emerald text-white">
          <Check className="size-7" />
        </div>
        <h2 className="mt-4 font-display text-2xl font-semibold text-ink">{t('pos.paid')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('pos.paidHint')}</p>
        <div className="tnum mt-5 font-mono text-3xl font-medium text-ink">
          {formatMoney(order.totalMinor, order.currency, locale)}
        </div>
        <Button className="mt-6 w-full" onClick={onNew}>
          {t('pos.newOrder')}
        </Button>
      </Card>
    </div>
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
