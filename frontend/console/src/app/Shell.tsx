import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ArrowRight,
  Check,
  ChevronDown,
  LogOut,
  Menu,
  Moon,
  Plus,
  Store,
  Sun,
  X,
} from 'lucide-react'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/cn'
import { useNavGroups, type NavGroup, type NavItem } from './navGroups'

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const { company } = useSession()
  const auth = useAuth()
  const { pathname } = useLocation()
  const { theme, toggle } = useTheme()
  const [drawerOpen, setDrawerOpen] = useState(false)

  // Nav data + role/grant/tier filtering live in navGroups.ts (shared with the phone More sheet).
  const { groups, canPos } = useNavGroups()

  const isActive = (item: NavItem) =>
    item.end ? pathname === item.to : pathname.startsWith(item.to)

  // Breadcrumb falls out of the same data: "<group> · <active item>".
  const flat = groups.flatMap((g) => g.items.map((it) => ({ ...it, group: g.heading })))
  const active = flat.find(isActive)

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[240px_1fr]">
      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-screen flex-col gap-[3px] overflow-y-auto border-r border-line bg-surface px-4 py-5 print:hidden [view-transition-name:shell-sidebar] lg:flex">
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
        <header className="sticky top-0 z-20 flex h-16 items-center gap-4 border-b border-line bg-surface px-5 print:hidden [view-transition-name:shell-topbar] lg:px-8">
          {/* mobile menu + logo */}
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label={t('nav.menu')}
            className="hidden size-9 place-items-center rounded-full border border-line text-ink-2 hover:bg-hover sm:grid lg:hidden"
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

          {company ? <CompanySwitcher /> : null}

          <button
            type="button"
            onClick={toggle}
            aria-label={t('a11y.toggleTheme')}
            title={t('a11y.toggleTheme')}
            className="grid size-10 place-items-center rounded-full border border-line bg-surface text-ink-3 transition-colors hover:bg-hover hover:text-ink"
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

/**
 * Pattern 1a (Native Console Web design): nine collapsible groups, one open at a time. Navigating
 * auto-expands the group that owns the destination; a collapsed group that holds the current page
 * keeps a brand dot so your place is never lost. Icons live on the group head — leaves are
 * label-only rows.
 */
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
  const activeKey = groups.find((g) => g.items.some(isActive))?.key ?? null
  const [openKey, setOpenKey] = useState<string | null>(activeKey)
  // Navigation always re-expands the owning group (manual toggles hold until the next navigate) —
  // adjusted during render, not in an effect (react.dev "you might not need an effect").
  const [prevActiveKey, setPrevActiveKey] = useState(activeKey)
  if (activeKey !== prevActiveKey) {
    setPrevActiveKey(activeKey)
    if (activeKey) setOpenKey(activeKey)
  }

  return (
    <>
      <Link to="/" viewTransition aria-label="Native" onClick={onNavigate} className="px-2 pb-5 pt-1.5">
        <Wordmark />
      </Link>

      {groups.map((group) => {
        const GroupIcon = group.icon
        const open = openKey === group.key
        const holdsActive = group.items.some(isActive)
        return (
          <div key={group.key} className="contents">
            <button
              type="button"
              aria-expanded={open}
              onClick={() => setOpenKey(open ? null : group.key)}
              className={cn(
                'flex h-[38px] w-full items-center gap-2.5 rounded-[11px] px-3 text-left text-[13px] font-semibold text-ink transition-colors focus-visible:outline-2 focus-visible:outline-brand-500',
                open ? 'bg-paper' : 'hover:bg-hover',
              )}
            >
              <GroupIcon
                className={cn(
                  'size-[17px] shrink-0',
                  open || holdsActive ? 'text-emerald' : 'text-ink-400',
                )}
                strokeWidth={1.8}
              />
              <span className="min-w-0 flex-1 truncate">{group.heading}</span>
              {!open && holdsActive ? (
                <span className="size-1.5 shrink-0 rounded-full bg-emerald" aria-hidden="true" />
              ) : null}
              <span className="font-mono text-[10px] font-semibold text-ink-3">
                {group.items.length}
              </span>
              <ChevronDown
                className={cn(
                  'size-[13px] shrink-0 text-ink-3 transition-transform',
                  open && 'rotate-180',
                )}
                aria-hidden="true"
              />
            </button>
            {open
              ? group.items.map((item) => {
                  const activeItem = isActive(item)
                  return (
                    <Link
                      key={item.to}
                      to={item.to}
                      viewTransition
                      onClick={onNavigate}
                      aria-current={activeItem ? 'page' : undefined}
                      className={cn(
                        'flex h-[34px] items-center rounded-[10px] pl-[38px] pr-3 text-[12.5px] transition-colors focus-visible:outline-2 focus-visible:outline-brand-500',
                        activeItem
                          ? 'bg-emerald-tint font-bold text-emerald-2'
                          : 'font-medium text-ink-3 hover:bg-hover hover:text-ink',
                      )}
                    >
                      <span className="truncate">{item.label}</span>
                    </Link>
                  )
                })
              : null}
          </div>
        )
      })}

      {canPos ? (
        <div className="mt-auto pt-4">
          <Link
            to="/pos"
            viewTransition
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

/**
 * The company switcher (multi-company ownership, ADR 0021): the header pill lists the login's
 * companies; picking one switches the active tenant (every companyId-keyed query re-fetches, and
 * each API call sends the token-validated selection). "+ Add business" opens the onboarding wizard
 * for company N+1. A single-company login sees the plain pill (no menu affordance beyond it).
 */
function CompanySwitcher() {
  const { t } = useTranslation()
  const { company, companies, setActiveCompany } = useSession()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  // Close on outside click / Escape.
  useEffect(() => {
    if (!open) return
    function onPointerDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  if (!company) return null

  return (
    <div ref={rootRef} className="relative hidden sm:block">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t('shell.switchCompany')}
        className="flex h-9 items-center gap-2 rounded-full bg-ink-50 px-3.5 text-[13px] font-semibold text-ink transition-colors hover:bg-hover"
      >
        <span className="size-[7px] rounded-full bg-profit" />
        {company.name}
        <ChevronDown className={cn('size-3.5 text-ink-3 transition-transform', open && 'rotate-180')} />
      </button>

      {open ? (
        <div
          role="menu"
          className="absolute right-0 top-11 z-50 min-w-[220px] rounded-2xl border border-line bg-surface p-1.5 shadow-lg"
        >
          <div className="px-3 pb-1 pt-2 text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('shell.yourBusinesses')}
          </div>
          {companies.map((c) => (
            <button
              key={c.companyId}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false)
                if (c.companyId !== company.companyId) setActiveCompany(c.companyId)
              }}
              className="flex w-full items-center justify-between gap-3 rounded-xl px-3 py-2.5 text-left text-[13px] font-semibold text-ink transition-colors hover:bg-hover"
            >
              <span className="truncate">{c.name}</span>
              {c.companyId === company.companyId ? (
                <Check className="size-4 shrink-0 text-profit" />
              ) : null}
            </button>
          ))}
          <div className="my-1 h-px bg-line" />
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              navigate('/onboarding')
            }}
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left text-[13px] font-semibold text-emerald-2 transition-colors hover:bg-hover"
          >
            <Plus className="size-4 shrink-0" />
            {t('shell.addBusiness')}
          </button>
        </div>
      ) : null}
    </div>
  )
}
