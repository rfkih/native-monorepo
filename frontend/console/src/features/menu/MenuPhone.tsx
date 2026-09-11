/**
 * MenuPhone — the phone menu WORK page (ADR 0083, "Manajemen Menu" design), rendered by
 * MenuManagement.tsx below the 640px cutoff. Not a catalog: everything an owner does to one item —
 * name, price, availability, recipe, duplicate, delete — finishes on that item's own row. Only one
 * row is open at a time; the panel it opens is the item's whole editor.
 *
 * HPP is never typed. A recipe line is an ingredient from the catalog and a quantity; its cost is
 * qty × the ingredient's moving-average unit cost (ADR 0056), so the figure moves the moment a line
 * is added or removed. Price and availability PATCH on commit (blur / Enter / switch) — there is
 * no save button, and the screen says so.
 *
 * Grey-only, on purpose: the one colour is the red on "Hapus item". Low stock is a heavier weight
 * in the meta line, not a badge. Draws its own chrome (`/menu` is outside the Shell and the tab bar
 * does not mount here): one `ScreenHeader`, one document scroll, overlays through the shared
 * `DialogOverlay` (ADR 0075 N2/N3). Search and chip live in the URL (`?q=`, `?cat=`, replace — N5).
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useQueries } from '@tanstack/react-query'
import {
  ChevronDown,
  Coffee,
  Cookie,
  CupSoda,
  IceCreamCone,
  Plus,
  Salad,
  Search,
  Soup,
  Star,
  Utensils,
  UtensilsCrossed,
  X,
  type LucideIcon,
} from 'lucide-react'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { OutletPicker } from '@/components/OutletPicker'
import { Button } from '@/components/ui/Button'
import { DialogOverlay } from '@/components/ui/Dialog'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatPercent, isoMinorExponent, minorToMajor } from '@/lib/money'
import { useMenu, type MenuItem } from '@/features/pos/api'
import {
  CATEGORY_TEMPLATE_KEYS,
  canonicalCategoryKey,
  displayCategoryName,
} from '@/features/pos/lib/categoryCanon'
import { useIngredients, type Ingredient } from '@/features/inventory/ingredientApi'
import { IngredientPickerSheet } from '@/features/inventory/IngredientPickerSheet'
import { QtyKeypad } from '@/features/inventory/QtyKeypad'
import {
  applyCountKey,
  countDraftWithinCap,
  decimalSeparatorOf,
  type CountKey,
} from '@/features/inventory/lib/countKeypad'
import { use86Item, useCreateMenuItem, useDeleteItem, useUn86Item, useUpdateMenuItem } from './api'
import {
  fetchRecipe,
  recipeKey,
  usePutRecipe,
  useRecipe,
  useHppSummary,
  type RecipeLine,
} from './recipeApi'
import {
  ALL_CHIP,
  categoryChips,
  cloneLines,
  filterItems,
  lineCostMinor,
  marginOf,
  parseMajorPrice,
  recipeTotal,
  stockMeta,
  summary,
  withLine,
  withoutLine,
} from './lib/menuView'
import { safeBottom } from '@/lib/safeArea'

/** Glyph per category template (`categoryCanon` keys); anything custom gets the plain fork. */
const GLYPHS: Record<string, LucideIcon> = {
  'tpl:appetizers': Salad,
  'tpl:mains': UtensilsCrossed,
  'tpl:sides': Soup,
  'tpl:desserts': IceCreamCone,
  'tpl:beverages': CupSoda,
  'tpl:coffeeTea': Coffee,
  'tpl:snacks': Cookie,
  'tpl:specials': Star,
}

const FIELD_LABEL = 'block text-2xs font-semibold uppercase tracking-eyebrow text-ink-3'
const FIELD_CARD = 'block rounded-xl border border-line bg-surface px-3 py-2.5'
const PANEL_BUTTON =
  'h-11 flex-1 rounded-xl border border-line bg-surface text-sm font-semibold transition-[background-color,border-color,transform] duration-150 active:scale-[0.98] motion-reduce:active:scale-100 disabled:opacity-50'
const ICON_BUTTON =
  'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

/** Fixed bottom surfaces bypass the body's safe-area padding (index.css) — each pads itself. */
const SAFE_BOTTOM = safeBottom // lib/safeArea — the one definition of the nav-bar inset rule

function problemDetail(err: unknown): string | null {
  return err instanceof ApiError ? (err.problem?.detail ?? null) : null
}

export function MenuPhone({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const menuQuery = useMenu(session)
  const hppQuery = useHppSummary(session)
  const ingredientsQuery = useIngredients(session)
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const chip = params.get('cat') ?? ALL_CHIP
  const [openId, setOpenId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  // Set by the create sheet on success and applied in its onClose — the sheet's parked Back entry
  // must be unwound (unmount) before the list navigates (`replace`), or the entry is orphaned.
  const createdRef = useRef<string | null>(null)

  const setParam = (key: string, value: string) => {
    const sp = new URLSearchParams(params)
    if (value === '' || (key === 'cat' && value === ALL_CHIP)) sp.delete(key)
    else sp.set(key, value)
    setParams(sp, { replace: true })
  }

  const items = useMemo(() => menuQuery.data ?? [], [menuQuery.data])
  const ingredients = ingredientsQuery.data ?? []
  const hppById = useMemo(
    () => new Map((hppQuery.data ?? []).map((r) => [r.menuItemId, r])),
    [hppQuery.data],
  )
  const chips = categoryChips(
    items,
    canonicalCategoryKey,
    (n) => displayCategoryName(n, t),
    t('menu.uncategorized'),
    locale,
  )

  // "Cari item atau bahan": the query also matches ingredient names, which live in each item's
  // recipe — fetched lazily, only while there is a query worth matching, keyed like `useRecipe`
  // so an open row and the search share one cache entry per item.
  const searching = q.trim().length >= 2
  const recipes = useQueries({
    queries: items.map((it) => ({
      queryKey: recipeKey(session, it.id),
      queryFn: () => fetchRecipe(session, it.id),
      enabled: searching,
      staleTime: 300_000,
    })),
    combine: (results) => results.map((r) => r.data ?? null),
  })
  const ingredientNamesById = useMemo(() => {
    const map = new Map<string, string[]>()
    items.forEach((it, i) => {
      const lines = recipes[i]?.lines ?? []
      map.set(
        it.id,
        lines.map((l) => l.ingredientName),
      )
    })
    return map
  }, [items, recipes])

  const visible = filterItems(items, chip, q, ingredientNamesById, canonicalCategoryKey, locale)
  const sum = summary(items, hppById)

  return (
    <div className="min-h-[100dvh] bg-paper">
      <ScreenHeader
        title={t('menu.title')}
        subtitle={
          <>
            <span className="truncate">{session.name}</span>
            <span aria-hidden="true">·</span>
            <OutletPicker variant="subtitle" />
          </>
        }
        backFallback="/pos"
        trailing={
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="mr-1 flex h-9 shrink-0 items-center gap-1.5 rounded-xl bg-emerald px-3 text-xs font-bold text-on-emerald shadow-lift transition-transform active:scale-[0.96] motion-reduce:active:scale-100"
          >
            <Plus className="size-[15px]" strokeWidth={2.2} aria-hidden="true" />
            {t('menu.phone.addItem')}
          </button>
        }
      />

      {/* Search + chips — in-flow under the one sticky header. */}
      <div className="border-b border-line bg-paper px-4 pt-2.5">
        <label className="flex h-[42px] items-center gap-2.5 rounded-xl bg-hover px-3.5">
          <Search className="size-4 shrink-0 text-ink-3" strokeWidth={2} aria-hidden="true" />
          <input
            type="search"
            value={q}
            onChange={(e) => setParam('q', e.target.value)}
            placeholder={t('menu.phone.searchPlaceholder')}
            aria-label={t('menu.phone.searchPlaceholder')}
            className="min-w-0 flex-1 bg-transparent text-sm font-medium text-ink placeholder:text-ink-400 focus:outline-none [&::-webkit-search-cancel-button]:appearance-none"
          />
          {q !== '' ? (
            <button
              type="button"
              onClick={() => setParam('q', '')}
              aria-label={t('mobile.more.clearSearch')}
              className="-mr-1.5 grid size-8 shrink-0 place-items-center rounded-full text-ink-3 hover:bg-line"
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          ) : null}
        </label>
        <div className="-mx-4 mt-2.5 flex gap-[7px] overflow-x-auto px-4 pb-[11px] [scrollbar-width:none]">
          {chips.map((c) => {
            const on = c.key === chip
            return (
              <button
                key={c.key}
                type="button"
                aria-pressed={on}
                onClick={() => {
                  setParam('cat', c.key)
                  setOpenId(null)
                }}
                className={cn(
                  'flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-[13px] text-xs font-semibold transition-colors',
                  on
                    ? 'border-emerald bg-emerald text-on-emerald'
                    : 'border-line bg-surface text-ink-3 hover:bg-hover',
                )}
              >
                {c.key === ALL_CHIP ? t('menu.phone.all') : c.label}
                <span className="tnum font-mono text-2xs font-semibold opacity-60">
                  {c.count}
                </span>
              </button>
            )
          })}
        </div>
      </div>

      <main className="mx-auto max-w-[640px] px-4 pb-8">
        {menuQuery.isLoading ? (
          <div className="mt-4 flex flex-col gap-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-[70px] animate-pulse rounded-2xl bg-ink-100" />
            ))}
          </div>
        ) : menuQuery.isError ? (
          <p className="mt-6 text-center text-sm text-loss" role="alert">
            {t('menu.loadError')}
          </p>
        ) : (
          <>
            <p className="pt-[13px] text-xs text-ink-3">
              {sum.avgMargin != null
                ? t('menu.phone.summary', {
                    active: sum.active,
                    soldOut: sum.soldOut,
                    margin: formatPercent(sum.avgMargin, locale),
                  })
                : t('menu.phone.summaryNoMargin', { active: sum.active, soldOut: sum.soldOut })}
            </p>
            <div className="mt-3 overflow-hidden rounded-2xl border border-line bg-surface">
              {visible.map((item) => (
                <ItemRow
                  key={item.id}
                  session={session}
                  item={item}
                  hpp={hppById.get(item.id)}
                  ingredients={ingredients}
                  locale={locale}
                  open={openId === item.id}
                  onToggle={() => setOpenId((cur) => (cur === item.id ? null : item.id))}
                  onDuplicated={(id) => setOpenId(id)}
                  onDeleted={() => setOpenId(null)}
                />
              ))}
              {visible.length === 0 ? (
                <p className="px-5 py-[34px] text-center text-sm font-medium text-ink-3">
                  {items.length === 0 ? t('menu.phone.empty') : t('menu.phone.noMatch')}
                </p>
              ) : null}
            </div>
          </>
        )}
      </main>

      {creating ? (
        <NewItemSheet
          session={session}
          items={items}
          locale={locale}
          onClose={() => {
            setCreating(false)
            const id = createdRef.current
            if (id == null) return
            createdRef.current = null
            // Clear the filters only when they would hide the new row (a no-op replace is
            // still a navigation).
            if (q !== '' || chip !== ALL_CHIP) {
              const sp = new URLSearchParams(params)
              sp.delete('q')
              sp.delete('cat')
              setParams(sp, { replace: true })
            }
            setOpenId(id)
          }}
          onCreated={(id) => {
            createdRef.current = id
          }}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// One row, and the panel it opens
// ---------------------------------------------------------------------------

function ItemRow({
  session,
  item,
  hpp,
  ingredients,
  locale,
  open,
  onToggle,
  onDuplicated,
  onDeleted,
}: {
  session: CompanySession
  item: MenuItem
  hpp: { unitHppMinor: number | null; hppCurrency: string | null } | undefined
  ingredients: Ingredient[]
  locale: string
  open: boolean
  onToggle: () => void
  onDuplicated: (id: string) => void
  onDeleted: () => void
}) {
  const { t } = useTranslation()
  const category = item.category.trim()
  const Glyph = GLYPHS[canonicalCategoryKey(category)] ?? Utensils
  const stock = stockMeta(item)
  const margin = marginOf(item, hpp)
  const stockText =
    stock.kind === 'untracked'
      ? t('menu.phone.stockUntracked')
      : stock.kind === 'zero'
        ? t('menu.phone.stockZero')
        : stock.kind === 'low'
          ? `${t('menu.phone.stockLeft', { count: stock.qty })} · ${t('menu.phone.low')}`
          : t('menu.phone.stockLeft', { count: stock.qty })
  const meta = [
    category === '' ? t('menu.uncategorized') : displayCategoryName(category, t),
    stockText,
  ]

  return (
    <div className="border-b border-line/60 last:border-b-0">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        aria-label={
          open
            ? t('menu.phone.collapse', { name: item.name })
            : t('menu.phone.expand', { name: item.name })
        }
        className={cn(
          'flex min-h-[70px] w-full items-center gap-3 px-3.5 py-[13px] text-left transition-colors hover:bg-paper',
          open ? 'bg-paper' : 'bg-surface',
        )}
      >
        <span className="grid size-[42px] shrink-0 place-items-center rounded-xl bg-hover text-ink-400">
          <Glyph className="size-[21px]" strokeWidth={1.8} aria-hidden="true" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-[7px]">
            <span className="min-w-0 truncate text-sm font-semibold leading-snug tracking-display text-ink">
              {item.name}
            </span>
            {!item.available ? (
              <span className="grid h-[18px] shrink-0 place-items-center rounded-full bg-emerald px-[7px] text-2xs font-bold text-on-emerald">
                {t('pos.soldOut')}
              </span>
            ) : null}
          </span>
          <span
            className={cn(
              'mt-1 block truncate text-xs leading-snug text-ink-3',
              stock.kind === 'low' || stock.kind === 'zero' ? 'font-semibold' : 'font-normal',
            )}
          >
            {meta.join(' · ')}
          </span>
        </span>
        <span className="shrink-0 text-right">
          <span className="tnum block font-mono text-sm font-semibold leading-none text-ink">
            {formatMoney(item.priceMinor, item.currency, locale)}
          </span>
          <span className="tnum mt-[5px] block font-mono text-2xs font-medium leading-none text-ink-3">
            {margin != null
              ? t('menu.phone.margin', { margin: formatPercent(margin, locale) })
              : t('menu.phone.marginNone')}
          </span>
        </span>
        <ChevronDown
          className={cn(
            'size-4 shrink-0 text-ink-300 transition-transform duration-200',
            open ? 'rotate-180' : 'rotate-0',
          )}
          strokeWidth={2}
          aria-hidden="true"
        />
      </button>
      {open ? (
        <ItemPanel
          session={session}
          item={item}
          ingredients={ingredients}
          locale={locale}
          onDuplicated={onDuplicated}
          onDeleted={onDeleted}
        />
      ) : null}
    </div>
  )
}

function ItemPanel({
  session,
  item,
  ingredients,
  locale,
  onDuplicated,
  onDeleted,
}: {
  session: CompanySession
  item: MenuItem
  ingredients: Ingredient[]
  locale: string
  onDuplicated: (id: string) => void
  onDeleted: () => void
}) {
  const { t } = useTranslation()
  const recipeQuery = useRecipe(session, item.id)
  // Two instances: TanStack only fires `mutate()` callbacks for the LATEST call on an instance, so
  // a name commit followed at once by a price commit would drop the name's onError.
  const updateName = useUpdateMenuItem(session)
  const updatePrice = useUpdateMenuItem(session)
  const mark86 = use86Item(session)
  const unMark86 = useUn86Item(session)
  const putRecipe = usePutRecipe(session)
  const create = useCreateMenuItem(session)
  const remove = useDeleteItem(session)

  const [picker, setPicker] = useState(false)
  const [qtyFor, setQtyFor] = useState<{ ingredient: Ingredient; baseQty: number | null } | null>(
    null,
  )
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fail = (err: unknown) => setError(problemDetail(err) ?? t('menu.phone.failed'))

  // The name and price fields are UNCONTROLLED and keyed on the server value: the field holds
  // the draft, a commit PATCHes, and when the server answers (or another device changes the item)
  // the field remounts with the new truth — no draft state to keep in step with props.
  const commitName = (el: HTMLInputElement) => {
    const next = el.value.trim()
    if (next === '' || next === item.name) {
      el.value = item.name
      return
    }
    setError(null)
    updateName.mutate({ itemId: item.id, name: next }, { onError: fail })
  }
  const commitPrice = (el: HTMLInputElement) => {
    const minor = parseMajorPrice(el.value, item.currency)
    if (minor == null || minor === item.priceMinor) {
      el.value = majorInput(item.priceMinor, item.currency)
      return
    }
    setError(null)
    updatePrice.mutate({ itemId: item.id, priceMinor: minor }, { onError: fail })
  }
  const toggleAvailable = () => {
    setError(null)
    if (item.available) mark86.mutate(item.id, { onError: fail })
    else unMark86.mutate(item.id, { onError: fail })
  }

  const lines: RecipeLine[] = recipeQuery.data?.lines ?? []
  const baseLines = lines.filter((l) => l.modifierOptionId == null)
  const total = recipeTotal(lines, item.currency)
  const ingredientById = useMemo(() => new Map(ingredients.map((i) => [i.id, i])), [ingredients])
  // Every recipe write is a FULL REPLACE built from `lines`, so nothing may write — or copy —
  // until the recipe has actually loaded: a PUT from an empty placeholder would erase it.
  const recipeReady = recipeQuery.isSuccess
  const busy = putRecipe.isPending || create.isPending || remove.isPending || !recipeReady

  const saveLines = (body: ReturnType<typeof withLine>, done?: () => void) => {
    if (!recipeReady) return
    setError(null)
    putRecipe.mutate(
      { itemId: item.id, lines: body },
      {
        onSuccess: () => done?.(),
        // (6) the sheet closes either way; the panel's error line is where the reason shows.
        onError: (err) => {
          done?.()
          fail(err)
        },
      },
    )
  }

  const duplicate = () => {
    if (!recipeReady) return
    setError(null)
    create.mutate(
      {
        name: `${item.name} ${t('menu.phone.copySuffix')}`,
        category: item.category,
        priceMinor: item.priceMinor,
        currency: item.currency,
        imageUrl: item.imageUrl ?? undefined,
        unitCostMinor: item.unitCostMinor,
        autoTrackStock: false,
      },
      {
        onSuccess: (created) => {
          if (!created) return
          const body = cloneLines(lines)
          if (body.length === 0) {
            onDuplicated(created.id)
            return
          }
          putRecipe.mutate(
            { itemId: created.id, lines: body },
            { onSettled: () => onDuplicated(created.id), onError: fail },
          )
        },
        onError: fail,
      },
    )
  }

  // A recipe quantity is an integer in the ingredient's BASE unit (g / ml / pcs) — the per-portion
  // amounts are small, so "200 g" reads, where the catalog's kg/liter display unit would not.
  const qtyText = (line: RecipeLine) =>
    `${new Intl.NumberFormat(locale).format(line.qtyPerPortion)} ${line.unit}`

  return (
    <div className="panel-in bg-paper px-3.5 pb-4 pt-0.5">
      <div className="pt-3">
        <label className={FIELD_CARD}>
          <span className={FIELD_LABEL}>{t('menu.createItem.nameLabel')}</span>
          <input
            type="text"
            key={item.name}
            defaultValue={item.name}
            onBlur={(e) => commitName(e.currentTarget)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
            }}
            placeholder={t('menu.createItem.namePlaceholder')}
            className="mt-1.5 w-full bg-transparent text-base font-semibold leading-tight tracking-display text-ink placeholder:text-ink-400 focus:outline-none"
          />
        </label>
      </div>

      <div className="flex gap-[9px] pt-[9px]">
        <label className={cn(FIELD_CARD, 'min-w-0 flex-1')}>
          <span className={FIELD_LABEL}>{t('menu.phone.priceLabel')}</span>
          <span className="mt-1.5 flex items-baseline gap-1.5">
            <span className="font-mono text-xs font-semibold text-ink-3">
              {currencySymbol(item.currency, locale)}
            </span>
            <input
              type="text"
              inputMode="decimal"
              key={item.priceMinor}
              defaultValue={majorInput(item.priceMinor, item.currency)}
              onBlur={(e) => commitPrice(e.currentTarget)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
              }}
              aria-label={t('menu.phone.priceLabel')}
              className="tnum min-w-0 flex-1 bg-transparent font-mono text-base font-semibold leading-tight text-ink focus:outline-none"
            />
          </span>
        </label>
        <div className={cn(FIELD_CARD, 'min-w-0 flex-1')}>
          <span className={FIELD_LABEL}>{t('menu.phone.hppLabel')}</span>
          {recipeQuery.isLoading ? (
            <span className="mt-1.5 block h-[18px] w-20 animate-pulse rounded-md bg-ink-100" />
          ) : (
            <span className="tnum mt-1.5 block truncate font-mono text-base font-semibold leading-tight text-ink">
              {total.minor != null
                ? formatMoney(total.minor, item.currency, locale)
                : t('menu.phone.costNone')}
            </span>
          )}
        </div>
      </div>

      <div className="mt-[9px] flex items-center gap-3 rounded-xl border border-line bg-surface p-3">
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-semibold leading-snug text-ink">
            {t('menu.phone.availableTitle')}
          </span>
          <span className="mt-0.5 block text-xs leading-snug text-ink-3">
            {item.available ? t('menu.phone.availableOn') : t('menu.phone.availableOff')}
          </span>
        </span>
        <button
          type="button"
          role="switch"
          aria-checked={item.available}
          aria-label={t('menu.phone.availableTitle')}
          disabled={mark86.isPending || unMark86.isPending}
          onClick={toggleAvailable}
          className={cn(
            'flex h-7 w-[46px] shrink-0 rounded-full p-[3px] transition-colors duration-200 disabled:opacity-60',
            item.available ? 'bg-emerald' : 'bg-line-strong',
          )}
        >
          <span
            className={cn(
              'size-[22px] rounded-full bg-white shadow-[0_1px_3px_rgba(15,23,42,.2)] transition-transform duration-200',
              item.available ? 'translate-x-[18px]' : 'translate-x-0',
            )}
          />
        </button>
      </div>

      {/* Recipe — the list the HPP is made of. */}
      <div className="mt-4 flex items-baseline justify-between gap-2.5 px-0.5">
        <span className={FIELD_LABEL}>
          {t('menu.phone.recipeHeading')} ·{' '}
          {t('menu.phone.ingredients', { count: baseLines.length })}
        </span>
        <span className="tnum font-mono text-2xs font-medium text-ink-3">
          {total.minor != null ? formatMoney(total.minor, item.currency, locale) : ''}
        </span>
      </div>
      <div className="mt-[7px] overflow-hidden rounded-xl border border-line bg-surface">
        {baseLines.map((line) => {
          const cost = lineCostMinor(line)
          const ing = ingredientById.get(line.ingredientId)
          return (
            <div
              key={line.id}
              className="flex min-h-[46px] items-center gap-2.5 border-b border-line/60 py-[9px] pl-3 pr-2"
            >
              <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink">
                {line.ingredientName}
              </span>
              <button
                type="button"
                disabled={!ing || busy}
                onClick={() => ing && setQtyFor({ ingredient: ing, baseQty: line.qtyPerPortion })}
                aria-label={t('menu.phone.qtyTitle')}
                className="tnum shrink-0 rounded-md px-1.5 py-1 font-mono text-xs font-medium text-ink-3 hover:bg-hover disabled:hover:bg-transparent"
              >
                {qtyText(line)}
              </button>
              <span className="tnum w-[76px] shrink-0 text-right font-mono text-xs font-semibold text-ink">
                {cost != null
                  ? formatMoney(cost, line.costCurrency ?? item.currency, locale)
                  : t('menu.phone.costNone')}
              </span>
              <button
                type="button"
                disabled={busy}
                onClick={() => saveLines(withoutLine(lines, line.ingredientId))}
                aria-label={t('recipe.removeLine')}
                className="grid size-[30px] shrink-0 place-items-center rounded-full text-ink-400 transition-colors hover:bg-hover hover:text-ink"
              >
                <X className="size-[14px]" strokeWidth={2.2} aria-hidden="true" />
              </button>
            </div>
          )
        })}
        <button
          type="button"
          disabled={busy}
          onClick={() => setPicker(true)}
          className="flex min-h-[46px] w-full items-center gap-2 px-3 py-[9px] text-left text-xs font-semibold text-ink transition-colors hover:bg-paper"
        >
          <Plus className="size-[15px]" strokeWidth={2.2} aria-hidden="true" />
          {t('recipe.addLine')}
        </button>
      </div>
      {total.partial ? (
        <p className="mt-1.5 px-0.5 text-2xs text-ink-3">{t('menu.phone.costPartial')}</p>
      ) : null}
      {recipeQuery.isError ? (
        <p className="mt-1.5 px-0.5 text-xs text-loss" role="alert">
          {t('recipe.loadError')}{' '}
          <button
            type="button"
            onClick={() => void recipeQuery.refetch()}
            className="font-semibold underline underline-offset-2"
          >
            {t('dashboardPhone.retry')}
          </button>
        </p>
      ) : null}

      <div className="mt-3.5 flex gap-[9px]">
        <button
          type="button"
          disabled={busy}
          onClick={duplicate}
          className={cn(PANEL_BUTTON, 'text-ink-2 hover:bg-hover')}
        >
          {create.isPending ? <Spinner /> : t('menu.phone.duplicate')}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => setConfirmDelete(true)}
          className={cn(PANEL_BUTTON, 'text-loss-ink hover:border-loss-line hover:bg-tint-loss')}
        >
          {t('menu.phone.deleteItem')}
        </button>
      </div>
      {error ? (
        <p className="mt-2.5 text-xs text-loss" role="alert">
          {error}
        </p>
      ) : null}
      <p className="mt-2.5 text-2xs leading-[1.45] text-ink-3">
        {t('menu.phone.appliesNote')} {t('menu.phone.duplicateNote')}
      </p>

      {picker ? (
        <IngredientPickerSheet
          ingredients={ingredients}
          locale={locale}
          onPick={(ing) => {
            setPicker(false)
            const existing = baseLines.find((l) => l.ingredientId === ing.id)
            setQtyFor({ ingredient: ing, baseQty: existing?.qtyPerPortion ?? null })
          }}
          onClose={() => setPicker(false)}
        />
      ) : null}
      {qtyFor ? (
        <RecipeQtySheet
          ingredient={qtyFor.ingredient}
          baseQty={qtyFor.baseQty}
          locale={locale}
          busy={putRecipe.isPending}
          onSubmit={(baseQty, done) =>
            saveLines(withLine(lines, qtyFor.ingredient.id, baseQty), done)
          }
          onClose={() => setQtyFor(null)}
        />
      ) : null}
      {confirmDelete ? (
        <DeleteConfirmSheet
          name={item.name}
          busy={remove.isPending}
          onConfirm={(done) => {
            setError(null)
            remove.mutate(item.id, {
              onSuccess: () => {
                done()
                onDeleted()
              },
              onError: (err) => {
                done()
                fail(err)
              },
            })
          }}
          onClose={() => setConfirmDelete(false)}
        />
      ) : null}
    </div>
  )
}

/** The price as the owner types it: major units, no grouping, the currency's own fraction digits. */
function majorInput(minor: number, currency: string): string {
  const digits = isoMinorExponent(currency)
  return minorToMajor(minor, currency).toFixed(digits).replace(/\.0+$/, '')
}

function currencySymbol(currency: string, locale: string): string {
  try {
    return (
      new Intl.NumberFormat(locale, { style: 'currency', currency })
        .formatToParts(0)
        .find((p) => p.type === 'currency')?.value ?? currency
    )
  } catch {
    return currency
  }
}

// ---------------------------------------------------------------------------
// Sheets — every overlay through the one Dialog primitive (ADR 0075 N3)
// ---------------------------------------------------------------------------


function RecipeQtySheet({
  ingredient,
  baseQty,
  locale,
  busy,
  onSubmit,
  onClose,
}: {
  ingredient: Ingredient
  /** The line's current quantity (requantify) or null (a new line). */
  baseQty: number | null
  locale: string
  busy: boolean
  onSubmit: (baseQty: number, done: () => void) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  // Base units, whole numbers: a recipe line is an integer in g / ml / pcs, so the keypad draws
  // no separator key and the figure is the quantity itself — no display-unit conversion to slip on.
  const fraction = false
  const unit = ingredient.unit
  const separator = decimalSeparatorOf(locale)
  const [raw, setRaw] = useState(() => (baseQty != null ? String(baseQty) : ''))
  const [pristine, setPristine] = useState(baseQty != null)
  const parsed = /^\d+$/.test(raw) ? Number(raw) : null
  const ok = parsed != null && parsed >= 1

  useEffect(() => {
    const id = requestAnimationFrame(() => inputRef.current?.focus())
    return () => cancelAnimationFrame(id)
  }, [])

  const press = (key: CountKey) => {
    setRaw(applyCountKey(pristine ? '' : raw, key, fraction))
    setPristine(false)
  }
  const submit = (done: () => void) => {
    if (!ok || busy || parsed == null) return
    onSubmit(parsed, done)
  }

  return (
    <DialogOverlay
      onClose={onClose}
      ariaLabel={`${t('menu.phone.qtyTitle')} — ${ingredient.name}`}
      className="p-0"
    >
      {(requestClose) => (
        <div className="flex flex-col">
          <div className="flex shrink-0 justify-center pb-1 pt-2.5 sm:hidden" aria-hidden="true">
            <div className="h-1 w-10 rounded-full bg-ink-300" />
          </div>
          <div className="flex shrink-0 items-start gap-2 px-[18px] pt-1.5">
            <div className="min-w-0 flex-1 pt-1">
              <div className="truncate text-base font-bold leading-tight text-ink">
                {t('menu.phone.qtyTitle')}
              </div>
              <div className="mt-1 truncate text-xs text-ink-3">
                {ingredient.name} · {t('menu.phone.qtyHint', { unit })}
              </div>
            </div>
            <button
              type="button"
              onClick={requestClose}
              aria-label={t('common.close')}
              className={cn(ICON_BUTTON, '-mr-2.5')}
            >
              <X className="size-[19px]" aria-hidden="true" />
            </button>
          </div>
          <div className="flex items-baseline justify-center gap-1.5 px-[18px] pt-[18px]">
            <input
              ref={inputRef}
              type="text"
              inputMode="none"
              autoComplete="off"
              spellCheck={false}
              value={raw}
              placeholder="0"
              aria-label={t('menu.phone.qtyTitle')}
              onChange={(e) => {
                const next = e.target.value.replace(/[^\d]/g, '')
                if (!countDraftWithinCap(next, fraction)) return
                setRaw(next)
                setPristine(false)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  submit(requestClose)
                  return
                }
                if (e.ctrlKey || e.metaKey || e.altKey) return
                const key: CountKey | null =
                  e.key === 'Backspace' || e.key === 'Delete'
                    ? 'backspace'
                    : /^[0-9.,]$/.test(e.key)
                      ? (e.key as CountKey)
                      : null
                if (key == null) return
                e.preventDefault()
                press(key)
              }}
              style={{ width: `${Math.max(1, raw.length)}ch` }}
              className="tnum min-w-[1ch] bg-transparent text-right font-mono text-5xl font-bold leading-none tracking-display text-ink caret-transparent placeholder:text-ink-400 focus:outline-none"
            />
            <span className="text-base font-semibold text-ink-3">{unit}</span>
          </div>
          <div
            className="min-h-[24px] px-[18px] pt-2 text-center text-xs font-semibold text-ink-400"
            aria-live="polite"
          >
            {raw !== '' && !ok ? t('menu.phone.qtyInvalid') : ''}
          </div>
          <div className="pt-2">
            <QtyKeypad fraction={fraction} separator={separator} onKey={press} />
          </div>
          <div className="flex shrink-0 gap-2 px-[18px] pt-3.5" style={SAFE_BOTTOM(24)}>
            <Button
              variant="outline"
              size="2xl"
              className="shrink-0 px-5"
              onClick={requestClose}
              disabled={busy}
            >
              {t('common.cancel')}
            </Button>
            <Button
              size="2xl"
              className="flex-1"
              disabled={!ok || busy}
              onClick={() => submit(requestClose)}
            >
              {busy ? <Spinner /> : t('common.save')}
            </Button>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}

function DeleteConfirmSheet({
  name,
  busy,
  onConfirm,
  onClose,
}: {
  name: string
  busy: boolean
  onConfirm: (done: () => void) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <DialogOverlay onClose={onClose} ariaLabel={t('menu.delete.title')}>
      {(requestClose) => (
        <div className="flex flex-col" style={SAFE_BOTTOM(8)}>
          <div className="text-base font-bold leading-tight text-ink">{t('menu.delete.title')}</div>
          <p className="mt-2 text-sm leading-relaxed text-ink-2">
            {t('menu.delete.body', { name })}
          </p>
          <div className="mt-5 flex gap-2">
            <Button
              variant="outline"
              size="lg"
              className="flex-1"
              onClick={requestClose}
              disabled={busy}
            >
              {t('common.cancel')}
            </Button>
            <button
              type="button"
              disabled={busy}
              onClick={() => onConfirm(requestClose)}
              className="h-12 flex-1 rounded-xl bg-loss text-sm font-bold text-white shadow-lift transition-transform active:scale-[0.98] motion-reduce:active:scale-100 disabled:opacity-50"
            >
              {busy ? <Spinner /> : t('menu.delete.confirm')}
            </button>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}

function NewItemSheet({
  session,
  items,
  locale,
  onClose,
  onCreated,
}: {
  session: CompanySession
  items: MenuItem[]
  locale: string
  onClose: () => void
  onCreated: (id: string) => void
}) {
  const { t } = useTranslation()
  const create = useCreateMenuItem(session)
  const [name, setName] = useState('')
  const [price, setPrice] = useState('')
  const [category, setCategory] = useState<string>('')
  const [custom, setCustom] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The categories already on the menu, then the templates not yet used — one row of chips.
  const options = useMemo(() => {
    const seen = new Set<string>()
    const out: { key: string; label: string; value: string }[] = []
    for (const it of items) {
      const raw = it.category.trim()
      if (raw === '') continue
      const key = canonicalCategoryKey(raw)
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ key, label: displayCategoryName(raw, t), value: raw })
    }
    for (const tpl of CATEGORY_TEMPLATE_KEYS) {
      const label = t(`menu.categoryTemplates.${tpl}`)
      const key = canonicalCategoryKey(label)
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ key, label, value: label })
    }
    return out
  }, [items, t])

  const priceMinor = parseMajorPrice(price, session.baseCurrency)
  const ok = name.trim() !== '' && priceMinor != null && category.trim() !== ''

  const submit = (done: () => void) => {
    if (!ok || priceMinor == null || create.isPending) return
    setError(null)
    create.mutate(
      {
        name: name.trim(),
        category: category.trim(),
        priceMinor,
        currency: session.baseCurrency,
        autoTrackStock: false,
      },
      {
        onSuccess: (created) => {
          done()
          if (created) onCreated(created.id)
        },
        onError: (err) => setError(problemDetail(err) ?? t('menu.phone.failed')),
      },
    )
  }

  return (
    <DialogOverlay onClose={onClose} ariaLabel={t('menu.phone.newItem')}>
      {(requestClose) => (
        <div className="flex flex-col" style={SAFE_BOTTOM(8)}>
          <div className="flex items-center gap-2">
            <div className="min-w-0 flex-1 text-base font-bold leading-tight text-ink">
              {t('menu.phone.newItem')}
            </div>
            <button
              type="button"
              onClick={requestClose}
              aria-label={t('common.close')}
              className={cn(ICON_BUTTON, '-mr-2.5')}
            >
              <X className="size-[19px]" aria-hidden="true" />
            </button>
          </div>
          <label className={cn(FIELD_CARD, 'mt-3')}>
            <span className={FIELD_LABEL}>{t('menu.createItem.nameLabel')}</span>
            <input
              type="text"
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('menu.createItem.namePlaceholder')}
              className="mt-1.5 w-full bg-transparent text-base font-semibold leading-tight text-ink placeholder:text-ink-400 focus:outline-none"
            />
          </label>
          <div className="mt-2.5">
            <span className={cn(FIELD_LABEL, 'px-0.5')}>{t('menu.createItem.categoryLabel')}</span>
            <div className="mt-1.5 flex flex-wrap gap-[7px]">
              {options.map((o) => {
                const on = !custom && canonicalCategoryKey(category) === o.key && category !== ''
                return (
                  <button
                    key={o.key}
                    type="button"
                    aria-pressed={on}
                    onClick={() => {
                      setCustom(false)
                      setCategory(o.value)
                    }}
                    className={cn(
                      'h-8 rounded-full border px-3 text-xs font-semibold transition-colors',
                      on
                        ? 'border-emerald bg-emerald text-on-emerald'
                        : 'border-line bg-surface text-ink-3 hover:bg-hover',
                    )}
                  >
                    {o.label}
                  </button>
                )
              })}
              <button
                type="button"
                aria-pressed={custom}
                onClick={() => {
                  setCustom(true)
                  setCategory('')
                }}
                className={cn(
                  'h-8 rounded-full border px-3 text-xs font-semibold transition-colors',
                  custom
                    ? 'border-emerald bg-emerald text-on-emerald'
                    : 'border-line bg-surface text-ink-3 hover:bg-hover',
                )}
              >
                {t('menu.phone.categoryCustom')}
              </button>
            </div>
            {custom ? (
              <input
                type="text"
                value={category}
                maxLength={64}
                onChange={(e) => setCategory(e.target.value)}
                placeholder={t('menu.phone.categoryCustomPlaceholder')}
                className="mt-2 h-11 w-full rounded-xl border border-line bg-surface px-3 text-sm font-medium text-ink placeholder:text-ink-400 focus:outline-none focus-visible:outline-2 focus-visible:outline-emerald"
              />
            ) : null}
          </div>
          <label className={cn(FIELD_CARD, 'mt-2.5')}>
            <span className={FIELD_LABEL}>{t('menu.phone.priceLabel')}</span>
            <span className="mt-1.5 flex items-baseline gap-1.5">
              <span className="font-mono text-xs font-semibold text-ink-3">
                {currencySymbol(session.baseCurrency, locale)}
              </span>
              <input
                type="text"
                inputMode="decimal"
                value={price}
                onChange={(e) => setPrice(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') submit(requestClose)
                }}
                placeholder="0"
                aria-label={t('menu.phone.priceLabel')}
                className="tnum min-w-0 flex-1 bg-transparent font-mono text-base font-semibold leading-tight text-ink placeholder:text-ink-400 focus:outline-none"
              />
            </span>
          </label>
          {error ? (
            <p className="mt-2 text-xs text-loss" role="alert">
              {error}
            </p>
          ) : null}
          <Button
            size="xl"
            className="mt-4 w-full"
            disabled={!ok || create.isPending}
            onClick={() => submit(requestClose)}
          >
            {create.isPending ? <Spinner /> : t('menu.createItem.submit')}
          </Button>
        </div>
      )}
    </DialogOverlay>
  )
}
