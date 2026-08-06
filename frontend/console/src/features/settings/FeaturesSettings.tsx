import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Check, LogOut, Sparkles } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { ToggleRow } from '@/components/ui/ToggleRow'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { useTierAccess, FEATURE_MIN_TIER, type FeatureKey } from '@/lib/featureTier'
import { cn } from '@/lib/cn'
import { useSetPlanTier } from './api'

/**
 * The FREE-tier and FULL-only feature keys, in `FEATURE_MIN_TIER`'s own (insertion) order — the
 * two plan cards below are DERIVED from the single source of truth in `lib/featureTier.ts`, never
 * a hand-kept duplicate list, so a future map edit (moving a feature between tiers) updates this
 * page's copy automatically.
 */
const FREE_FEATURE_KEYS = (Object.keys(FEATURE_MIN_TIER) as FeatureKey[]).filter(
  (key) => FEATURE_MIN_TIER[key] === 'FREE',
)
const FULL_ONLY_FEATURE_KEYS = (Object.keys(FEATURE_MIN_TIER) as FeatureKey[]).filter(
  (key) => FEATURE_MIN_TIER[key] === 'FULL',
)

/**
 * `/settings/features` — the owner-only "Simple mode" toggle (P1 tier-mode,
 * `~/.claude/plans/umkm-tier-mode.md`). A plan-comparison layout: a hero status card with the
 * switch (OFF = FREE, the lean UMKM console; ON = FULL, today's complete back-office), and two
 * read-only plan cards below showing exactly what each tier includes — the current one highlighted.
 * The switch is the single control that mutates state (the cards are informational only), so there
 * is one code path to reason about. Registered as a sibling of `/settings/printer` in App.tsx, but
 * owner-gated there since flipping a company-wide setting is a real authorization decision, unlike
 * the printer's per-device pairing.
 */
export function FeaturesSettings() {
  const { t } = useTranslation()
  const auth = useAuth()
  const { company } = useSession()
  const tierAccess = useTierAccess()
  const setPlanTier = useSetPlanTier({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
  })

  if (!company) return null

  const isExtended = tierAccess.isExtended
  const onToggle = () => {
    setPlanTier.mutate(isExtended ? 'FREE' : 'FULL')
  }
  // Dynamic keys (settings.tier.feature.<FeatureKey>) — this repo's i18n has no typed-key
  // augmentation (see Dashboard.tsx's `vertical.${v}` precedent for the same cast).
  const featureLabel = (key: FeatureKey) =>
    t(`settings.tier.feature.${key}` as Parameters<typeof t>[0])

  return (
    <div className="min-h-screen bg-paper">
      {/* This route renders OUTSIDE the dashboard Shell (owner-only, mirrors /settings/printer's
          registration in App.tsx), so it carries its own minimal topbar — same pattern as /me —
          rather than the bare, chrome-less content PrinterSettings has today. */}
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

      <main className="mx-auto flex w-full max-w-[900px] flex-col gap-7 px-5 py-8 sm:px-8 sm:py-10">
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

        {/* Hero: current plan status + the one control that flips it */}
        <Card
          className={cn(
            'flex flex-col gap-5 rounded-[28px] border p-6 sm:flex-row sm:items-center sm:justify-between sm:p-7',
            isExtended
              ? 'border-brand-200 bg-brand-50/50 dark:bg-brand-500/10'
              : 'border-emerald-line bg-emerald-tint/50',
          )}
        >
          <div>
            <Badge tone={isExtended ? 'info' : 'emerald'}>
              {isExtended ? t('settings.tier.fullPlanName') : t('settings.tier.freePlanName')}
            </Badge>
            <p className="mt-3 max-w-md text-sm leading-relaxed text-ink-2">
              {isExtended ? t('settings.tier.fullPlanTagline') : t('settings.tier.freePlanTagline')}
            </p>
          </div>
          <div className="shrink-0 rounded-2xl border border-line bg-surface p-4 sm:p-5">
            <ToggleRow
              label={t('settings.tier.switchLabel')}
              hint={isExtended ? t('settings.tier.switchOnHint') : t('settings.tier.switchOffHint')}
              checked={isExtended}
              disabled={setPlanTier.isPending}
              onToggle={onToggle}
            />
          </div>
        </Card>

        {setPlanTier.isError ? (
          <Card className="p-4 text-sm text-loss">{t('settings.tier.error')}</Card>
        ) : null}

        {/* Plan comparison — read-only; the hero switch above is the only control that writes. */}
        <div className="grid gap-5 sm:grid-cols-2">
          <PlanCard
            name={t('settings.tier.freePlanName')}
            tagline={t('settings.tier.freePlanTagline')}
            current={!isExtended}
            currentLabel={t('settings.tier.currentPlanBadge')}
            items={FREE_FEATURE_KEYS.map(featureLabel)}
          />
          <PlanCard
            name={t('settings.tier.fullPlanName')}
            tagline={t('settings.tier.fullPlanTagline')}
            current={isExtended}
            currentLabel={t('settings.tier.currentPlanBadge')}
            itemsIntro={t('settings.tier.fullIncludesEverything')}
            items={FULL_ONLY_FEATURE_KEYS.map(featureLabel)}
            accent
          />
        </div>
      </main>
    </div>
  )
}

function PlanCard({
  name,
  tagline,
  current,
  currentLabel,
  items,
  itemsIntro,
  accent,
}: {
  name: string
  tagline: string
  current: boolean
  currentLabel: string
  items: string[]
  itemsIntro?: string
  accent?: boolean
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
        <p className="mt-1 text-sm text-ink-3">{tagline}</p>
      </div>

      {itemsIntro ? (
        <p className="text-xs font-bold uppercase tracking-[0.06em] text-ink-3">{itemsIntro}</p>
      ) : null}

      <ul className="flex flex-col gap-2.5">
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
    </Card>
  )
}
