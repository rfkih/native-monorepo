/**
 * Public marketing landing page — the product's front door at `/`.
 *
 * Rendered for UNAUTHENTICATED visitors (the OidcAuthProvider no longer force-redirects to Keycloak;
 * login is explicit). "Get started" → /signup; "Sign in" → auth.login() (Keycloak PKCE redirect).
 *
 * World-class, self-contained: real photography (bundled Unsplash assets, see ./photos and
 * src/assets/landing/SOURCES.md) under theme-aware brand scrims, with the in-product "screenshot"
 * mocks floating over it, alternating feature rows, scroll-reveal motion, light/dark theme toggle.
 * Fully localized (react-i18next) and locale-aware `Intl` for money. Product mockups stay inline
 * SVG/DOM (see ./art); photos are hashed local assets — still no external calls at runtime.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowRight, Check, GitBranch, Moon, Quote, Sun, Users, Zap, type LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/cn'
import {
  Backdrop,
  ConsolidationDiagram,
  DashboardMock,
  FxChip,
  PosReceiptMock,
  SaleToast,
  VerticalStrip,
} from './art'
import { ctaPhoto, heroPhoto, Photo, portraitPhoto, posPhoto } from './photos'

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

function Bullet({ children }: { children: ReactNode }) {
  return (
    <li className="flex items-start gap-2.5 text-[14.5px] leading-relaxed text-ink-2">
      <span className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full bg-tint-profit text-profit">
        <Check className="size-3" />
      </span>
      {children}
    </li>
  )
}

function Kicker({ children }: { children: ReactNode }) {
  return <div className="text-xs font-bold uppercase tracking-[0.16em] text-brand-600">{children}</div>
}

// ── Header ───────────────────────────────────────────────────────────────────────────────────────

function Header({ onSignIn, onGetStarted }: { onSignIn: () => void; onGetStarted: () => void }) {
  const { t } = useTranslation()
  return (
    <header className="sticky top-0 z-30 border-b border-line/70 bg-paper/70 backdrop-blur-xl">
      <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-4 px-5">
        <Link to="/" aria-label={t('app.name')} className="shrink-0">
          <Wordmark />
        </Link>
        <nav className="ml-6 hidden items-center gap-7 md:flex">
          <a href="#features" className="text-sm font-semibold text-ink-2 transition-colors hover:text-ink">
            {t('landing.navFeatures')}
          </a>
          <a href="#how" className="text-sm font-semibold text-ink-2 transition-colors hover:text-ink">
            {t('landing.navHow')}
          </a>
        </nav>
        <div className="ml-auto flex items-center gap-2.5">
          <div className="hidden sm:block">
            <LanguageSwitcher />
          </div>
          <ThemeToggle />
          <Button variant="ghost" onClick={onSignIn} className="hidden sm:inline-flex">
            {t('landing.signIn')}
          </Button>
          <Button onClick={onGetStarted}>
            {t('landing.getStarted')} <ArrowRight className="size-4" />
          </Button>
        </div>
      </div>
    </header>
  )
}

// ── Hero ───────────────────────────────────────────────────────────────────────────────────────

function Hero({ onSignIn, onGetStarted }: { onSignIn: () => void; onGetStarted: () => void }) {
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
            className="reveal inline-flex items-center gap-2 rounded-full border border-brand-100 bg-brand-50 px-3.5 py-1.5 text-xs font-bold tracking-wide text-brand-700"
            style={{ animationDelay: '40ms' }}
          >
            <span className="size-1.5 rounded-full bg-brand-500" />
            {t('landing.heroBadge')}
          </span>
          <h1
            className="reveal mt-5 font-display text-[42px] font-extrabold leading-[1.03] tracking-[-0.035em] text-ink sm:text-[58px]"
            style={{ animationDelay: '110ms' }}
          >
            {t('landing.heroTitleLead')}{' '}
            <span className="relative whitespace-nowrap text-brand-600">
              {t('landing.heroTitleAccent')}
              <Underline />
            </span>
          </h1>
          <p
            className="reveal mt-6 max-w-xl text-[17.5px] leading-relaxed text-ink-2"
            style={{ animationDelay: '180ms' }}
          >
            {t('landing.heroSubtitle')}
          </p>
          <div
            className="reveal mt-8 flex flex-col gap-3 sm:flex-row sm:items-center"
            style={{ animationDelay: '250ms' }}
          >
            <Button onClick={onGetStarted} size="xl" className="w-full sm:w-auto">
              {t('landing.heroPrimary')} <ArrowRight className="size-4" />
            </Button>
            <Button
              variant="outline"
              onClick={onSignIn}
              size="xl"
              className="w-full sm:w-auto"
            >
              {t('landing.heroSecondary')}
            </Button>
          </div>
          <p className="reveal mt-4 text-[13px] text-ink-3" style={{ animationDelay: '320ms' }}>
            {t('landing.heroNote')}
          </p>
        </div>

        {/* Right — the product screenshot + floating accent cards */}
        <div className="reveal relative mx-auto lg:justify-self-end" style={{ animationDelay: '280ms' }}>
          <DashboardMock />
          <SaleToast className="floaty absolute -bottom-6 -left-6 hidden w-[248px] md:flex" />
          <div className="floaty absolute -right-5 top-10 hidden md:block [animation-delay:1.4s]">
            <FxChip />
          </div>
        </div>
      </div>
    </section>
  )
}

/** A hand-drawn brand underline flourish beneath the hero accent word. */
function Underline() {
  return (
    <svg
      className="absolute -bottom-1.5 left-0 h-2.5 w-full text-brand-300"
      viewBox="0 0 200 12"
      fill="none"
      preserveAspectRatio="none"
      aria-hidden
    >
      <path
        d="M2 8C40 3 80 3 118 6C150 8.5 180 8 198 4"
        stroke="currentColor"
        strokeWidth="3.5"
        strokeLinecap="round"
      />
    </svg>
  )
}

// ── Verticals strip ──────────────────────────────────────────────────────────────────────────────

function TrustStrip() {
  const { t } = useTranslation()
  return (
    <section className="border-y border-line bg-canvas/60 py-10">
      <div className="mx-auto w-full max-w-6xl px-5">
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

// ── Feature row (alternating text + product mock) ────────────────────────────────────────────────

function FeatureRow({
  kicker,
  title,
  body,
  points,
  media,
  reverse,
}: {
  kicker: string
  title: string
  body: string
  points: string[]
  media: ReactNode
  reverse?: boolean
}) {
  return (
    <Reveal className="mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-12 px-5 py-20 lg:grid-cols-2 lg:gap-16">
      <div className={cn(reverse && 'lg:order-2')}>
        <Kicker>{kicker}</Kicker>
        <h3 className="mt-3 font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink sm:text-[34px]">
          {title}
        </h3>
        <p className="mt-4 max-w-lg text-[16px] leading-relaxed text-ink-2">{body}</p>
        <ul className="mt-6 space-y-3">
          {points.map((p) => (
            <Bullet key={p}>{p}</Bullet>
          ))}
        </ul>
      </div>
      <div className={cn('relative', reverse && 'lg:order-1')}>{media}</div>
    </Reveal>
  )
}

// ── Feature grid (3-up) ──────────────────────────────────────────────────────────────────────────

function FeatureGrid() {
  const { t } = useTranslation()
  const cards: { icon: LucideIcon; title: string; body: string }[] = [
    { icon: Zap, title: t('landing.g1Title'), body: t('landing.g1Body') },
    { icon: Users, title: t('landing.g2Title'), body: t('landing.g2Body') },
    { icon: GitBranch, title: t('landing.g3Title'), body: t('landing.g3Body') },
  ]
  return (
    <section id="features" className="border-t border-line bg-canvas py-20">
      <div className="mx-auto w-full max-w-6xl px-5">
        <Reveal className="mx-auto max-w-2xl text-center">
          <Kicker>{t('landing.gridKicker')}</Kicker>
          <h2 className="mt-3 font-display text-[30px] font-extrabold tracking-[-0.02em] text-ink sm:text-[36px]">
            {t('landing.gridTitle')}
          </h2>
          <p className="mt-3 text-[16px] leading-relaxed text-ink-2">{t('landing.gridSubtitle')}</p>
        </Reveal>
        <div className="mt-12 grid grid-cols-1 gap-5 md:grid-cols-3">
          {cards.map((c, i) => (
            <Reveal key={c.title} delay={i * 90}>
              <div className="h-full rounded-card border border-line bg-surface p-6 shadow-sm transition-shadow hover:shadow-md">
                <span className="grid size-11 place-items-center rounded-xl bg-brand-50 text-brand-600">
                  <c.icon className="size-5" />
                </span>
                <h3 className="mt-4 font-display text-[18px] font-bold tracking-[-0.01em] text-ink">
                  {c.title}
                </h3>
                <p className="mt-1.5 text-sm leading-relaxed text-ink-2">{c.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── How it works ─────────────────────────────────────────────────────────────────────────────────

function Steps() {
  const { t } = useTranslation()
  const steps = [
    { title: t('landing.s1Title'), body: t('landing.s1Body') },
    { title: t('landing.s2Title'), body: t('landing.s2Body') },
    { title: t('landing.s3Title'), body: t('landing.s3Body') },
  ]
  return (
    <section id="how" className="py-20">
      <div className="mx-auto w-full max-w-6xl px-5">
        <Reveal className="mx-auto max-w-2xl text-center">
          <Kicker>{t('landing.stepsKicker')}</Kicker>
          <h2 className="mt-3 font-display text-[30px] font-extrabold tracking-[-0.02em] text-ink sm:text-[36px]">
            {t('landing.stepsTitle')}
          </h2>
        </Reveal>
        <ol className="mt-12 grid grid-cols-1 gap-6 md:grid-cols-3">
          {steps.map((s, i) => (
            <Reveal key={s.title} delay={i * 90}>
              <li className="relative h-full rounded-card border border-line bg-surface p-6 shadow-sm">
                <span className="tnum grid size-10 place-items-center rounded-full bg-gradient-to-br from-brand-600 to-brand-800 text-[16px] font-extrabold text-white shadow-sm">
                  {i + 1}
                </span>
                <h3 className="mt-4 font-display text-[18px] font-bold text-ink">{s.title}</h3>
                <p className="mt-1.5 text-sm leading-relaxed text-ink-2">{s.body}</p>
              </li>
            </Reveal>
          ))}
        </ol>
      </div>
    </section>
  )
}

// ── Testimonial ──────────────────────────────────────────────────────────────────────────────────

function Testimonial() {
  const { t } = useTranslation()
  const author = t('landing.quoteAuthor')
  return (
    <section className="border-t border-line px-5 py-24">
      <Reveal className="mx-auto max-w-3xl text-center">
        <Quote className="mx-auto size-8 text-brand-300" />
        <blockquote className="mt-5 font-display text-[24px] font-bold leading-snug tracking-[-0.01em] text-ink sm:text-[30px]">
          {t('landing.quoteText')}
        </blockquote>
        <div className="mt-7 flex items-center justify-center gap-3">
          <Photo
            photo={portraitPhoto}
            alt={author}
            className="size-11 rounded-full object-cover ring-2 ring-brand-100 dark:ring-brand-300/30"
          />
          <div className="text-left">
            <div className="text-sm font-bold text-ink">{author}</div>
            <div className="text-[12.5px] text-ink-3">{t('landing.quoteRole')}</div>
          </div>
        </div>
      </Reveal>
    </section>
  )
}

// ── Final CTA band ───────────────────────────────────────────────────────────────────────────────

function CtaBand({ onSignIn, onGetStarted }: { onSignIn: () => void; onGetStarted: () => void }) {
  const { t } = useTranslation()
  return (
    <section className="px-5 py-20">
      <Reveal className="relative mx-auto w-full max-w-5xl overflow-hidden rounded-[32px] bg-gradient-to-br from-brand-600 to-brand-800 px-6 py-16 text-center shadow-lg">
        {/* photo texture as a low-opacity luminosity layer — the gradient supplies hue (true brand
            duotone), the carwash supplies light and shadow; white copy keeps AA regardless of image */}
        <Photo
          photo={ctaPhoto}
          alt=""
          sizes="(min-width: 1100px) 1024px, 96vw"
          className="absolute inset-0 h-full w-full object-cover opacity-30 mix-blend-luminosity"
        />
        {/* decorative grid inside the band */}
        <svg className="pointer-events-none absolute inset-0 h-full w-full text-white/10" aria-hidden>
          <defs>
            <pattern id="ctagrid" width="32" height="32" patternUnits="userSpaceOnUse">
              <path d="M32 0H0V32" fill="none" stroke="currentColor" strokeWidth="1" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#ctagrid)" />
        </svg>
        <div className="relative">
          <h2 className="font-display text-[32px] font-extrabold tracking-[-0.02em] text-white sm:text-[40px]">
            {t('landing.ctaTitle')}
          </h2>
          <p className="mx-auto mt-3 max-w-xl text-[16px] leading-relaxed text-white/85">
            {t('landing.ctaSubtitle')}
          </p>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button
              onClick={onGetStarted}
              size="xl"
              className="w-full bg-white text-brand-700 shadow-sm hover:bg-white/90 sm:w-auto"
            >
              {t('landing.ctaButton')} <ArrowRight className="size-4" />
            </Button>
            <button
              onClick={onSignIn}
              className="text-sm font-semibold text-white/90 underline-offset-4 hover:underline"
            >
              {t('landing.ctaSignIn')}
            </button>
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
  return (
    <footer className="border-t border-line bg-canvas">
      <div className="mx-auto flex w-full max-w-6xl flex-col items-center gap-5 px-5 py-10 sm:flex-row">
        <Wordmark />
        <p className="text-sm text-ink-3 sm:ml-4">{t('landing.footerTagline')}</p>
        <div className="flex items-center gap-2.5 sm:ml-auto">
          <a href="#features" className="text-sm font-semibold text-ink-2 hover:text-ink">
            {t('landing.navFeatures')}
          </a>
          <span className="text-ink-200">·</span>
          <a href="#how" className="text-sm font-semibold text-ink-2 hover:text-ink">
            {t('landing.navHow')}
          </a>
          <span className="ml-1">
            <LanguageSwitcher />
          </span>
          <ThemeToggle />
        </div>
      </div>
      <div className="border-t border-line py-4 text-center text-xs text-ink-3">
        {t('landing.footerRights', { year })}
      </div>
    </footer>
  )
}

// ── Page ─────────────────────────────────────────────────────────────────────────────────────────

export function Landing() {
  const { t } = useTranslation()
  const auth = useAuth()
  const navigate = useNavigate()
  const onSignIn = () => auth.login()
  const onGetStarted = () => navigate('/signup')

  useEffect(() => {
    if (!window.location.hash) window.scrollTo(0, 0)
  }, [])

  return (
    <div className="relative min-h-screen overflow-x-clip text-ink">
      <Backdrop />
      <Header onSignIn={onSignIn} onGetStarted={onGetStarted} />
      <main>
        <Hero onSignIn={onSignIn} onGetStarted={onGetStarted} />
        <TrustStrip />
        <FeatureRow
          kicker={t('landing.r1Kicker')}
          title={t('landing.r1Title')}
          body={t('landing.r1Body')}
          points={[t('landing.r1Point1'), t('landing.r1Point2'), t('landing.r1Point3')]}
          media={<ConsolidationDiagram />}
          reverse
        />
        <FeatureRow
          kicker={t('landing.r2Kicker')}
          title={t('landing.r2Title')}
          body={t('landing.r2Body')}
          points={[t('landing.r2Point1'), t('landing.r2Point2'), t('landing.r2Point3')]}
          media={
            // Collage: real counter scene with the product receipt floating over it. The wrapper's
            // bottom padding reserves the receipt's overhang so it never bleeds into the next section.
            <div className="relative mx-auto w-full max-w-[500px] pb-14">
              <div className="relative overflow-hidden rounded-card shadow-lg">
                <Photo
                  photo={posPhoto}
                  alt={t('landing.r2PhotoAlt')}
                  sizes="(min-width: 1024px) 500px, 92vw"
                  className="aspect-[4/3] w-full object-cover"
                />
                <div aria-hidden className="absolute inset-0 bg-brand-900/25 mix-blend-multiply" />
              </div>
              <PosReceiptMock className="absolute bottom-0 right-0 w-[264px] sm:-right-4" />
            </div>
          }
        />
        <FeatureGrid />
        <Steps />
        <Testimonial />
        <CtaBand onSignIn={onSignIn} onGetStarted={onGetStarted} />
      </main>
      <Footer />
    </div>
  )
}
