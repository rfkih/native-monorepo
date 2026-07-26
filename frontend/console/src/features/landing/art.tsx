/**
 * Landing-page artwork & product mockups — all self-contained inline SVG / DOM (no raster assets,
 * no external calls), theme-aware via the design-system tokens, and crisp at any size.
 *
 * The "screenshots" (DashboardMock, PosReceiptMock) are the real product's aesthetic rebuilt as
 * static markup — the same trick Linear/Stripe use so the hero art stays pixel-perfect and themable.
 * Brand-green hues are used directly (the brand ramp is theme-invariant); every surface / ink / line
 * uses a token utility so the art recolors for free in dark mode.
 */
import { useTranslation } from 'react-i18next'
import {
  ArrowLeftRight,
  Briefcase,
  Building2,
  Car,
  Check,
  ShoppingBag,
  Utensils,
  type LucideIcon,
} from 'lucide-react'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { MOCK_EXPENSE, MOCK_NET, MOCK_REVENUE, money } from './mock'

// ── Atmospheric backdrop ─────────────────────────────────────────────────────────────────────────
// A fixed, layered background: two soft brand glows + a fine masked grid. Sits behind everything.

export function Backdrop() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      {/* base wash */}
      <div className="absolute inset-0 bg-paper" />
      {/* brand glow — top right */}
      <div
        className="absolute -right-40 -top-56 h-[640px] w-[640px] rounded-full opacity-70 blur-3xl"
        style={{
          background:
            'radial-gradient(closest-side, color-mix(in srgb, var(--color-brand-400) 30%, transparent), transparent)',
        }}
      />
      {/* brand glow — mid left, cooler */}
      <div
        className="absolute -left-56 top-[38%] h-[560px] w-[560px] rounded-full opacity-50 blur-3xl"
        style={{
          background:
            'radial-gradient(closest-side, color-mix(in srgb, var(--color-brand-300) 22%, transparent), transparent)',
        }}
      />
      {/* fine grid, faded top-to-transparent */}
      <svg className="absolute inset-0 h-full w-full text-ink-900/[0.04]" aria-hidden>
        <defs>
          <pattern id="grid" width="44" height="44" patternUnits="userSpaceOnUse">
            <path d="M44 0H0V44" fill="none" stroke="currentColor" strokeWidth="1" />
          </pattern>
          <linearGradient id="gridfade" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="white" stopOpacity="1" />
            <stop offset="0.7" stopColor="white" stopOpacity="0" />
          </linearGradient>
          <mask id="gridmask">
            <rect width="100%" height="100%" fill="url(#gridfade)" />
          </mask>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)" mask="url(#gridmask)" />
      </svg>
    </div>
  )
}

// ── Hero product "screenshot": the consolidated dashboard ────────────────────────────────────────

export function DashboardMock() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  return (
    <div className="w-full max-w-[440px] overflow-hidden rounded-[20px] border border-line bg-surface shadow-lg">
      {/* window chrome */}
      <div className="flex items-center gap-2 border-b border-line bg-paper/60 px-4 py-3">
        <span className="size-2.5 rounded-full bg-loss/70" />
        <span className="size-2.5 rounded-full bg-warning/70" />
        <span className="size-2.5 rounded-full bg-profit/70" />
        <span className="mx-auto flex items-center gap-1.5 rounded-md bg-surface px-2.5 py-1 text-[11px] font-medium text-ink-3 shadow-sm">
          <span className="size-1.5 rounded-full bg-brand-500" />
          app.native.id
        </span>
      </div>

      <div className="p-5">
        <div className="flex items-center justify-between">
          <div>
            <div className="font-display text-[15px] font-bold text-ink">
              {t('dashboard.title')}
            </div>
            <div className="text-[11.5px] text-ink-3">{t('landing.mockPeriod')}</div>
          </div>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-tint-profit px-2.5 py-1 text-[10.5px] font-bold text-profit">
            <span className="size-1.5 animate-pulse rounded-full bg-profit" />
            {t('landing.mockLive')}
          </span>
        </div>

        {/* KPI row */}
        <div className="mt-4 grid grid-cols-3 gap-2.5">
          <Kpi label={t('landing.mockRevenue')} value={money(MOCK_REVENUE, locale, true)} delta="+12.4%" tone="profit" />
          <Kpi label={t('landing.mockExpense')} value={money(MOCK_EXPENSE, locale, true)} delta="+4.1%" tone="ink" />
          <Kpi label={t('landing.mockNet')} value={money(MOCK_NET, locale, true)} delta="+28.9%" tone="profit" />
        </div>

        {/* area chart */}
        <div className="mt-4 rounded-xl border border-line bg-paper/50 p-3">
          <div className="mb-2 flex items-center justify-between text-[10.5px] text-ink-3">
            <span className="font-semibold text-ink-2">{t('dashboard.revenueVsExpense')}</span>
            <span className="flex items-center gap-3">
              <Swatch className="bg-brand-500" label={t('landing.mockRevenue')} />
              <Swatch className="bg-ink-300" label={t('landing.mockExpense')} />
            </span>
          </div>
          <AreaChart />
        </div>

        {/* outlet contribution */}
        <div className="mt-4 space-y-2">
          {[
            [t('landing.mockOutletA'), 0.46],
            [t('landing.mockOutletB'), 0.33],
            [t('landing.mockOutletC'), 0.21],
          ].map(([name, frac]) => (
            <div key={name as string} className="flex items-center gap-3">
              <span className="w-24 shrink-0 truncate text-[11px] text-ink-2">{name}</span>
              <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-100">
                <span
                  className="block h-full rounded-full bg-brand-500"
                  style={{ width: `${(frac as number) * 100}%` }}
                />
              </span>
              <span className="tnum w-9 shrink-0 text-right text-[11px] font-semibold text-ink-3">
                {Math.round((frac as number) * 100)}%
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function Kpi({
  label,
  value,
  delta,
  tone,
}: {
  label: string
  value: string
  delta: string
  tone: 'profit' | 'ink'
}) {
  return (
    <div className="rounded-lg border border-line bg-surface px-2.5 py-2">
      <div className="truncate text-[9.5px] font-semibold uppercase tracking-wide text-ink-3">
        {label}
      </div>
      <div className="tnum mt-1 text-[13px] font-bold text-ink">{value}</div>
      <div
        className={cn(
          'tnum mt-0.5 text-[10px] font-bold',
          tone === 'profit' ? 'text-profit' : 'text-ink-3',
        )}
      >
        ▲ {delta}
      </div>
    </div>
  )
}

function Swatch({ className, label }: { className: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1">
      <span className={cn('size-2 rounded-sm', className)} />
      {label}
    </span>
  )
}

/** A small revenue-vs-expense area chart — hand-tuned smooth paths, gradient fill under revenue. */
function AreaChart() {
  const REV = 'M4,74 C24,66 34,58 54,58 C78,58 86,60 108,62 C136,64 146,44 168,38 C196,30 210,24 232,22 C258,20 296,14 316,10'
  const EXP = 'M4,84 C24,80 34,76 54,76 C82,76 92,74 108,72 C138,70 146,68 168,66 C198,64 210,60 232,58 C262,56 296,52 316,50'
  return (
    <svg viewBox="0 0 320 96" className="h-24 w-full" preserveAspectRatio="none" aria-hidden>
      <defs>
        <linearGradient id="revfill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#16b364" stopOpacity="0.26" />
          <stop offset="1" stopColor="#16b364" stopOpacity="0" />
        </linearGradient>
      </defs>
      {/* faint gridlines */}
      {[24, 48, 72].map((y) => (
        <line key={y} x1="0" y1={y} x2="320" y2={y} className="stroke-line" strokeWidth="1" />
      ))}
      <path d={`${REV} L316,96 L4,96 Z`} fill="url(#revfill)" />
      <path d={EXP} fill="none" className="stroke-ink-300" strokeWidth="2" strokeLinecap="round" />
      <path d={REV} fill="none" className="stroke-brand-500" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  )
}

// ── Floating accent cards (positioned by the parent via className) ───────────────────────────────

export function SaleToast({ className }: { className?: string }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  return (
    <div
      className={cn(
        'flex items-center gap-3 rounded-2xl border border-line bg-surface p-3 pr-4 shadow-lg',
        className,
      )}
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-full bg-tint-profit text-profit">
        <Check className="size-4.5" />
      </span>
      <div className="leading-tight">
        <div className="text-[12.5px] font-bold text-ink">{t('landing.toastTitle')}</div>
        <div className="text-[11px] text-ink-3">{t('landing.toastMeta')}</div>
      </div>
      <div className="tnum ml-1 text-[12.5px] font-bold text-profit">
        +{money(150_000, locale, true)}
      </div>
    </div>
  )
}

export function FxChip({ className }: { className?: string }) {
  const { t } = useTranslation()
  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-2 shadow-lg',
        className,
      )}
    >
      <span className="grid size-6 place-items-center rounded-full bg-brand-50 text-brand-600">
        <ArrowLeftRight className="size-3.5" />
      </span>
      <span className="tnum text-[12px] font-bold text-ink">IDR → USD</span>
      <span className="text-[11px] text-ink-3">{t('landing.fxChip')}</span>
    </div>
  )
}

// ── POS receipt mock (feature row) ───────────────────────────────────────────────────────────────

export function PosReceiptMock() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const items: [string, number, number][] = [
    [t('landing.posItem1'), 2, 90_000],
    [t('landing.posItem2'), 3, 24_000],
    [t('landing.posItem3'), 1, 55_000],
  ]
  const subtotal = items.reduce((s, [, q, p]) => s + q * p, 0)
  return (
    <div className="mx-auto w-full max-w-[320px] rounded-[20px] border border-line bg-surface p-5 shadow-lg">
      <div className="flex items-center justify-between border-b border-dashed border-line pb-3">
        <div>
          <div className="font-display text-[14px] font-bold text-ink">{t('landing.posOrder')}</div>
          <div className="text-[11px] text-ink-3">{t('landing.posOutlet')}</div>
        </div>
        <span className="inline-flex items-center gap-1 rounded-full bg-tint-profit px-2 py-1 text-[10px] font-bold text-profit">
          <Check className="size-3" /> {t('landing.posPaid')}
        </span>
      </div>
      <div className="space-y-2 py-3">
        {items.map(([name, qty, price]) => (
          <div key={name} className="flex items-center gap-2 text-[12.5px]">
            <span className="tnum w-5 text-ink-3">{qty}×</span>
            <span className="flex-1 truncate text-ink-2">{name}</span>
            <span className="tnum font-medium text-ink">{money(qty * price, locale, true)}</span>
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between border-t border-dashed border-line pt-3">
        <span className="text-[12px] font-semibold text-ink-3">{t('pos.total')}</span>
        <span className="tnum font-display text-[16px] font-extrabold text-ink">
          {money(subtotal, locale, true)}
        </span>
      </div>
    </div>
  )
}

// ── Multi-currency consolidation diagram (feature row) ──────────────────────────────────────────

export function ConsolidationDiagram() {
  const { t } = useTranslation()
  const entities: [string, string][] = [
    [t('landing.entity1'), 'IDR'],
    [t('landing.entity2'), 'USD'],
    [t('landing.entity3'), 'IDR'],
  ]
  return (
    <div className="relative mx-auto w-full max-w-[400px] px-2 py-4">
      {/* connectors */}
      <svg
        viewBox="0 0 400 220"
        className="absolute inset-0 h-full w-full text-brand-300"
        preserveAspectRatio="none"
        aria-hidden
      >
        <path d="M70,58 C70,130 200,120 200,168" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 4" />
        <path d="M200,58 C200,120 200,120 200,168" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 4" />
        <path d="M330,58 C330,130 200,120 200,168" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 4" />
      </svg>

      {/* entity chips */}
      <div className="relative flex justify-between">
        {entities.map(([name, ccy]) => (
          <div
            key={name}
            className="w-[30%] rounded-xl border border-line bg-surface p-2.5 text-center shadow-sm"
          >
            <div className="grid place-items-center">
              <span className="grid size-7 place-items-center rounded-lg bg-brand-50 text-brand-600">
                <Building2 className="size-4" />
              </span>
            </div>
            <div className="mt-1.5 truncate text-[11px] font-semibold text-ink">{name}</div>
            <div className="tnum text-[10px] font-bold text-ink-3">{ccy}</div>
          </div>
        ))}
      </div>

      {/* group node */}
      <div className="relative mx-auto mt-[54px] w-[54%] rounded-2xl border border-brand-200 bg-gradient-to-br from-brand-500 to-brand-700 p-3 text-center shadow-md">
        <div className="text-[11px] font-bold uppercase tracking-wide text-white/80">
          {t('landing.groupLabel')}
        </div>
        <div className="tnum font-display text-[15px] font-extrabold text-white">USD · IFRS</div>
      </div>
    </div>
  )
}

// ── Verticals strip (honest social proof: the modules Native actually ships) ─────────────────────

export function VerticalStrip() {
  const { t } = useTranslation()
  const verticals: [LucideIcon, string][] = [
    [Utensils, t('landing.vRestaurant')],
    [ShoppingBag, t('landing.vRetail')],
    [Car, t('landing.vCarwash')],
    [Briefcase, t('landing.vServices')],
    [Building2, t('landing.vFranchise')],
  ]
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-8 gap-y-4">
      {verticals.map(([Icon, label]) => (
        <span key={label} className="inline-flex items-center gap-2 text-ink-3">
          <Icon className="size-4.5" />
          <span className="text-[13px] font-bold tracking-tight">{label}</span>
        </span>
      ))}
    </div>
  )
}
