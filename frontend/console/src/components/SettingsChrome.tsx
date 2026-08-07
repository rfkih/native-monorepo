/**
 * Chrome for standalone settings pages that render OUTSIDE the dashboard Shell (a cashier never
 * mounts the Shell, yet must reach the printer pairing). Mirrors the topbar FeaturesSettings /
 * PaymentSettings already carry (their in-file headers predate this component) — added because
 * bare PrinterSettings was a navigation dead-end on phones (UX audit). The home link routes to
 * `/`, which the catch-all resolves to the login's actual home (a cashier lands back on the POS).
 */
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LogOut } from 'lucide-react'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { AUTH_MODE } from '@/lib/config'
import { useAuth } from '@/lib/authContext'

export function SettingsChrome({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const auth = useAuth()
  return (
    <div className="min-h-screen bg-paper">
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
        {children}
      </main>
    </div>
  )
}
