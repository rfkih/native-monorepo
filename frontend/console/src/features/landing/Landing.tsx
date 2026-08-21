/**
 * Public marketing landing page — the product's front door at `/`.
 *
 * Rendered for UNAUTHENTICATED visitors (the OidcAuthProvider no longer force-redirects to Keycloak;
 * login is explicit). "Masuk" → auth.login() (Keycloak PKCE redirect); demo CTAs anchor to the #demo
 * band whose primary opens a demo-request mail draft; "Coba gratis" → /signup.
 *
 * Layout from the Landing.dc.html redesign (DesignSync project, 2026-08): hero over the warung photo
 * with the dashboard mock, verticals strip, proof band, "Produk" section around the offline-POS
 * card, dark "Kepatuhan" panel, a founder-note card + product-fact rows (every figure is an
 * architectural fact or capability — no invented customer measurements or testimonials; never pair
 * an invented quote with a real person's photo), photo CTA band, and a sitemap footer. Fully
 * localized (react-i18next), locale-aware
 * `Intl` for money/percentages, theme-aware via tokens; photos are hashed local assets — no external
 * calls at runtime.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  AlertTriangle,
  ArrowRight,
  BarChart3,
  Check,
  Clock,
  DollarSign,
  Download,
  Lock,
  Moon,
  Network,
  Quote,
  Store,
  Sun,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { BrandMark, Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { FEATURE_MIN_TIER, type FeatureKey, type PlanTier } from '@/lib/featureTier'
import { PRICING } from '@/lib/pricing'
import { Backdrop, DashboardMock, FxChip, PosReceiptMock, SaleToast, VerticalStrip } from './art'
import { Photo } from './Photo'
import { ctaPhoto, heroPhoto, posPhoto } from './photos'
import { MOCK_REVENUE, money } from './mock'

/** Demo-request mail target — the product's contact address (demonstration build). */
const CONTACT_EMAIL = 'halo@native.id'

/**
 * Android POS app download — ALWAYS the PROD app. Absolute on purpose: this same console build also
 * serves the UAT origin, where a relative `/native-app-latest.apk` would silently hand out the UAT
 * flavor (a different installable app, ADR 0058). The alias 302s to the current
 * `native-app-prod-v<N>.apk` (docker/prod/edge.conf, bumped per release) so this URL never changes.
 */
const ANDROID_APP_URL = 'https://app.native-app.my.id/native-app-latest.apk'

// ── Scroll-reveal (flash-free, reduced-motion-safe) ──────────────────────────────────────────────

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function Reveal({
  children,
  className,
  delay = 0,
}: {
  children: ReactNode
  className?: string
  delay?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = prefersReducedMotion()
  // Reduced-motion (or no IntersectionObserver) → render visible immediately, no animation.
  const [shown, setShown] = useState(() => reduce || typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    if (reduce || typeof IntersectionObserver === 'undefined') return
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(
      (entries) =>
        entries.forEach((e) => {
          if (e.isIntersecting) {
            setShown(true)
            io.disconnect()
          }
        }),
      { threshold: 0.12, rootMargin: '0px 0px -6% 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [reduce])

  return (
    <div
      ref={ref}
      className={className}
      style={
        reduce
          ? undefined
          : {
              opacity: shown ? 1 : 0,
              transform: shown ? 'none' : 'translateY(16px)',
              transition: `opacity .6s cubic-bezier(.2,.7,.2,1) ${delay}ms, transform .6s cubic-bezier(.2,.7,.2,1) ${delay}ms`,
            }
      }
    >
      {children}
    </div>
  )
}

// ── Small shared pieces ──────────────────────────────────────────────────────────────────────────

function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={t('a11y.toggleTheme')}
      className="grid size-9 place-items-center rounded-lg border border-line bg-surface text-ink-2 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {theme === 'dark' ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
    </button>
  )
}

/** Anchor styled as the primary/outline Button recipe — for in-page and mailto CTAs. */
function CtaLink({
  href,
  variant = 'primary',
  size = 'sm',
  className,
  children,
}: {
  href: string
  variant?: 'primary' | 'outline'
  size?: 'sm' | 'xl'
  className?: string
  children: ReactNode
}) {
  return (
    <a
      href={href}
      className={cn(
        'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-xl transition-colors duration-150',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        size === 'xl' ? 'h-[54px] px-6 text-[15px]' : 'h-10 px-4 text-[13px]',
        variant === 'primary'
          ? 'bg-emerald font-bold text-on-emerald shadow-sm hover:bg-emerald-2'
          : 'border border-line bg-surface font-semibold text-ink hover:bg-hover',
        className,
      )}
    >
      {children}
    </a>
  )
}

function Bullet({ children }: { children: ReactNode }) {
  return (
    <li className="flex items-start gap-2.5 text-[14.5px] leading-normal font-medium text-ink-2">
      <Check className="mt-[3px] size-4 shrink-0 text-profit-ink" strokeWidth={2.2} />
      {children}
    </li>
  )
}

function IconTile({ icon: Icon }: { icon: LucideIcon }) {
  return (
    <span className="grid size-10 place-items-center rounded-[13px] bg-brand-50 text-brand-700 dark:bg-emerald-tint dark:text-emerald-2">
      <Icon className="size-5" strokeWidth={1.8} />
    </span>
  )
}

// ── Header ───────────────────────────────────────────────────────────────────────────────────────

function Header({ onSignIn }: { onSignIn: () => void }) {
  const { t } = useTranslation()
  const navLink =
    'flex h-9 items-center rounded-[10px] px-3.5 text-sm font-semibold text-ink-2 transition-colors hover:bg-hover hover:text-ink'
  return (
    <header className="sticky top-0 z-30 border-b border-line/70 bg-paper/90 backdrop-blur-xl">
      <div className="mx-auto flex h-[72px] w-full max-w-6xl items-center gap-3 px-5">
        <Link to="/" aria-label={t('app.name')} className="shrink-0">
          <Wordmark />
        </Link>
        <nav className="ml-5 hidden items-center gap-1 md:flex">
          <a href="#produk" className={navLink}>
            {t('landing.navProduct')}
          </a>
          <a href="#kepatuhan" className={navLink}>
            {t('landing.navCompliance')}
          </a>
          <a href="#harga" className={navLink}>
            {t('landing.navPricing')}
          </a>
        </nav>
        <div className="ml-auto flex items-center gap-1.5 sm:gap-2.5">
          <div className="hidden sm:block">
            <LanguageSwitcher />
          </div>
          <ThemeToggle />
          <Button variant="ghost" onClick={onSignIn} className="px-2.5 sm:px-4">
            {t('landing.signIn')}
          </Button>
          <CtaLink href="#demo" className="px-3 sm:px-4">
            {t('landing.navDemo')}
          </CtaLink>
        </div>
      </div>
    </header>
  )
}

// ── Hero ─────────────────────────────────────────────────────────────────────────────────────────

function Hero() {
  const { t } = useTranslation()
  return (
    <section className="relative isolate overflow-hidden">
      {/* Full-bleed photo layer: warung scene → brand tint → theme-aware paper scrim. The scrim is
          effectively solid over the copy column and lifts toward the right, so the photo emerges
          under the dashboard mock; `--color-paper` flips with the theme, so dark mode is free. */}
      <div aria-hidden className="absolute inset-0 -z-10">
        <Photo
          photo={heroPhoto}
          alt=""
          priority
          sizes="100vw"
          className="absolute inset-0 h-full w-full object-cover"
        />
        <div className="absolute inset-0 bg-brand-900/35 mix-blend-multiply" />
        <div
          className="absolute inset-0"
          style={{
            background:
              'linear-gradient(90deg, var(--color-paper) 0%, var(--color-paper) 34%, color-mix(in srgb, var(--color-paper) 72%, transparent) 58%, color-mix(in srgb, var(--color-paper) 22%, transparent) 100%)',
          }}
        />
        <div className="absolute inset-0 bg-paper/85 lg:hidden" />
        <div className="absolute inset-x-0 bottom-0 h-24 bg-gradient-to-b from-transparent to-paper" />
      </div>
      <div className="mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-14 px-5 pb-24 pt-14 lg:grid-cols-[1.05fr_0.95fr] lg:pt-20">
        {/* Left — copy + CTAs, staggered entrance */}
        <div>
          <span
            className="reveal inline-flex items-center gap-2 rounded-full border border-line bg-ink-50 py-1.5 pl-2 pr-3.5"
            style={{ animationDelay: '40ms' }}
          >
            <span className="rounded-full bg-surface px-2 py-[3px] text-[10.5px] font-bold tracking-wide text-ink shadow-sm">
              {t('landing.heroBadgeCount')}
            </span>
            <span className="text-[12.5px] font-semibold text-ink-2">{t('landing.heroBadge')}</span>
          </span>
          <h1
            className="reveal mt-5 font-display text-[40px] font-extrabold leading-[1.05] tracking-[-0.03em] text-ink text-balance sm:text-[54px]"
            style={{ animationDelay: '110ms' }}
          >
            {t('landing.heroTitle')}
          </h1>
          <p
            className="reveal mt-5 max-w-[520px] text-[17px] leading-relaxed text-ink-2 text-pretty"
            style={{ animationDelay: '180ms' }}
          >
            {t('landing.heroSubtitle')}
          </p>
          <div
            className="reveal mt-8 flex flex-col gap-3 sm:flex-row sm:items-center"
            style={{ animationDelay: '250ms' }}
          >
            <CtaLink href="#demo" size="xl" className="w-full sm:w-auto">
              {t('landing.heroPrimary')} <ArrowRight className="size-4" />
            </CtaLink>
            <CtaLink href="#produk" variant="outline" size="xl" className="w-full sm:w-auto">
              {t('landing.heroSecondary')}
            </CtaLink>
          </div>
          <p
            className="reveal mt-6 flex items-start gap-2.5 text-[13px] font-medium text-ink-3"
            style={{ animationDelay: '320ms' }}
          >
            <Check className="mt-0.5 size-4 shrink-0 text-profit-ink" strokeWidth={2.1} />
            {t('landing.heroNote')}
          </p>
          <div className="reveal mt-4" style={{ animationDelay: '380ms' }}>
            <CtaLink href={ANDROID_APP_URL} variant="outline">
              <Download className="size-4" /> {t('landing.downloadAndroid')}
            </CtaLink>
          </div>
        </div>

        {/* Right — the product screenshot + floating accent cards */}
        <div className="reveal relative mx-auto lg:justify-self-end" style={{ animationDelay: '280ms' }}>
          <DashboardMock />
          <SaleToast className="floaty absolute -bottom-6 -left-6 hidden w-[284px] md:flex" />
          <div className="floaty absolute -right-5 top-10 hidden md:block [animation-delay:1.4s]">
            <FxChip />
          </div>
        </div>
      </div>

      {/* Verticals strip — inside the hero so it sits on the photo's bottom fade */}
      <div className="mx-auto w-full max-w-6xl px-5 pb-[76px]">
        <p className="text-center text-xs font-semibold uppercase tracking-[0.16em] text-ink-3">
          {t('landing.trustLabel')}
        </p>
        <div className="mt-6">
          <VerticalStrip />
        </div>
      </div>
    </section>
  )
}

// ── Proof band — only claims the repo can back; unfilled figures stay labelled slots ─────────────

function ProofBar() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const stats: [string, string][] = [
    [money(MOCK_REVENUE, locale, true), t('landing.proofRevenueLabel')],
    [t('landing.proofVarianceValue'), t('landing.proofVarianceLabel')],
    [t('landing.proofTraceValue'), t('landing.proofTraceLabel')],
    [t('landing.proofOfflineValue'), t('landing.proofOfflineLabel')],
  ]
  return (
    <section className="px-5 pt-3.5">
      <Reveal className="mx-auto w-full max-w-6xl">
        {/* gap-px over a line-colored base = hairline dividers on both axes at every breakpoint */}
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-card border border-line bg-line shadow-sm sm:grid-cols-2 lg:grid-cols-4">
          {stats.map(([value, label]) => (
            <div key={label} className="bg-surface px-6 py-7">
              <div className="tnum font-mono text-[28px] font-bold tracking-[-0.02em] text-ink">
                {value}
              </div>
              <div className="mt-2 text-[13px] font-medium leading-relaxed text-ink-3">{label}</div>
            </div>
          ))}
        </div>
      </Reveal>
    </section>
  )
}

// ── "Produk" — one set of books, the offline POS card + four feature cards ───────────────────────

function ProductSection() {
  const { t } = useTranslation()
  const features: { icon: LucideIcon; title: string; body: string }[] = [
    { icon: Users, title: t('landing.f1Title'), body: t('landing.f1Body') },
    { icon: BarChart3, title: t('landing.f2Title'), body: t('landing.f2Body') },
    { icon: DollarSign, title: t('landing.f3Title'), body: t('landing.f3Body') },
    { icon: Network, title: t('landing.f4Title'), body: t('landing.f4Body') },
  ]
  return (
    <section id="produk" className="scroll-mt-20 px-5 pt-24">
      <div className="mx-auto w-full max-w-6xl">
        <Reveal className="max-w-2xl">
          <div className="text-[11px] font-semibold uppercase tracking-[0.09em] text-brand-700 dark:text-emerald-2">
            {t('landing.productKicker')}
          </div>
          <h2 className="mt-3 font-display text-[30px] font-extrabold leading-[1.12] tracking-[-0.025em] text-ink text-balance sm:text-[38px]">
            {t('landing.productTitle')}
          </h2>
          <p className="mt-3.5 text-[16px] leading-relaxed text-ink-2 text-pretty">
            {t('landing.productLede')}
          </p>
        </Reveal>

        <div className="mt-10 grid grid-cols-1 gap-5 lg:grid-cols-2">
          {/* Offline-POS hero card — copy column + photo with the receipt mock floating over it */}
          <Reveal className="lg:col-span-2">
            <div className="grid overflow-hidden rounded-card border border-line bg-surface shadow-sm lg:grid-cols-[1.05fr_1fr]">
              <div className="p-8 sm:p-10">
                <IconTile icon={Store} />
                <h3 className="mt-4.5 font-display text-[22px] font-bold leading-[1.25] tracking-[-0.015em] text-ink">
                  {t('landing.posCardTitle')}
                </h3>
                <p className="mt-3 text-[15px] leading-relaxed text-ink-2 text-pretty">
                  {t('landing.posCardBody')}
                </p>
                <ul className="mt-5 space-y-2.5">
                  <Bullet>{t('landing.posPoint1')}</Bullet>
                  <Bullet>{t('landing.posPoint2')}</Bullet>
                  <Bullet>{t('landing.posPoint3')}</Bullet>
                </ul>
              </div>
              <div className="relative aspect-[4/3] lg:aspect-auto">
                <Photo
                  photo={posPhoto}
                  alt={t('landing.posPhotoAlt')}
                  sizes="(min-width: 1024px) 560px, 92vw"
                  className="absolute inset-0 h-full w-full object-cover"
                />
                <PosReceiptMock className="absolute bottom-4 right-4 w-[240px] p-4 shadow-lg sm:w-[264px]" />
              </div>
            </div>
          </Reveal>

          {features.map((f, i) => (
            <Reveal key={f.title} delay={(i % 2) * 90}>
              <div className="h-full rounded-card border border-line bg-surface p-8 shadow-sm">
                <IconTile icon={f.icon} />
                <h3 className="mt-4 font-display text-[19px] font-bold tracking-[-0.01em] text-ink">
                  {f.title}
                </h3>
                <p className="mt-2 text-[14.5px] leading-relaxed text-ink-2 text-pretty">{f.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── "Kepatuhan" — the dark compliance panel ──────────────────────────────────────────────────────

function ComplianceSection() {
  const { t } = useTranslation()
  const cards: { icon: LucideIcon; title: string; body: string }[] = [
    { icon: Clock, title: t('landing.t1Title'), body: t('landing.t1Body') },
    { icon: Lock, title: t('landing.t2Title'), body: t('landing.t2Body') },
    { icon: AlertTriangle, title: t('landing.t3Title'), body: t('landing.t3Body') },
    { icon: Download, title: t('landing.t4Title'), body: t('landing.t4Body') },
  ]
  return (
    <section id="kepatuhan" className="scroll-mt-20 px-5 pt-24">
      {/* The panel is dark in BOTH themes (fixed ink #0e1116, green accent #5fcb8b = the dark-mode
          profit ink) — token-flipping colors would break it in one theme or the other. */}
      <Reveal className="mx-auto w-full max-w-6xl">
        <div className="rounded-[24px] border border-transparent bg-[#0e1116] p-8 text-white sm:p-12 dark:border-white/10">
          <div className="grid grid-cols-1 items-start gap-10 lg:grid-cols-[1fr_1.15fr] lg:gap-14">
            <div>
              <div className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[#5fcb8b]">
                {t('landing.complianceKicker')}
              </div>
              <h2 className="mt-3 font-display text-[28px] font-extrabold leading-[1.15] tracking-[-0.025em] text-white text-balance sm:text-[34px]">
                {t('landing.complianceTitle')}
              </h2>
              <p className="mt-3.5 text-[15.5px] leading-relaxed text-white/70 text-pretty">
                {t('landing.complianceLede')}
              </p>
            </div>
            <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2">
              {cards.map((c) => (
                <div key={c.title} className="rounded-2xl border border-white/10 bg-white/[0.06] p-5">
                  <c.icon className="size-5 text-[#5fcb8b]" strokeWidth={1.8} />
                  <div className="mt-3 text-[14.5px] font-bold text-white">{c.title}</div>
                  <div className="mt-1.5 text-[13px] leading-normal text-white/60">{c.body}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Reveal>
    </section>
  )
}

// ── Pricing (ADR 0047) — rendered from lib/pricing.ts + FEATURE_MIN_TIER, never hand-kept ────────

function PricingSection() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const money = (minor: number) => formatMoney(minor, PRICING.currency, locale)
  const featureLabel = (key: FeatureKey) =>
    t(`settings.tier.feature.${key}` as Parameters<typeof t>[0])
  const keysAt = (tier: PlanTier) =>
    (Object.keys(FEATURE_MIN_TIER) as FeatureKey[]).filter((k) => FEATURE_MIN_TIER[k] === tier)
  const planName = (p: PlanTier) =>
    p === 'FREE'
      ? t('settings.tier.freePlanName')
      : p === 'BASIC'
        ? t('settings.tier.basicPlanName')
        : t('settings.tier.fullPlanName')

  const plans: { tier: PlanTier; tagline: string; highlight?: boolean }[] = [
    { tier: 'FREE', tagline: t('settings.tier.freePlanTagline') },
    { tier: 'BASIC', tagline: t('settings.tier.basicPlanTagline'), highlight: true },
    { tier: 'FULL', tagline: t('settings.tier.fullPlanTagline') },
  ]

  return (
    <section id="harga" className="scroll-mt-20 px-5 pt-24">
      <Reveal className="mx-auto w-full max-w-6xl">
        <div className="text-center">
          <div className="text-[11px] font-semibold uppercase tracking-[0.09em] text-brand-700">
            {t('landing.pricing.kicker')}
          </div>
          <h2 className="mt-3 font-display text-[28px] font-extrabold leading-[1.15] tracking-[-0.025em] text-ink text-balance sm:text-[34px]">
            {t('landing.pricing.title')}
          </h2>
          <p className="mx-auto mt-3.5 max-w-2xl text-[15.5px] leading-relaxed text-ink-3 text-pretty">
            {t('landing.pricing.lede')}
          </p>
        </div>

        <div className="mt-10 grid grid-cols-1 gap-5 sm:grid-cols-3">
          {plans.map(({ tier, tagline, highlight }, index) => (
            <div
              key={tier}
              className={cn(
                'flex flex-col gap-4 rounded-[24px] border bg-surface p-6 sm:p-7',
                highlight ? 'border-brand-500 shadow-lg ring-4 ring-brand-500/10' : 'border-line',
              )}
            >
              <div>
                <div className="text-[13px] font-bold uppercase tracking-[0.06em] text-ink-3">
                  {planName(tier)}
                </div>
                <div className="tnum mt-2 font-mono text-[28px] font-bold tracking-[-0.02em] text-ink">
                  {tier === 'FREE' ? (
                    t('settings.tier.priceFree')
                  ) : (
                    <>
                      {money(PRICING.tiers[tier].baseMinor)}
                      <span className="font-sans text-sm font-medium text-ink-3">
                        {t('settings.tier.perMonth')}
                      </span>
                    </>
                  )}
                </div>
                <p className="mt-2 text-sm leading-relaxed text-ink-3">{tagline}</p>
              </div>
              <ul className="flex flex-1 flex-col gap-2.5">
                {index > 0 ? (
                  <li className="text-xs font-bold uppercase tracking-[0.06em] text-ink-3">
                    {t('settings.tier.includesEverythingIn', {
                      plan: planName(plans[index - 1].tier),
                    })}
                  </li>
                ) : null}
                {keysAt(tier).map((key) => (
                  <li key={key} className="flex items-start gap-2.5 text-sm text-ink-2">
                    <span
                      className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full bg-tint-profit text-profit"
                      aria-hidden
                    >
                      <Check className="size-3" strokeWidth={2.5} />
                    </span>
                    {featureLabel(key)}
                  </li>
                ))}
              </ul>
              {tier === 'FREE' ? (
                <Link
                  to="/signup"
                  className="inline-flex h-11 w-full items-center justify-center rounded-xl border border-line bg-surface text-sm font-bold text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
                >
                  {t('landing.pricing.ctaFree')}
                </Link>
              ) : (
                <CtaLink
                  href="#demo"
                  variant={highlight ? 'primary' : 'outline'}
                  className="w-full"
                >
                  {t('landing.pricing.ctaPaid')}
                </CtaLink>
              )}
            </div>
          ))}
        </div>

        {/* The usage add-on rules, from the same price sheet — one sentence, no fine print. */}
        <p className="mx-auto mt-6 max-w-3xl text-center text-[13px] leading-relaxed text-ink-3">
          {t('landing.pricing.addOns', {
            outlets: PRICING.tiers.BASIC.outletsIncluded,
            employees: PRICING.employeesIncluded,
            outletPrice: money(PRICING.extraOutletMinor),
            packSize: PRICING.employeePackSize,
            packPrice: money(PRICING.employeePackMinor),
          })}
        </p>
      </Reveal>
    </section>
  )
}

// ── Founder note + product facts — no invented customer quotes or measurements ───────────────────

function SocialProofSection() {
  const { t } = useTranslation()
  const outcomes: [string, string][] = [
    [t('landing.outcome1Value'), t('landing.outcome1Label')],
    [t('landing.outcome2Value'), t('landing.outcome2Label')],
    [t('landing.outcome3Value'), t('landing.outcome3Label')],
  ]
  return (
    <section className="px-5 pt-24">
      <div className="mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 lg:grid-cols-[1.15fr_1fr]">
        <Reveal>
          <div className="rounded-[24px] border border-line bg-ink-50/60 p-8 sm:p-10">
            <Quote className="size-[30px] text-brand-100" fill="currentColor" strokeWidth={0} />
            <blockquote className="mt-5 text-[19px] font-medium leading-[1.5] tracking-[-0.01em] text-ink-2 text-pretty sm:text-[22px]">
              {t('landing.quoteText')}
            </blockquote>
            <div className="mt-6 flex items-center gap-3.5">
              <span className="grid size-[52px] shrink-0 place-items-center rounded-full bg-gradient-to-br from-brand-500 to-brand-800">
                <BrandMark size={24} />
              </span>
              <div>
                <div className="text-[15px] font-bold text-ink">{t('landing.quoteAuthor')}</div>
                <div className="mt-0.5 text-[13px] font-medium text-ink-3">
                  {t('landing.quoteRole')}
                </div>
              </div>
            </div>
          </div>
        </Reveal>
        <Reveal delay={90}>
          <div className="flex flex-col gap-3.5">
            {outcomes.map(([value, label]) => (
              <div
                key={label}
                className="flex items-center gap-4 rounded-card border border-line bg-surface px-6 py-5 shadow-sm"
              >
                <span className="tnum min-w-[76px] shrink-0 whitespace-nowrap font-mono text-[24px] font-bold tracking-[-0.02em] text-brand-700 dark:text-emerald-2 sm:text-[26px]">
                  {value}
                </span>
                <span className="text-[14px] font-medium leading-normal text-ink-2">{label}</span>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  )
}

// ── Final CTA band ───────────────────────────────────────────────────────────────────────────────

function CtaBand() {
  const { t } = useTranslation()
  const mailto = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(t('landing.demoMailSubject'))}`
  return (
    <section id="demo" className="scroll-mt-20 px-5 pt-24">
      <Reveal className="relative mx-auto w-full max-w-6xl overflow-hidden rounded-[24px]">
        <Photo
          photo={ctaPhoto}
          alt=""
          sizes="(min-width: 1200px) 1152px, 96vw"
          className="h-[420px] w-full object-cover sm:h-[340px]"
        />
        {/* Brand duotone over the carwash — the brand ramp is theme-invariant, so fixed vars are safe */}
        <div
          aria-hidden
          className="absolute inset-0"
          style={{
            background:
              'linear-gradient(105deg, color-mix(in srgb, var(--color-brand-900) 96%, transparent) 0%, color-mix(in srgb, var(--color-brand-800) 90%, transparent) 52%, color-mix(in srgb, var(--color-brand-600) 72%, transparent) 100%)',
          }}
        />
        <div className="absolute inset-0 flex flex-col items-center justify-center px-6 text-center sm:px-14">
          <h2 className="max-w-[660px] font-display text-[28px] font-extrabold leading-[1.15] tracking-[-0.025em] text-white text-balance sm:text-[36px]">
            {t('landing.ctaTitle')}
          </h2>
          <p className="mt-3.5 max-w-[540px] text-[15px] leading-relaxed text-white/80 sm:text-[16px]">
            {t('landing.ctaSubtitle')}
          </p>
          <div className="mt-7 flex flex-col gap-3 sm:flex-row">
            <a
              href={mailto}
              className="inline-flex h-[54px] items-center justify-center rounded-xl bg-white px-6 text-[15px] font-bold text-brand-800 transition-colors hover:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
            >
              {t('landing.ctaPrimary')}
            </a>
            <Link
              to="/signup"
              className="inline-flex h-[54px] items-center justify-center rounded-xl border border-white/35 px-6 text-[15px] font-semibold text-white transition-colors hover:bg-white/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
            >
              {t('landing.ctaSecondary')}
            </Link>
            <a
              href={ANDROID_APP_URL}
              className="inline-flex h-[54px] items-center justify-center gap-2 rounded-xl border border-white/35 px-6 text-[15px] font-semibold text-white transition-colors hover:bg-white/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
            >
              <Download className="size-4" /> {t('landing.downloadAndroid')}
            </a>
          </div>
        </div>
      </Reveal>
    </section>
  )
}

// ── Footer ───────────────────────────────────────────────────────────────────────────────────────

function Footer() {
  const { t } = useTranslation()
  const year = new Date().getFullYear()
  const salesMailto = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(t('landing.demoMailSubject'))}`
  const columns: { heading: string; links: { label: string; href: string }[] }[] = [
    {
      heading: t('landing.fProductHeading'),
      links: [
        { label: t('landing.fPos'), href: '#produk' },
        { label: t('landing.fPayroll'), href: '#produk' },
        { label: t('landing.fStatements'), href: '#produk' },
        { label: t('landing.fArAp'), href: '#produk' },
        { label: t('landing.navPricing'), href: '#harga' },
      ],
    },
    {
      heading: t('landing.fCompanyHeading'),
      links: [
        { label: t('landing.fAbout'), href: '#' },
        { label: t('landing.fCompliance'), href: '#kepatuhan' },
        { label: t('landing.fSecurity'), href: '#kepatuhan' },
        { label: t('landing.fContact'), href: `mailto:${CONTACT_EMAIL}` },
      ],
    },
    {
      heading: t('landing.fSupportHeading'),
      links: [
        { label: t('landing.fDocs'), href: '#' },
        { label: t('landing.fStatus'), href: '#' },
        { label: t('landing.fSales'), href: salesMailto },
      ],
    },
  ]
  return (
    <footer className="mt-[88px] border-t border-line">
      <div className="mx-auto flex w-full max-w-6xl flex-col justify-between gap-10 px-5 pb-13 pt-11 md:flex-row">
        <div className="max-w-[300px]">
          <Wordmark />
          <p className="mt-4 text-[13.5px] leading-relaxed text-ink-3">
            {t('landing.footerCompany')}
            <br />
            {t('landing.footerAddress')}
          </p>
        </div>
        <div className="flex flex-wrap gap-10 lg:gap-14">
          {columns.map((col) => (
            <div key={col.heading}>
              <div className="text-xs font-bold text-ink">{col.heading}</div>
              <div className="mt-3 flex flex-col gap-2.5">
                {col.links.map((l) => (
                  <a
                    key={l.label}
                    href={l.href}
                    className="text-[13.5px] font-medium text-ink-3 transition-colors hover:text-ink"
                  >
                    {l.label}
                  </a>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="border-t border-line">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center justify-between gap-3 px-5 py-5 text-[12.5px] text-ink-3 sm:flex-row">
          <span>{t('landing.footerRights', { year })}</span>
          <span>{t('landing.footerPhotoCredit')}</span>
        </div>
      </div>
    </footer>
  )
}

// ── SEO head sync ────────────────────────────────────────────────────────────────────────────────

/**
 * Keeps the document head in step with the active language (react-i18next). index.html ships the
 * English baseline a no-JS crawler / social scraper sees; once the SPA runs this rewrites the same
 * tags IN PLACE (no duplicates) to the resolved language — <title>, meta description, the OG/Twitter
 * title+description mirrors, `og:locale` — and stamps `<html lang>`. The `hreflang` cluster stays
 * static in index.html (identical on both URLs, so the return links are reciprocal), but canonical +
 * og:url are made SELF-REFERENTIAL per language: the Indonesian view canonicalises to `/?lng=id`
 * (its own hreflang alternate) rather than the English `/`, so Google indexes it as a distinct
 * language version instead of folding it into the default as a duplicate.
 */
const SEO_ORIGIN = 'https://app.native-app.my.id'

function useLandingSeo() {
  const { t, i18n } = useTranslation()
  useEffect(() => {
    const lang = i18n.language === 'id' ? 'id' : 'en'
    const title = t('landing.seoTitle')
    const description = t('landing.seoDescription')
    const pageUrl = lang === 'id' ? `${SEO_ORIGIN}/?lng=id` : `${SEO_ORIGIN}/`
    document.documentElement.lang = lang
    document.title = title
    const setMeta = (selector: string, content: string) => {
      document.head.querySelector<HTMLMetaElement>(selector)?.setAttribute('content', content)
    }
    setMeta('meta[name="description"]', description)
    setMeta('meta[property="og:title"]', title)
    setMeta('meta[property="og:description"]', description)
    setMeta('meta[name="twitter:title"]', title)
    setMeta('meta[name="twitter:description"]', description)
    setMeta('meta[property="og:locale"]', lang === 'id' ? 'id_ID' : 'en_US')
    setMeta('meta[property="og:locale:alternate"]', lang === 'id' ? 'en_US' : 'id_ID')
    setMeta('meta[property="og:url"]', pageUrl)
    document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.setAttribute('href', pageUrl)
  }, [t, i18n.language])
}

// ── Page ─────────────────────────────────────────────────────────────────────────────────────────

export function Landing() {
  const auth = useAuth()
  const onSignIn = () => auth.login()
  useLandingSeo()

  useEffect(() => {
    if (!window.location.hash) window.scrollTo(0, 0)
  }, [])

  return (
    <div className="relative min-h-screen overflow-x-clip text-ink">
      <Backdrop />
      <Header onSignIn={onSignIn} />
      <main>
        <Hero />
        <ProofBar />
        <ProductSection />
        <ComplianceSection />
        <PricingSection />
        <SocialProofSection />
        <CtaBand />
      </main>
      <Footer />
    </div>
  )
}
