/**
 * MoreSheet — the manager persona's "Lainnya" bottom sheet (Native Console Android design):
 * a tile grid of the everyday phone actions, then the FULL Shell nav (useNavGroups — the same
 * role ∧ grant ∧ tier-filtered tree, so nothing reachable on desktop is unreachable on phone),
 * then the company switcher, theme toggle, and sign-out.
 *
 * Tile visibility mirrors the target ROUTE's gate (App.tsx) AND the Shell nav's tier tag where
 * one exists — a tile never points at a route that would bounce.
 */
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import {
  Banknote,
  CalendarCheck,
  Check,
  ClipboardCheck,
  CookingPot,
  Inbox,
  LogOut,
  Moon,
  NotebookText,
  Package,
  Plus,
  Store,
  Sun,
} from 'lucide-react'
import { MobileSheet } from '@/components/mobile/MobileSheet'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { canFinance, canHr, canOps, canPos } from '@/lib/rolePreset'
import { AUTH_MODE } from '@/lib/config'
import { useSession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/cn'
import { useNavGroups, type Icon } from './navGroups'

function Tile({ to, icon: TileIcon, label, onClose }: { to: string; icon: Icon; label: string; onClose: () => void }) {
  return (
    <Link
      to={to}
      viewTransition
      onClick={onClose}
      className="flex min-h-[92px] flex-col items-center justify-center gap-2 rounded-2xl border border-line bg-surface px-1.5 py-3 text-center text-[12px] font-semibold text-ink-2 transition-colors hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
    >
      <TileIcon className="size-[22px] text-emerald-2" strokeWidth={1.8} aria-hidden />
      {label}
    </Link>
  )
}

const ROW_CLASS =
  'flex h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-[14px] font-medium text-ink transition-colors hover:bg-hover'

function MicroHeading({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-3 pb-1.5 pt-4 font-mono text-[11px] font-semibold uppercase tracking-[0.09em] text-ink-3">
      {children}
    </div>
  )
}

export function MoreSheet({
  onClose,
  onOpenStocktake,
  onOpenRegister,
}: {
  onClose: () => void
  /** When provided (POS-capable login), the Opname stok tile renders and opens the overlay. */
  onOpenStocktake?: () => void
  /** When provided (POS-capable login), the register-close (daily close) tile renders. */
  onOpenRegister?: () => void
}) {
  const { t } = useTranslation()
  const auth = useAuth()
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  const { groups } = useNavGroups()
  const { company, companies, setActiveCompany } = useSession()
  const { theme, toggle } = useTheme()
  const navigate = useNavigate()

  // ADR 0049 P3b — mirrors MobileTabBarGate's per-capability booleans (merged/elevated roles), so
  // this sheet's tiles + "Add business" row don't disappear on an elevated device just because
  // MoreSheet itself is only ever reachable once the tab bar already decided some office
  // capability was true. Byte-identical for a normal `user` login (elevatedRoles is always `[]`).
  // Preset role-based access model Phase 2 — `close` is FINANCE (owner/accountant), `expenses`
  // (claims) is HR (owner/manager/hr); neither is the wider OPS bundle any more.
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const opsOk = canOps(roles)
  const financeOk = canFinance(roles)
  const hrOk = canHr(roles)
  const posOk = canPos(auth.roles)

  const tiles = [
    financeOk && pageAccess.isAllowed('close') && tierAccess.allows('orgStructure')
      ? { key: 'close', to: '/close', icon: CalendarCheck, label: t('mobile.more.closeBook') }
      : null,
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
  ].filter((x) => x != null)

  return (
    <MobileSheet onClose={onClose} ariaLabel={t('mobile.more.title')}>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-7">
        <div className="px-1 pb-3 pt-1 text-[17px] font-bold text-ink">{t('mobile.more.title')}</div>

        {tiles.length > 0 || onOpenStocktake != null || onOpenRegister != null ? (
          <div className="grid grid-cols-3 gap-2">
            {onOpenRegister != null ? (
              <button
                type="button"
                onClick={onOpenRegister}
                className="flex min-h-[92px] flex-col items-center justify-center gap-2 rounded-2xl border border-line bg-surface px-1.5 py-3 text-center text-[12px] font-semibold text-ink-2 transition-colors hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
              >
                <Banknote className="size-[22px] text-emerald-2" strokeWidth={1.8} aria-hidden />
                {t('mobile.more.registerClose')}
              </button>
            ) : null}
            {tiles.map((tile) => (
              <Tile key={tile.key} to={tile.to} icon={tile.icon} label={tile.label} onClose={onClose} />
            ))}
            {onOpenStocktake != null ? (
              <button
                type="button"
                onClick={onOpenStocktake}
                className="flex min-h-[92px] flex-col items-center justify-center gap-2 rounded-2xl border border-line bg-surface px-1.5 py-3 text-center text-[12px] font-semibold text-ink-2 transition-colors hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
              >
                <ClipboardCheck className="size-[22px] text-emerald-2" strokeWidth={1.8} aria-hidden />
                {t('mobile.more.stocktake')}
              </button>
            ) : null}
          </div>
        ) : null}

        {groups.length > 0 ? <MicroHeading>{t('mobile.more.allPages')}</MicroHeading> : null}
        {groups.map((group) => (
          <div key={group.key}>
            <MicroHeading>{group.heading}</MicroHeading>
            {group.items.map((item) => {
              const ItemIcon = item.icon
              return (
                <Link key={item.to} to={item.to} viewTransition onClick={onClose} className={ROW_CLASS}>
                  <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">
                    <ItemIcon className="size-[17px]" strokeWidth={1.8} aria-hidden />
                  </span>
                  {item.label}
                </Link>
              )
            })}
          </div>
        ))}

        {company != null ? (
          <>
            <MicroHeading>{t('shell.yourBusinesses')}</MicroHeading>
            {companies.map((c) => (
              <button
                key={c.companyId}
                type="button"
                onClick={() => {
                  onClose()
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
                onClick={() => {
                  onClose()
                  navigate('/onboarding')
                }}
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

        <div className="my-2 h-px bg-line" />
        <button type="button" onClick={toggle} className={ROW_CLASS}>
          <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">
            {theme === 'dark' ? <Sun className="size-[17px]" aria-hidden /> : <Moon className="size-[17px]" aria-hidden />}
          </span>
          {t('a11y.toggleTheme')}
        </button>
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={() => {
              onClose()
              auth.logout()
            }}
            className="flex h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-[14px] font-medium text-loss transition-colors hover:bg-tint-loss"
          >
            <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-tint-loss text-loss">
              <LogOut className="size-[17px]" aria-hidden />
            </span>
            {t('nav.logout')}
          </button>
        ) : null}
      </div>
    </MobileSheet>
  )
}
