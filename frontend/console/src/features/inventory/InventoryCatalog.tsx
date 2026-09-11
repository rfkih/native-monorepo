/**
 * InventoryCatalog — the list that reads in days (ADR 0081). A value hero (the figure account 1100
 * will hold once inventory is booked), today's consumption in money, the "Prediksi stok" toggle,
 * four filter chips with counts, a sort pill, and one row per ingredient whose right-hand figure
 * is DAYS LEFT — coloured by class, with the per-day rate under it. One action stays on the row
 * (Terima — deliveries come daily); everything else is on the ingredient's own screen.
 *
 * Two variants of the same data: `phone` (name over chip + "qty · value", days on the right) and
 * `desktop` (a column header and aligned Stok / Nilai / Terpakai per hari / Sisa columns). The
 * rules — rate, class, ranking, counts — are `lib/catalogView.ts`; this file only formats.
 */
import { useTranslation } from 'react-i18next'
import { ArrowDownToLine, ChevronRight, ListFilter, Package, TriangleAlert } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CatalogOrder } from './catalogState'
import type { InventoryData } from './inventoryData'
import {
  CATALOG_FILTERS,
  nextCatalogSort,
  stockValueOf,
  USAGE_WINDOW_DAYS,
  type CatalogClass,
  type CatalogFilter,
  type CatalogRow,
} from './lib/catalogView'
import { formatShownQty, shownUnit } from './lib/units'
import { needsUnitConversion } from './lib/unitConversion'
import { formatDays, formatRateQty } from './format'

const CHIP_TONE: Record<CatalogFilter, string> = {
  zero: 'bg-tint-loss text-loss',
  low: 'bg-amber-tint text-amber',
  unit: 'bg-amber-tint text-amber',
  nocost: 'bg-ink-50 text-ink-2',
}

const DAYS_TONE: Record<CatalogClass, string> = {
  zero: 'text-loss',
  low: 'text-amber',
  unit: 'text-ink',
  nocost: 'text-ink',
  ok: 'text-ink',
}

/** The small status chip a row carries (none for a healthy row; "low" speaks through the days). */
function rowChip(row: CatalogRow): { key: 'zero' | 'unit' | 'nocost'; unit?: string } | null {
  if (row.cls === 'zero') return { key: 'zero' }
  if (needsUnitConversion(row.ingredient)) return { key: 'unit', unit: row.ingredient.unit }
  if (row.ingredient.unitCostMinor == null) return { key: 'nocost' }
  return null
}

export function InventoryCatalog({
  data,
  order,
  locale,
  currency,
  variant,
  selectedId,
  showDays,
  onToggleDays,
  isOwner,
  onOpen,
  onReceive,
  onCreate,
}: {
  data: InventoryData
  order: CatalogOrder
  locale: string
  currency: string
  variant: 'phone' | 'desktop'
  selectedId: string | null
  showDays: boolean
  onToggleDays: () => void
  isOwner: boolean
  onOpen: (id: string) => void
  onReceive: (id: string) => void
  onCreate: () => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { query, ingredients, totals } = data

  if (query.isLoading) return <CatalogSkeleton variant={variant} />

  if (query.isError) {
    return (
      <div className="grid min-h-[60dvh] place-items-center px-8 py-10">
        <div className="text-center">
          <TriangleAlert className="mx-auto mb-2.5 size-[26px] text-loss" aria-hidden="true" />
          <div className="text-sm font-semibold text-loss">{t('inventory.loadError')}</div>
          <Button size="lg" className="mt-5" onClick={() => void query.refetch()}>
            {t('inventory.catalog.retry')}
          </Button>
        </div>
      </div>
    )
  }

  if (ingredients.length === 0) {
    return (
      <div className="grid min-h-[60dvh] place-items-center px-8 py-10">
        <div className="max-w-sm text-center">
          <div className="mx-auto mb-3.5 grid size-12 place-items-center rounded-2xl bg-tint-profit text-profit-ink">
            <Package className="size-[22px]" strokeWidth={1.8} aria-hidden="true" />
          </div>
          <h2 className="font-display text-xl font-bold tracking-display text-ink">
            {t('inventory.emptyTitle')}
          </h2>
          <p className="mt-2 text-xs leading-relaxed text-ink-3 text-pretty">
            {t('inventory.emptyHint')}
          </p>
          <Button size="lg" className="mt-5" onClick={onCreate}>
            {t('inventory.catalog.emptyFirst')}
          </Button>
        </div>
      </div>
    )
  }

  const heroNote =
    totals.uncostedCount > 0
      ? t('inventory.catalog.heroUncosted', { count: totals.uncostedCount })
      : t('inventory.catalog.heroAllCosted')
  // The note is the door to the inventory-method page — owner-only, like the route it opens.
  const openMethod = () =>
    navigate('/settings/inventory', {
      state: { catalogValueMinor: totals.totalValueMinor, currency, outletName: data.session.name },
    })
  const shelfLabel =
    totals.daysOverall != null
      ? t('inventory.catalog.shelfDays', { days: formatDays(totals.daysOverall, locale) })
      : t('inventory.catalog.shelfDaysUnknown')

  const toggle = (
    <button
      type="button"
      onClick={onToggleDays}
      aria-pressed={showDays}
      title={t('inventory.catalog.predictionHint', { days: USAGE_WINDOW_DAYS })}
      className={cn(
        'flex shrink-0 items-center gap-2 rounded-xl transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        variant === 'phone'
          ? 'min-h-11 pl-2.5 pr-0.5 active:bg-ink-50'
          : 'h-9 rounded-full border border-line bg-surface pl-3 pr-1 hover:border-line-strong',
      )}
    >
      <span
        className={cn(
          'whitespace-nowrap text-xs font-semibold',
          showDays ? 'text-ink-2' : 'text-ink-3',
        )}
      >
        {variant === 'phone' && showDays ? shelfLabel : t('inventory.catalog.predictionLabel')}
      </span>
      <span
        aria-hidden="true"
        className={cn(
          'flex h-[22px] w-[38px] shrink-0 items-center rounded-full p-0.5 transition-colors',
          showDays ? 'justify-end bg-profit' : 'justify-start bg-line-strong',
        )}
      >
        <span className="size-[18px] rounded-full bg-white shadow-[0_1px_2px_rgba(15,23,42,.2)]" />
      </span>
    </button>
  )

  const chips = (
    <div
      className={cn(
        'flex gap-[7px]',
        variant === 'phone' ? 'overflow-x-auto px-4 [scrollbar-width:none]' : 'flex-wrap',
      )}
    >
      {CATALOG_FILTERS.map((key) => {
        const on = order.filter === key
        return (
          <button
            key={key}
            type="button"
            onClick={() => order.setFilter(on ? null : key)}
            aria-pressed={on}
            className={cn(
              'flex h-[30px] shrink-0 items-center gap-[7px] rounded-full px-3 text-xs font-bold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              on ? 'bg-emerald text-on-emerald' : CHIP_TONE[key],
            )}
          >
            {t(`inventory.catalog.chips.${key}`)}
            <span className="tnum font-mono text-2xs font-bold opacity-70">
              {new Intl.NumberFormat(locale).format(order.counts[key])}
            </span>
          </button>
        )
      })}
    </div>
  )

  const caption = (
    <div className="flex items-center justify-between gap-2.5">
      <span className="text-2xs font-semibold uppercase tracking-eyebrow text-ink-3">
        {order.filter
          ? t('inventory.catalog.captionFiltered', { count: order.ranked.length })
          : t('inventory.catalog.caption', { count: order.ranked.length })}
      </span>
      <button
        type="button"
        onClick={() => order.setSort(nextCatalogSort(order.sort))}
        aria-label={t('inventory.catalog.sortAria', {
          sort: t(`inventory.catalog.sort.${order.sort}`),
        })}
        className="flex h-7 shrink-0 items-center gap-[5px] rounded-full bg-ink-50 px-2.5 text-2xs font-semibold text-ink-2 transition-colors hover:bg-hover active:bg-line focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        <ListFilter className="size-[11px]" strokeWidth={2.2} aria-hidden="true" />
        {t(`inventory.catalog.sort.${order.sort}`)}
      </button>
    </div>
  )

  const noHits =
    order.ranked.length === 0 ? (
      <div className="px-8 py-11 text-center">
        <div className="text-sm font-semibold text-ink">{t('inventory.catalog.noHitsTitle')}</div>
        <div className="mt-1.5 text-xs text-ink-3">{t('inventory.catalog.noHitsHint')}</div>
      </div>
    ) : null

  if (variant === 'phone') {
    return (
      <div className="pb-8">
        <div className="px-4 pt-3">
          <div className="text-xs font-semibold text-ink-3">
            {t('inventory.catalog.heroLabel', { count: ingredients.length })}
          </div>
          <HeroValue
            value={formatMoney(totals.totalValueMinor, currency, locale)}
            note={heroNote}
            onOpen={isOwner ? openMethod : null}
          />
          <div className="mt-2.5 flex items-center gap-2 border-t border-line pt-[11px]">
            <span className="text-xs font-medium text-ink-3">
              {t('inventory.catalog.usedToday')}
            </span>
            <span className="tnum font-mono text-sm font-bold text-ink">
              {formatMoney(totals.usedTodayValueMinor, currency, locale)}
            </span>
            <span className="min-w-0 flex-1" />
            {toggle}
          </div>
        </div>
        <div className="mt-4">{chips}</div>
        <div className="mt-3.5 px-4 pb-1">{caption}</div>
        <div>
          {order.ranked.map((row) => (
            <PhoneRow
              key={row.ingredient.id}
              row={row}
              locale={locale}
              showDays={showDays}
              onOpen={() => onOpen(row.ingredient.id)}
              onReceive={() => onReceive(row.ingredient.id)}
            />
          ))}
          {noHits}
        </div>
      </div>
    )
  }

  return (
    <div className="pb-6">
      <div className="flex flex-wrap items-end gap-x-9 gap-y-4 px-6 pt-5">
        <div>
          <div className="text-2xs font-semibold text-ink-3">
            {t('inventory.catalog.heroLabel', { count: ingredients.length })}
          </div>
          <HeroValue
            value={formatMoney(totals.totalValueMinor, currency, locale)}
            note={heroNote}
            onOpen={isOwner ? openMethod : null}
            compact
          />
        </div>
        <div>
          <div className="text-2xs font-semibold text-ink-3">
            {t('inventory.catalog.usedToday')}
          </div>
          <div className="tnum mt-1.5 font-mono text-lg font-bold leading-tight text-ink">
            {formatMoney(totals.usedTodayValueMinor, currency, locale)}
          </div>
        </div>
        {showDays ? (
          <div>
            <div className="text-2xs font-semibold text-ink-3">
              {t('inventory.catalog.shelf')}
            </div>
            <div className="tnum mt-1.5 font-mono text-lg font-bold leading-tight text-ink">
              {shelfLabel}
            </div>
          </div>
        ) : null}
        {toggle}
        <div className="min-w-0 flex-1" />
        {chips}
      </div>

      <div className="mx-6 mt-[18px] flex items-center gap-3.5 border-b border-line px-3 pb-2">
        <span className="min-w-0 flex-1 text-2xs font-semibold uppercase tracking-eyebrow text-ink-400">
          {t('inventory.catalog.columns.name')}
        </span>
        <ColumnHead width="w-[108px]">{t('inventory.catalog.columns.stock')}</ColumnHead>
        <ColumnHead width="w-[118px]">{t('inventory.catalog.columns.value')}</ColumnHead>
        <ColumnHead width="w-[112px]">{t('inventory.catalog.columns.rate')}</ColumnHead>
        {showDays ? (
          <ColumnHead width="w-[78px]">{t('inventory.catalog.columns.days')}</ColumnHead>
        ) : null}
        <span className="w-11 shrink-0" />
      </div>
      <div className="mx-6 mb-2 mt-2 flex items-center px-3">{caption}</div>
      <div className="px-6">
        {order.ranked.map((row) => (
          <DesktopRow
            key={row.ingredient.id}
            row={row}
            locale={locale}
            showDays={showDays}
            selected={row.ingredient.id === selectedId}
            onOpen={() => onOpen(row.ingredient.id)}
            onReceive={() => onReceive(row.ingredient.id)}
          />
        ))}
        {noHits}
      </div>
    </div>
  )
}

function HeroValue({
  value,
  note,
  onOpen,
  compact = false,
}: {
  value: string
  note: string
  /** The owner's door to the inventory-method page; null renders the note as plain text. */
  onOpen: (() => void) | null
  compact?: boolean
}) {
  const { t } = useTranslation()
  const figure = (
    <span
      className={cn(
        'tnum block font-mono font-bold leading-none tracking-display text-ink',
        compact ? 'text-2xl' : 'text-2xl',
      )}
    >
      {value}
    </span>
  )
  const noteLine = (
    <span className="flex items-center gap-1 text-xs font-semibold text-amber">
      {note}
      {onOpen ? (
        <ChevronRight className="size-[13px]" strokeWidth={2.2} aria-hidden="true" />
      ) : null}
    </span>
  )
  if (!onOpen) {
    return (
      <div
        className={cn(
          'flex flex-col items-start',
          compact ? 'mt-1.5 gap-1.5' : 'mt-[7px] gap-[7px]',
        )}
      >
        {figure}
        {noteLine}
      </div>
    )
  }
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t('inventory.catalog.heroOpenMethod')}
      className={cn(
        'flex flex-col items-start text-left focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-emerald',
        compact ? 'mt-1.5 gap-1.5' : 'mt-[7px] gap-[7px]',
      )}
    >
      {figure}
      {noteLine}
    </button>
  )
}

function ColumnHead({ width, children }: { width: string; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'shrink-0 text-right text-2xs font-semibold uppercase tracking-eyebrow text-ink-400',
        width,
      )}
    >
      {children}
    </span>
  )
}

function RowChip({ row }: { row: CatalogRow }) {
  const { t } = useTranslation()
  const chip = rowChip(row)
  if (!chip) return null
  return (
    <span
      className={cn(
        'flex h-[19px] shrink-0 items-center rounded-full px-[7px] text-2xs font-bold',
        CHIP_TONE[chip.key],
      )}
    >
      {chip.key === 'unit'
        ? t('inventory.catalog.rowChip.unit', { unit: chip.unit })
        : t(`inventory.catalog.rowChip.${chip.key}`)}
    </span>
  )
}

function RateLabel({ row, locale }: { row: CatalogRow; locale: string }) {
  const { t } = useTranslation()
  if (row.rate == null) return <>{t('inventory.catalog.rateNone')}</>
  return (
    <>
      {t('inventory.catalog.rateLine', {
        qty: formatRateQty(row.rate, row.ingredient, locale),
        unit: shownUnit(row.ingredient),
      })}
    </>
  )
}

function DaysFigure({ row, locale }: { row: CatalogRow; locale: string }) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        'tnum font-mono text-sm font-bold',
        row.days == null ? 'text-ink-400' : DAYS_TONE[row.cls],
      )}
    >
      {row.days == null
        ? t('inventory.catalog.daysNone')
        : t('inventory.catalog.daysShort', { days: formatDays(row.days, locale) })}
    </span>
  )
}

function ReceiveButton({
  name,
  onReceive,
  size,
}: {
  name: string
  onReceive: () => void
  size: 'row' | 'cell'
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onReceive}
      aria-label={t('inventory.catalog.receiveAria', { name })}
      title={t('inventory.receiveAction')}
      className={cn(
        'grid shrink-0 place-items-center rounded-xl text-profit-ink transition-colors hover:bg-tint-profit active:bg-tint-profit focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
        size === 'row' ? 'w-11' : 'h-9 w-11 rounded-xl',
      )}
    >
      <ArrowDownToLine
        className={size === 'row' ? 'size-[19px]' : 'size-[17px]'}
        strokeWidth={1.9}
        aria-hidden="true"
      />
    </button>
  )
}

function PhoneRow({
  row,
  locale,
  showDays,
  onOpen,
  onReceive,
}: {
  row: CatalogRow
  locale: string
  showDays: boolean
  onOpen: () => void
  onReceive: () => void
}) {
  const { t } = useTranslation()
  const { ingredient } = row
  const qty = formatShownQty(ingredient.stockQty, ingredient, locale)
  const unit = shownUnit(ingredient)
  const qtyLine =
    ingredient.unitCostMinor != null && ingredient.costCurrency != null
      ? t('inventory.catalog.qtyValue', {
          qty,
          unit,
          value: formatMoney(stockValueOf(ingredient), ingredient.costCurrency, locale),
        })
      : `${qty} ${unit}`
  return (
    <div className="flex items-stretch gap-0.5 border-t border-line/60 pl-4 pr-2">
      <button
        type="button"
        onClick={onOpen}
        className="flex min-h-[70px] min-w-0 flex-1 items-center gap-3 rounded-xl py-[11px] pr-1.5 text-left transition-colors active:bg-ink-50 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
      >
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="truncate text-sm font-semibold leading-tight text-ink">
            {ingredient.name}
          </span>
          <span className="flex min-w-0 items-center gap-1.5">
            <RowChip row={row} />
            <span className="tnum truncate font-mono text-xs text-ink-3">{qtyLine}</span>
          </span>
        </span>
        {showDays ? (
          <span className="flex shrink-0 flex-col items-end gap-[3px]">
            <DaysFigure row={row} locale={locale} />
            <span className="text-2xs font-medium text-ink-400">
              <RateLabel row={row} locale={locale} />
            </span>
          </span>
        ) : null}
      </button>
      <ReceiveButton name={ingredient.name} onReceive={onReceive} size="row" />
    </div>
  )
}

function DesktopRow({
  row,
  locale,
  showDays,
  selected,
  onOpen,
  onReceive,
}: {
  row: CatalogRow
  locale: string
  showDays: boolean
  selected: boolean
  onOpen: () => void
  onReceive: () => void
}) {
  const { t } = useTranslation()
  const { ingredient } = row
  const costed = ingredient.unitCostMinor != null && ingredient.costCurrency != null
  return (
    <div
      className={cn(
        'flex items-center gap-3.5 rounded-xl border-b border-line/60 px-3 transition-colors',
        selected ? 'bg-surface' : 'hover:bg-hover',
      )}
    >
      <button
        type="button"
        onClick={onOpen}
        aria-current={selected ? 'true' : undefined}
        className="flex min-h-14 min-w-0 flex-1 items-center gap-3.5 py-[9px] text-left focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
      >
        <span className="flex min-w-0 flex-1 flex-col gap-[3px]">
          <span className="truncate text-sm font-semibold leading-tight text-ink">
            {ingredient.name}
          </span>
          <span className="flex items-center gap-1.5">
            <RowChip row={row} />
            {ingredient.packSize != null ? (
              <span className="text-2xs font-medium text-ink-400">
                {t('inventory.catalog.packLine', {
                  qty: formatShownQty(ingredient.packSize, ingredient, locale),
                  unit: shownUnit(ingredient),
                })}
              </span>
            ) : null}
          </span>
        </span>
        <span
          className={cn(
            'tnum w-[108px] shrink-0 text-right font-mono text-sm font-semibold',
            row.cls === 'zero' ? 'text-loss' : 'text-ink',
          )}
        >
          {formatShownQty(ingredient.stockQty, ingredient, locale)} {shownUnit(ingredient)}
        </span>
        <span
          className={cn(
            'tnum w-[118px] shrink-0 text-right font-mono text-sm',
            costed ? 'text-ink-2' : 'text-ink-400',
          )}
        >
          {costed
            ? formatMoney(stockValueOf(ingredient), ingredient.costCurrency as string, locale)
            : t('inventory.catalog.valueUncosted')}
        </span>
        <span className="tnum w-[112px] shrink-0 text-right font-mono text-sm text-ink-3">
          {row.rate != null
            ? `${formatRateQty(row.rate, ingredient, locale)} ${shownUnit(ingredient)}`
            : t('inventory.catalog.daysNone')}
        </span>
        {showDays ? (
          <span className="w-[78px] shrink-0 text-right">
            <DaysFigure row={row} locale={locale} />
          </span>
        ) : null}
      </button>
      <ReceiveButton name={ingredient.name} onReceive={onReceive} size="cell" />
    </div>
  )
}

function CatalogSkeleton({ variant }: { variant: 'phone' | 'desktop' }) {
  const widths = [46, 38, 52, 41, 48, 35, 44]
  return (
    <div
      className={cn('flex flex-col gap-5', variant === 'phone' ? 'px-4 pt-3' : 'px-6 pt-5')}
      aria-busy="true"
    >
      <div className="flex flex-col gap-2">
        <Skeleton className="h-[11px] w-[42%]" />
        <Skeleton className="h-[30px] w-[62%]" />
      </div>
      <div className="flex gap-[7px]">
        <Skeleton className="h-[30px] w-[86px] rounded-full" />
        <Skeleton className="h-[30px] w-[104px] rounded-full" />
        <Skeleton className="h-[30px] w-[78px] rounded-full" />
      </div>
      {widths.map((w, i) => (
        <div key={i} className="flex items-center gap-3">
          <div className="flex flex-1 flex-col gap-1.5">
            <div style={{ width: `${w}%` }}>
              <Skeleton className="h-3 w-full" />
            </div>
            <div style={{ width: `${(w * 1.3) % 70}%` }}>
              <Skeleton className="h-[9px] w-full" />
            </div>
          </div>
          <Skeleton className="h-3 w-[52px]" />
        </div>
      ))}
    </div>
  )
}
