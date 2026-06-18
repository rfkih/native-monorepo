import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const { company } = useSession()
  const { pathname } = useLocation()

  const nav = [
    { to: '/', label: t('nav.dashboard') },
    { to: '/onboarding', label: t('nav.onboarding') },
  ]

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-line bg-paper/80 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-5">
          <div className="flex items-center gap-8">
            <Link to="/" aria-label="Native">
              <Wordmark />
            </Link>
            <nav className="hidden items-center gap-1 md:flex">
              {nav.map((item) => {
                const active =
                  item.to === '/' ? pathname === '/' : pathname.startsWith(item.to)
                return (
                  <Link
                    key={item.to}
                    to={item.to}
                    className={cn(
                      'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                      active
                        ? 'bg-emerald-tint/70 text-emerald-2'
                        : 'text-ink-3 hover:text-ink',
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
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-5 py-10 md:py-14">{children}</main>
    </div>
  )
}
