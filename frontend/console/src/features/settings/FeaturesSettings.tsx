import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Check, LogOut, Sparkles, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { useTierAccess, FEATURE_MIN_TIER, type FeatureKey, type PlanTier } from '@/lib/featureTier'
import { computeMonthlyPrice, PRICING } from '@/lib/pricing'
import { useOutlets } from '@/features/org/api'
import { useEmployees } from '@/features/hr/api'
import { cn } from '@/lib/cn'
import { useSetPlanTier } from './api'

/**
 * Feature lists per tier, DERIVED from the single source of truth in `lib/featureTier.ts`
 * (never hand-kept) — moving a feature between tiers in FEATURE_MIN_TIER updates this page
 * and the landing pricing section automatically.
 */
const KEYS_AT = (tier: PlanTier) =>
  (Object.keys(FEATURE_MIN_TIER) as FeatureKey[]).filter((key) => FEATURE_MIN_TIER[key] === tier)

const PLAN_ORDER: PlanTier[] = ['FREE', 'BASIC', 'FULL']

/**
 * `/settings/features` — the owner-only PLAN page (ADR 0047 three-tier pricing, replacing the
 * ADR 0044 two-way toggle): Gratis / Basic / Premium cards with published prices, a live
 * monthly-price panel computed from the company's ACTUAL usage (outlets + distinct active
 * employees) via `computeMonthlyPrice`, and soft-enforce messaging when usage exceeds the
 * current tier's included counts. Display + soft-enforce phase: choosing a plan flips
 * `plan_tier` (owner-only server guard, unchanged); nothing is charged in-app and nothing
 * hard-blocks (ADR 0047).
 */
export function FeaturesSettings() {
  const { t, i18n } = useTranslation()
  const auth = useAuth()
  const { company } = useSession()
  const tierAccess = useTierAccess()
  const locale = localeOf(i18n.language)
  const setPlanTier = useSetPlanTier({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
  })
  const outletsQuery = useOutlets(company?.companyId ?? null, company?.actor ?? '')
  const employeesQuery = useEmployees({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    orgUnitIds: [],
    enabled: !!company,
  })

  if (!company) return null

  const tier = tierAccess.tier
  // Usage: outlet rows are one-per-outlet; employee rows are employee×assignment, so count
  // DISTINCT ACTIVE employee ids. Either read failing → undefined count → its line hides and
  // the price falls back to base-only (fail quiet — never block the plan page on a read).
  const outletCount = outletsQuery.data?.length
  const employeeCount = employeesQuery.data
    ? new Set(employeesQuery.data.filter((r) => r.status === 'ACTIVE').map((r) => r.employeeId))
        .size
    : undefined
  const price = computeMonthlyPrice(tier, outletCount ?? 0, employeeCount ?? 0)
  const money = (minor: number) => formatMoney(minor, PRICING.currency, locale)

  // Dynamic keys (settings.tier.feature.<FeatureKey>) — no typed-key augmentation in this
  // repo's i18n (the Dashboard `vertical.${v}` precedent).
  const featureLabel = (key: FeatureKey) =>
    t(`settings.tier.feature.${key}` as Parameters<typeof t>[0])
  const planName = (p: PlanTier) =>
    p === 'FREE'
      ? t('settings.tier.freePlanName')
      : p === 'BASIC'
        ? t('settings.tier.basicPlanName')
        : t('settings.tier.fullPlanName')

  return (
    <div className="min-h-[100dvh] bg-paper">
      {/* Own minimal topbar — this route renders OUTSIDE the dashboard Shell (owner-only). */}
      <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        <Link
          to="/"
          className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {t('me.toDashboard')}
        </Link>
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            className="flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink"
          >
            <LogOut className="size-4" />
            <span className="hidden sm:inline">{t('nav.logout')}</span>
          </button>
        ) : null}
      </header>

      <main className="mx-auto flex w-full max-w-[980px] flex-col gap-7 px-5 py-8 sm:px-8 sm:py-10">
        {/* Header */}
        <div className="flex items-start gap-4">
          <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-brand-50 text-brand-600">
            <Sparkles className="size-6" strokeWidth={1.8} />
          </span>
          <div>
            <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
              {t('settings.tier.title')}
            </h1>
            <p className="mt-1.5 max-w-xl text-[15px] leading-relaxed text-ink-3">
              {t('settings.tier.help')}
            </p>
          </div>
        </div>

        {/* Live price panel — the company's ACTUAL quoted month at its current tier + usage. */}
        <Card className="flex flex-col gap-5 rounded-[28px] border border-brand-200 bg-brand-50/40 p-6 dark:bg-brand-500/10 sm:flex-row sm:items-start sm:justify-between sm:p-7">
          <div className="min-w-0">
            <Badge tone="info">
              {t('settings.tier.currentPlanBadge')} · {planName(tier)}
            </Badge>
            <div className="tnum mt-3 font-mono text-[34px] font-bold leading-none tracking-[-0.02em] text-ink">
              {tier === 'FREE' ? t('settings.tier.priceFree') : money(price.totalMinor)}
              {tier !== 'FREE' ? (
                <span className="ml-1 font-sans text-sm font-medium text-ink-3">
                  {t('settings.tier.perMonth')}
                </span>
              ) : null}
            </div>
            <p className="mt-2 text-[13px] leading-relaxed text-ink-3">
              {t('settings.tier.currentUsage')}
              {': '}
              {outletCount != null ? t('settings.tier.usageOutlets', { count: outletCount }) : '—'}
              {employeeCount != null
                ? ` · ${t('settings.tier.usageEmployees', { count: employeeCount })}`
                : ''}
            </p>
          </div>

          {/* Breakdown — only lines that apply, never a synthetic zero. */}
          {tier !== 'FREE' ? (
            <div className="w-full shrink-0 rounded-2xl border border-line bg-surface p-4 sm:w-[300px]">
              <BreakdownRow label={t('settings.tier.priceBase', { plan: planName(tier) })} value={money(price.baseMinor)} />
              {price.extraOutlets > 0 ? (
                <BreakdownRow
                  label={t('settings.tier.outletAddOnLine', { count: price.extraOutlets, price: money(PRICING.extraOutletMinor) })}
                  value={money(price.outletAddOnMinor)}
                />
              ) : null}
              {price.employeePacks > 0 ? (
                <BreakdownRow
                  label={t('settings.tier.employeeAddOnLine', { count: price.employeePacks, size: PRICING.employeePackSize, price: money(PRICING.employeePackMinor) })}
                  value={money(price.employeeAddOnMinor)}
                />
              ) : null}
              <div className="mt-2 flex items-baseline justify-between border-t border-line pt-2">
                <span className="text-[13px] font-bold text-ink">{t('settings.tier.totalPerMonth')}</span>
                <span className="tnum font-mono text-[15px] font-bold text-ink">{money(price.totalMinor)}</span>
              </div>
            </div>
          ) : null}
        </Card>

        {/* Soft-enforce: usage above the current tier's included counts (ADR 0047 — warn, never block). */}
        {!price.withinIncluded ? (
          <div className="flex items-start gap-3 rounded-2xl bg-amber-tint px-4 py-3.5 text-sm leading-relaxed text-amber-2">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            <span>
              {tier === 'FREE'
                ? t('settings.tier.overLimitFreeBody')
                : t('settings.tier.overLimitBody')}
            </span>
          </div>
        ) : null}

        {setPlanTier.isError ? (
          <Card className="p-4 text-sm text-loss">{t('settings.tier.error')}</Card>
        ) : null}

        {/* The three plans. Cumulative feature lists derived from FEATURE_MIN_TIER. */}
        <div className="grid gap-5 max-sm:grid-cols-1 sm:grid-cols-3">
          {PLAN_ORDER.map((p, index) => (
            <PlanCard
              key={p}
              name={planName(p)}
              tagline={t(
                p === 'FREE'
                  ? 'settings.tier.freePlanTagline'
                  : p === 'BASIC'
                    ? 'settings.tier.basicPlanTagline'
                    : 'settings.tier.fullPlanTagline',
              )}
              priceLine={
                p === 'FREE'
                  ? t('settings.tier.priceFree')
                  : `${money(PRICING.tiers[p].baseMinor)}${t('settings.tier.perMonth')}`
              }
              addOnHint={
                p === 'FREE'
                  ? t('settings.tier.freeIncludesHint', {
                      outlets: PRICING.tiers.FREE.outletsIncluded,
                      employees: PRICING.employeesIncluded,
                    })
                  : t('settings.tier.addOnHint', {
                      outlets: PRICING.tiers[p].outletsIncluded,
                      employees: PRICING.employeesIncluded,
                      outletPrice: money(PRICING.extraOutletMinor),
                      packSize: PRICING.employeePackSize,
                      packPrice: money(PRICING.employeePackMinor),
                    })
              }
              current={tier === p}
              currentLabel={t('settings.tier.currentPlanBadge')}
              itemsIntro={
                index > 0
                  ? t('settings.tier.includesEverythingIn', { plan: planName(PLAN_ORDER[index - 1]) })
                  : undefined
              }
              items={KEYS_AT(p).map(featureLabel)}
              accent={p === 'FULL'}
              chooseLabel={t('settings.tier.choosePlan')}
              pending={setPlanTier.isPending}
              onChoose={tier === p ? undefined : () => setPlanTier.mutate(p)}
            />
          ))}
        </div>
      </main>
    </div>
  )
}

function BreakdownRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1">
      <span className="min-w-0 text-[13px] leading-snug text-ink-3">{label}</span>
      <span className="tnum shrink-0 font-mono text-[13px] font-semibold text-ink-2">{value}</span>
    </div>
  )
}

function PlanCard({
  name,
  tagline,
  priceLine,
  addOnHint,
  current,
  currentLabel,
  items,
  itemsIntro,
  accent,
  chooseLabel,
  pending,
  onChoose,
}: {
  name: string
  tagline: string
  priceLine: string
  addOnHint: string
  current: boolean
  currentLabel: string
  items: string[]
  itemsIntro?: string
  accent?: boolean
  chooseLabel: string
  pending: boolean
  onChoose?: () => void
}) {
  return (
    <Card
      className={cn(
        'relative flex flex-col gap-4 p-6 pt-7 transition-colors',
        current ? 'border-2 border-emerald ring-4 ring-emerald/10' : 'border border-line',
      )}
    >
      {current ? (
        <span className="absolute -top-3 left-6 rounded-full bg-emerald px-3 py-1 text-[11px] font-bold uppercase tracking-wide text-on-emerald shadow-sm">
          {currentLabel}
        </span>
      ) : null}

      <div>
        <h2 className="font-display text-lg font-bold text-ink">{name}</h2>
        <div className="tnum mt-1.5 font-mono text-[22px] font-bold tracking-[-0.01em] text-ink">
          {priceLine}
        </div>
        <p className="mt-1.5 text-sm text-ink-3">{tagline}</p>
      </div>

      {itemsIntro ? (
        <p className="text-xs font-bold uppercase tracking-[0.06em] text-ink-3">{itemsIntro}</p>
      ) : null}

      <ul className="flex flex-1 flex-col gap-2.5">
        {items.map((item) => (
          <li key={item} className="flex items-start gap-2.5 text-sm text-ink-2">
            <span
              className={cn(
                'mt-0.5 grid size-5 shrink-0 place-items-center rounded-full',
                accent ? 'bg-brand-50 text-brand-600' : 'bg-tint-profit text-profit',
              )}
              aria-hidden
            >
              <Check className="size-3" strokeWidth={2.5} />
            </span>
            {item}
          </li>
        ))}
      </ul>

      <p className="text-xs leading-relaxed text-ink-3">{addOnHint}</p>

      {onChoose ? (
        <Button
          variant={accent ? 'primary' : 'outline'}
          className="w-full"
          disabled={pending}
          onClick={onChoose}
        >
          {chooseLabel}
        </Button>
      ) : null}
    </Card>
  )
}
