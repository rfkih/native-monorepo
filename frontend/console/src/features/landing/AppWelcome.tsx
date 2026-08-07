/**
 * AppWelcome — the logged-out front door INSIDE the native Android shell (replaces the
 * public marketing Landing, which is a sales page and reads wrong inside an app).
 *
 * Design: a continuation of the app's splash — the same deep-cyan brand field and the
 * BrandMark trend line, here drawn in once (the screen's single animation; motion-safe),
 * then exactly two actions: sign in, create an account. The brand-50..900 ramp is not
 * reassigned by the dark theme, so this screen is identical in light and dark mode —
 * deliberate, like the splash it extends.
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

export function AppWelcome() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[100dvh] flex-col bg-gradient-to-b from-[var(--color-brand-900)] to-[var(--color-brand-800)] px-7">
      <div className="flex-[1.2]" />

      {/* The mark, drawn large — the splash's trend line finishing its stroke. */}
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="var(--color-brand-300)"
        strokeWidth={1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="w-[190px] max-w-[52vw]"
      >
        <path d="M4 18 L10 10 L14 14 L20 5" pathLength={100} className="draw-path" />
        <circle
          cx={20}
          cy={5}
          r={1.3}
          fill="var(--color-brand-200)"
          stroke="none"
          className="reveal"
          style={{ animationDelay: '0.9s' }}
        />
      </svg>

      <h1
        className="reveal mt-7 font-display text-[38px] font-extrabold leading-none tracking-[-0.03em] text-white"
        style={{ animationDelay: '0.25s' }}
      >
        {t('app.name')}
      </h1>
      <p
        className="reveal mt-3 max-w-[26ch] text-[15.5px] leading-relaxed text-brand-100/85"
        style={{ animationDelay: '0.4s' }}
      >
        {t('appWelcome.tagline')}
      </p>

      <div className="flex-1" />

      <div className="reveal flex flex-col gap-3 pb-10" style={{ animationDelay: '0.55s' }}>
        <Link
          to="/login"
          className="grid h-14 place-items-center rounded-2xl bg-white text-[15.5px] font-bold text-brand-800 transition-transform active:scale-[0.98]"
        >
          {t('appWelcome.signIn')}
        </Link>
        <Link
          to="/signup"
          className="grid h-14 place-items-center rounded-2xl border border-white/20 text-[15px] font-semibold text-white/90 transition-colors hover:bg-white/5"
        >
          {t('appWelcome.createAccount')}
        </Link>
      </div>
    </div>
  )
}
