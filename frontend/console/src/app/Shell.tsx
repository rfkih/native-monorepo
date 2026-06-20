import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LogOut } from 'lucide-react'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const { company } = useSession()
  const auth = useAuth()
  const { pathname } = useLocation()

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const home = canDashboard ? '/' : '/pos'

  // Nav is built from roles: a cashier only ever sees the POS link.
  const nav = [
    canDashboard ? { to: '/', label: t('nav.dashboard') } : null,
    canDashboard ? { to: '/statements/income', label: t('nav.income') } : null,
    canDashboard ? { to: '/statements/balance-sheet', label: t('nav.balanceSheet') } : null,
    canPos ? { to: '/pos', label: t('nav.pos') } : null,
    canDashboard ? { to: '/onboarding', label: t('nav.onboarding') } : null,
  ].filter((x): x is { to: string; label: string } => x !== null)

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-line bg-paper/80 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-5">
          <div className="flex items-center gap-8">
            <Link to={home} aria-label="Native">
              <Wordmark />
            </Link>
            <nav className="hidden items-center gap-1 md:flex">
              {nav.map((item) => {
                const active = item.to === '/' ? pathname === '/' : pathname.startsWith(item.to)
                return (
                  <Link
                    key={item.to}
                    to={item.to}
                    className={cn(
                      'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                      active ? 'bg-emerald-tint/70 text-emerald-2' : 'text-ink-3 hover:text-ink',
                    )}
                  >
                    {item.label}
                  </Link>
                )
              })}
            </nav>
          </div>
          <div className="flex items-center gap-4">
            {company ? (
              <div className="hidden text-right sm:block">
                <div className="text-[11px] uppercase tracking-wide text-ink-3">
                  {t('nav.actingAs')}
                </div>
                <div className="text-sm font-medium text-ink">{company.name}</div>
              </div>
            ) : null}
            <LanguageSwitcher />
            {AUTH_MODE === 'oidc' && auth.authenticated ? (
              <button
                type="button"
                onClick={auth.logout}
                title={auth.actor}
                className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink"
              >
                <LogOut className="size-4" />
                <span className="hidden sm:inline">{t('nav.logout')}</span>
              </button>
            ) : null}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-5 py-10 md:py-14">{children}</main>
    </div>
  )
}
