import { useState, type ComponentType, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeftRight,
  ArrowRight,
  Building2,
  CalendarCheck,
  Clock,
  FileText,
  History,
  Landmark,
  Layers,
  LayoutDashboard,
  LineChart,
  LogOut,
  type LucideProps,
  Menu,
  Moon,
  Network,
  Percent,
  Receipt,
  Scale,
  Store,
  Sun,
  Target,
  Truck,
  Users,
  UsersRound,
  X,
} from 'lucide-react'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { usePageAccess, type PageKey } from '@/lib/pageAccess'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/cn'

type Icon = ComponentType<LucideProps>
type NavItem = { to: string; label: string; icon: Icon; end?: boolean; page?: PageKey }
type NavGroup = { heading: string; items: NavItem[] }

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const { company } = useSession()
  const auth = useAuth()
  const { pathname } = useLocation()
  const { theme, toggle } = useTheme()
  const [drawerOpen, setDrawerOpen] = useState(false)

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const pageAccess = usePageAccess()

  // Grouped nav — the whole sidebar is dashboard-only; a cashier never mounts the Shell. Each item
  // that maps to a grantable page is hidden when the login's grants exclude it (owner bypasses).
  const rawGroups: NavGroup[] = canDashboard
    ? [
        {
          heading: t('nav.groupFinance'),
          items: [
            { to: '/', label: t('nav.dashboard'), icon: LayoutDashboard, end: true, page: 'dashboard' },
            { to: '/statements/income', label: t('nav.income'), icon: LineChart, page: 'reports' },
            { to: '/statements/balance-sheet', label: t('nav.balanceSheet'), icon: Scale, page: 'reports' },
            { to: '/statements/cash-flow', label: t('nav.cashFlow'), icon: ArrowLeftRight, page: 'reports' },
            { to: '/invoices', label: t('nav.invoices'), icon: Receipt },
            { to: '/customers', label: t('nav.customers'), icon: Users },
            { to: '/ar/aging', label: t('nav.arAging'), icon: Clock },
            { to: '/bills', label: t('nav.bills'), icon: FileText },
            { to: '/vendors', label: t('nav.vendors'), icon: Truck },
            { to: '/ap/aging', label: t('nav.apAging'), icon: History },
            { to: '/bank', label: t('nav.bank'), icon: Landmark },
            { to: '/tax', label: t('nav.tax'), icon: Percent },
            { to: '/budgets', label: t('nav.budget'), icon: Target },
          ],
        },
        {
          heading: t('nav.groupStructure'),
          items: [
            { to: '/org', label: t('nav.org'), icon: Network, page: 'org' },
            { to: '/groups', label: t('nav.groups'), icon: Layers, page: 'groups' },
            { to: '/close', label: t('nav.close'), icon: CalendarCheck, page: 'close' },
            { to: '/team', label: t('nav.team'), icon: UsersRound, page: 'team' },
            { to: '/onboarding', label: t('nav.onboarding'), icon: Building2 },
          ],
        },
      ]
    : []

  // Drop items whose page is not granted, then drop any group left empty.
  const groups: NavGroup[] = rawGroups
    .map((g) => ({
      ...g,
      items: g.items.filter((it) => !it.page || pageAccess.isAllowed(it.page)),
    }))
    .filter((g) => g.items.length > 0)

  const isActive = (item: NavItem) =>
    item.end ? pathname === item.to : pathname.startsWith(item.to)

  // Breadcrumb falls out of the same data: "<group> · <active item>".
  const flat = groups.flatMap((g) => g.items.map((it) => ({ ...it, group: g.heading })))
  const active = flat.find(isActive)

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[240px_1fr]">
      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-screen flex-col gap-[3px] overflow-y-auto border-r border-line bg-surface px-4 py-5 print:hidden lg:flex">
        <Sidebar
          groups={groups}
          isActive={isActive}
          canPos={canPos}
          openPosLabel={t('nav.openPos')}
        />
      </aside>

      {/* Mobile drawer */}
      {drawerOpen ? (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true">
          <div
            className="absolute inset-0 bg-black/40"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <aside className="reveal absolute left-0 top-0 flex h-full w-[260px] flex-col gap-[3px] overflow-y-auto border-r border-line bg-surface px-4 py-5">
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label={t('common.cancel')}
              className="absolute right-3 top-4 grid size-9 place-items-center rounded-xl text-ink-3 hover:bg-hover hover:text-ink"
            >
              <X className="size-4.5" />
            </button>
            <Sidebar
              groups={groups}
              isActive={isActive}
              canPos={canPos}
              openPosLabel={t('nav.openPos')}
              onNavigate={() => setDrawerOpen(false)}
            />
          </aside>
        </div>
      ) : null}

      {/* Main column */}
      <div className="flex min-w-0 flex-col">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-4 border-b border-line bg-surface px-5 print:hidden lg:px-8">
          {/* mobile menu + logo */}
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label={t('nav.menu')}
            className="grid size-9 place-items-center rounded-xl border border-line text-ink-2 hover:bg-hover lg:hidden"
          >
            <Menu className="size-4.5" />
          </button>
          <Wordmark className="lg:hidden" />

          {/* desktop breadcrumb */}
          <div className="hidden text-[13px] text-ink-3 lg:block">
            {active ? (
              <>
                {active.group} · <b className="font-semibold text-ink-2">{active.label}</b>
              </>
            ) : (
              <b className="font-semibold text-ink-2">{t('app.name')}</b>
            )}
          </div>

          <div className="flex-1" />

          {company ? (
            <div className="hidden h-9 items-center gap-2 rounded-full bg-ink-50 px-3.5 text-[13px] font-semibold text-ink sm:flex">
              <span className="size-[7px] rounded-full bg-profit" />
              {company.name}
            </div>
          ) : null}

          <button
            type="button"
            onClick={toggle}
            aria-label={t('a11y.toggleTheme')}
            title={t('a11y.toggleTheme')}
            className="grid size-10 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-colors hover:bg-hover hover:text-ink"
          >
            {theme === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
          </button>

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
          ) : (
            <Avatar />
          )}
        </header>

        <main className="mx-auto w-full max-w-[1200px] px-5 py-7 lg:px-8">{children}</main>
      </div>
    </div>
  )
}

function Sidebar({
  groups,
  isActive,
  canPos,
  openPosLabel,
  onNavigate,
}: {
  groups: NavGroup[]
  isActive: (item: NavItem) => boolean
  canPos: boolean
  openPosLabel: string
  onNavigate?: () => void
}) {
  return (
    <>
      <Link to="/" aria-label="Native" onClick={onNavigate} className="px-2 pb-5 pt-1.5">
        <Wordmark />
      </Link>

      {groups.map((group) => (
        <div key={group.heading} className="contents">
          <div className="px-3 pb-1.5 pt-2.5 text-[11px] font-bold uppercase tracking-[0.09em] text-ink-3">
            {group.heading}
          </div>
          {group.items.map((item) => {
            const ItemIcon = item.icon
            const activeItem = isActive(item)
            return (
              <Link
                key={item.to}
                to={item.to}
                onClick={onNavigate}
                aria-current={activeItem ? 'page' : undefined}
                className={cn(
                  'flex h-11 items-center gap-[11px] rounded-xl px-3 text-sm transition-colors',
                  activeItem
                    ? 'bg-emerald-tint font-semibold text-emerald-2'
                    : 'font-medium text-ink-2 hover:bg-hover hover:text-ink',
                )}
              >
                <ItemIcon className="size-[17px] shrink-0" strokeWidth={1.8} />
                {item.label}
              </Link>
            )
          })}
        </div>
      ))}

      {canPos ? (
        <div className="mt-auto pt-4">
          <Link
            to="/pos"
            onClick={onNavigate}
            className="flex h-14 items-center gap-[11px] rounded-[20px] border border-emerald-line bg-emerald-tint px-3.5 text-sm font-bold text-emerald-2 transition-colors hover:bg-brand-100/60"
          >
            <Store className="size-[18px] shrink-0" strokeWidth={1.9} />
            {openPosLabel}
            <ArrowRight className="ml-auto size-[15px] shrink-0" />
          </Link>
        </div>
      ) : null}
    </>
  )
}

function Avatar() {
  const { actor } = useAuth()
  const initials = (actor || 'NA')
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join('')
  return (
    <div className="grid size-10 place-items-center rounded-full bg-emerald-tint text-[13px] font-bold text-emerald-2">
      {initials || 'NA'}
    </div>
  )
}
