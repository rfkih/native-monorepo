/**
 * MorePage — the office persona's "/more" SCREEN (Native Console Android design; ADR 0078):
 * a tile grid of the everyday phone actions, a search field, then the FULL Shell nav
 * (useNavGroups — the same role ∧ grant ∧ tier-filtered tree, so nothing reachable on desktop is
 * unreachable on phone), then the company switcher, theme toggle, and sign-out.
 *
 * It was a bottom sheet. Below 640px the sidebar and its hamburger are both hidden, so this is the
 * ONLY navigation an office login has — and a modal self-dismisses on every use, cannot be backed
 * into, and loses its scroll position between visits. Those are the correct behaviours of a modal
 * applied to something that is not one. As a route it gets a history entry (N1 gives Back a pop
 * home) and its scroll offset back (N4), for free.
 *
 * Phone-only: at 640px and up the sidebar IS the navigation, so this bounces to `home` — the same
 * shape /me/payslips and /me/timeoff already use.
 *
 * Tile visibility mirrors the target ROUTE's gate (App.tsx) AND the Shell nav's tier tag where
 * one exists — a tile never points at a route that would bounce.
 */
import { Suspense, lazy, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import {
  Banknote,
  CalendarCheck,
  Check,
  ClipboardCheck,
  Clock,
  CookingPot,
  FileText,
  History,
  Inbox,
  Languages,
  LogOut,
  Moon,
  NotebookText,
  Package,
  Percent,
  Plus,
  Receipt,
  Search,
  Store,
  Sun,
  X,
} from 'lucide-react'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { canFinance, canHr, canOps, canPos } from '@/lib/rolePreset'
import { useCurrentOutletRegisterLabelKey } from '@/features/pos/registerApi'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { useOfferedLangs } from '@/lib/geo'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/cn'
import { useNavGroups, type Icon } from './navGroups'
import { OWN_GROUPS, arrangeNavGroups, normalizeQuery } from './moreNavPolicy'

/** Lazy — keeps the stocktake/register + POS API code out of the main chunk until a tile is used. */
const StandaloneStocktake = lazy(() =>
  import('@/features/stocktake/StandaloneStocktake').then((m) => ({ default: m.StandaloneStocktake })),
)
const StandaloneRegister = lazy(() =>
  import('@/features/pos/StandaloneRegister').then((m) => ({ default: m.StandaloneRegister })),
)

const TILE_CLASS =
  'flex min-h-[92px] flex-col items-center justify-center gap-2 rounded-2xl border border-line bg-surface px-1.5 py-3 text-center text-xs font-semibold text-ink-2 transition-[background-color,border-color,color,transform,scale] duration-150 hover:border-line-strong hover:bg-hover hover:text-ink active:scale-[0.97] motion-reduce:active:scale-100'

function Tile({ to, icon: TileIcon, label }: { to: string; icon: Icon; label: string }) {
  return (
    <Link to={to} viewTransition className={TILE_CLASS}>
      <TileIcon className="size-[22px]" strokeWidth={1.8} aria-hidden />
      {label}
    </Link>
  )
}

const ROW_CLASS =
  'flex h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-sm font-medium text-ink transition-colors hover:bg-hover active:bg-line'

function MicroHeading({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-3 pb-1.5 pt-4 font-mono text-2xs font-semibold uppercase tracking-eyebrow text-ink-3">
      {children}
    </div>
  )
}

export function MorePage({ home }: { home: string }) {
  const { t } = useTranslation()
  const isPhone = useIsPhone()
  const auth = useAuth()
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  const { groups } = useNavGroups()
  // The 'catalog' group (menu + inventory) exists for the DESKTOP sidebar; on a phone those pages are
  // the quick-access tiles below, so drop it from the "all pages" list here to avoid listing twice.
  const pageGroups = groups.filter((g) => g.key !== 'catalog')
  const { company, companies, setActiveCompany } = useSession()
  const { theme, toggle } = useTheme()
  // Outside Indonesia there is a single UI language, so the switcher renders nothing (ADR 0059) —
  // drop this labeled row entirely rather than leave a "Language" label with no control beside it.
  const hasLanguageChoice = useOfferedLangs().length >= 2
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [stocktakeOpen, setStocktakeOpen] = useState(false)
  const [registerOpen, setRegisterOpen] = useState(false)

  // ADR 0049 P3b — mirrors MobileTabBarGate's per-capability booleans (merged/elevated roles), so
  // this page's tiles + "Add business" row don't disappear on an elevated device just because
  // MorePage is only ever reachable once the tab bar already decided some office
  // capability was true. Byte-identical for a normal `user` login (elevatedRoles is always `[]`).
  // Preset role-based access model Phase 2 — `close` is FINANCE (owner/accountant), `expenses`
  // (claims) is HR (owner/manager/hr); neither is the wider OPS bundle any more.
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const opsOk = canOps(roles)
  const financeOk = canFinance(roles)
  const hrOk = canHr(roles)
  const posOk = canPos(auth.roles)
  // The stock-opname and register-close overlays are launched from here now rather than handed in
  // by the tab-bar gate (ADR 0078): they belong to the page that offers them, and closing one
  // returns you to /more, which is a real place to return to.
  const posAllowed = posOk && pageAccess.isAllowed('pos') && tierAccess.allows('pos')
  // The register tile toggles Buka/Closing kasir like the POS till menu (owner request) — resolved
  // for the current outlet only when the tile actually renders.
  const registerLabelKey = useCurrentOutletRegisterLabelKey(posAllowed)

  // The tile grid used to be ONE ops-shaped set for everybody. That is the most prominent
  // treatment on the only navigation a phone login has, and for an accountant it was spent on
  // things they cannot open: four of the six tiles need POS and the claim inbox needs HR, so a
  // finance-only login was left with a single tile. The set now follows the persona — an
  // ops-capable login keeps exactly the grid it had, and a books-only login gets the books.
  const accountingOk = financeOk && tierAccess.allows('accounting')
  const closeTile =
    financeOk && pageAccess.isAllowed('close') && tierAccess.allows('orgStructure')
      ? { key: 'close', to: '/close', icon: CalendarCheck, label: t('mobile.more.closeBook') }
      : null
  const opsTiles = [
    closeTile,
    hrOk && pageAccess.isAllowed('expenses') && tierAccess.allows('expenses')
      ? { key: 'inbox', to: '/expenses', icon: Inbox, label: t('mobile.more.claimInbox') }
      : null,
    posOk && pageAccess.isAllowed('menu') && tierAccess.allows('products')
      ? { key: 'menu', to: '/menu', icon: NotebookText, label: t('mobile.more.menuPrices') }
      : null,
    // Inventory (stock-item) catalog behind the stock opname (ADR 0046) — the menu tile's gate.
    posOk && pageAccess.isAllowed('menu') && tierAccess.allows('products')
      ? { key: 'ingredients', to: '/inventory', icon: Package, label: t('mobile.more.ingredients') }
      : null,
    posOk && pageAccess.isAllowed('kitchen') && tierAccess.allows('kitchen')
      ? { key: 'kitchen', to: '/kitchen', icon: CookingPot, label: t('mobile.more.kitchenDisplay') }
      : null,
    posOk && pageAccess.isAllowed('pos') && tierAccess.allows('pos')
      ? { key: 'pos', to: '/pos', icon: Store, label: t('mobile.more.openPos') }
      : null,
  ]
  // Invoices and Bills are the LISTS; AR/AP ageing are the reports. Keeping them distinct matters —
  // pointing "Invoices" at the ageing report would be a confident wrong answer, worse than a dead
  // tap because nothing signals it.
  const financeTiles = [
    accountingOk ? { key: 'invoices', to: '/invoices', icon: Receipt, label: t('nav.invoices') } : null,
    accountingOk ? { key: 'bills', to: '/bills', icon: FileText, label: t('nav.bills') } : null,
    accountingOk ? { key: 'ar', to: '/ar/aging', icon: Clock, label: t('nav.arAging') } : null,
    accountingOk ? { key: 'ap', to: '/ap/aging', icon: History, label: t('nav.apAging') } : null,
    accountingOk ? { key: 'tax', to: '/tax', icon: Percent, label: t('nav.tax') } : null,
    closeTile,
  ]
  // Books-only logins get the books; everyone else keeps the ops grid they already had.
  const tiles = (financeOk && !opsOk ? financeTiles : opsTiles).filter((x) => x != null)

  // Which groups are this persona's daily work — ops wins where a login is both (an owner is
  // reading the business, not just the books).
  const ownGroupKeys = opsOk ? OWN_GROUPS.ops : financeOk ? OWN_GROUPS.finance : []
  const arranged = arrangeNavGroups(pageGroups, query, ownGroupKeys)
  const filtering = normalizeQuery(query).length > 0

  // Above the phone cutoff the sidebar IS the navigation — a full-screen link list would be a
  // worse duplicate of it (same shape as /me/payslips, /me/timeoff).
  if (!isPhone) return <Navigate to={home} replace />

  return (
    // No page chrome of its own: this renders inside the Shell layout route, which already supplies
    // the ground, the topbar and the content padding (ADR 0078 N2). The slight negative inset pulls
    // the tile grid back to the phone gutter Shell's `px-5` would otherwise double.
    <div className="-mx-1">
      <div className="pb-2">
        <h1 className="px-1 pb-3 font-display text-2xl font-extrabold tracking-display text-ink">
          {t('mobile.more.title')}
        </h1>

        {tiles.length > 0 || posAllowed ? (
          <div className="grid grid-cols-3 gap-2">
            {posAllowed ? (
              <button type="button" onClick={() => setRegisterOpen(true)} className={TILE_CLASS}>
                <Banknote className="size-[22px]" strokeWidth={1.8} aria-hidden />
                {t(registerLabelKey)}
              </button>
            ) : null}
            {tiles.map((tile) => (
              <Tile key={tile.key} to={tile.to} icon={tile.icon} label={tile.label} />
            ))}
            {posAllowed ? (
              <button type="button" onClick={() => setStocktakeOpen(true)} className={TILE_CLASS}>
                <ClipboardCheck className="size-[22px]" strokeWidth={1.8} aria-hidden />
                {t('mobile.more.stocktake')}
              </button>
            ) : null}
          </div>
        ) : null}

        {/* Search over the whole tree. On desktop the sidebar shows all of this at once; here it is
            33 links in a 390pt column, and this sheet is the only navigation a phone login has. */}
        {pageGroups.length > 0 ? (
          <div className="mt-5 flex h-11 items-center gap-2.5 rounded-xl bg-hover px-3.5">
            <Search className="size-[18px] shrink-0 text-ink-3" strokeWidth={1.9} aria-hidden />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('mobile.more.searchPages')}
              aria-label={t('mobile.more.searchPages')}
              className="min-w-0 flex-1 self-stretch border-0 bg-transparent text-base text-ink outline-none placeholder:text-ink-3"
            />
            {filtering ? (
              <button
                type="button"
                onClick={() => setQuery('')}
                aria-label={t('mobile.more.clearSearch')}
                className="-mr-2.5 grid size-11 shrink-0 place-items-center rounded-full text-ink-2 transition-colors hover:bg-line-strong"
              >
                <X className="size-[15px]" strokeWidth={2.6} aria-hidden />
              </button>
            ) : null}
          </div>
        ) : null}

        {filtering && arranged.length === 0 ? (
          <div className="px-2 py-8 text-center">
            <div className="text-base font-semibold text-ink">{t('mobile.more.noMatches')}</div>
            <div className="mt-1 text-sm text-ink-3">{t('mobile.more.noMatchesHint')}</div>
          </div>
        ) : null}

        {arranged.map((group) => (
          <div key={group.key}>
            <MicroHeading>{group.heading}</MicroHeading>
            {group.items.map((item) => {
              const ItemIcon = item.icon
              return (
                <Link key={item.to} to={item.to} viewTransition className={ROW_CLASS}>
                  <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">
                    <ItemIcon className="size-[17px]" strokeWidth={1.8} aria-hidden />
                  </span>
                  {item.label}
                </Link>
              )
            })}
          </div>
        ))}

        {/* Preferences are navigation furniture, not search results — they drop out while filtering
            so hits are the only thing on screen. */}
        {!filtering && company != null ? (
          <>
            <MicroHeading>{t('shell.yourBusinesses')}</MicroHeading>
            {companies.map((c) => (
              <button
                key={c.companyId}
                type="button"
                onClick={() => {
                  if (c.companyId !== company.companyId) setActiveCompany(c.companyId)
                }}
                className={cn(ROW_CLASS, 'justify-between font-semibold')}
              >
                <span className="truncate">{c.name}</span>
                {c.companyId === company.companyId ? <Check className="size-4 shrink-0 text-profit" /> : null}
              </button>
            ))}
            {opsOk ? (
              <button
                type="button"
                onClick={() => navigate('/onboarding')}
                className={cn(ROW_CLASS, 'font-semibold text-emerald-2')}
              >
                <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-emerald-tint text-emerald-2">
                  <Plus className="size-4" aria-hidden />
                </span>
                {t('shell.addBusiness')}
              </button>
            ) : null}
          </>
        ) : null}

        {filtering ? null : (
          <>
        <div className="my-2 h-px bg-line" />
        {/* Preferences — language + theme moved off the phone top bar (they crowded a 360px header). */}
        {hasLanguageChoice && (
          <div className={cn(ROW_CLASS, 'justify-between hover:bg-transparent')}>
            <span className="flex items-center gap-3">
              <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">
                <Languages className="size-[17px]" aria-hidden />
              </span>
              {t('nav.language')}
            </span>
            <LanguageSwitcher />
          </div>
        )}
        <button type="button" onClick={toggle} className={ROW_CLASS}>
          <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">
            {theme === 'dark' ? <Sun className="size-[17px]" aria-hidden /> : <Moon className="size-[17px]" aria-hidden />}
          </span>
          {t('a11y.toggleTheme')}
        </button>
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={() => auth.logout()}
            className="flex h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-sm font-medium text-loss-ink transition-colors hover:bg-tint-loss"
          >
            <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-tint-loss text-loss-ink">
              <LogOut className="size-[17px]" aria-hidden />
            </span>
            {t('nav.logout')}
          </button>
        ) : null}
          </>
        )}
      </div>

      {stocktakeOpen ? (
        <Suspense fallback={null}>
          <StandaloneStocktake onClose={() => setStocktakeOpen(false)} />
        </Suspense>
      ) : null}
      {registerOpen ? (
        <Suspense fallback={null}>
          <StandaloneRegister onClose={() => setRegisterOpen(false)} />
        </Suspense>
      ) : null}
    </div>
  )
}
