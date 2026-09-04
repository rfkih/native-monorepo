import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  ArrowUpRight,
  Briefcase,
  Building2,
  ChevronRight,
  Plus,
  Store,
  TriangleAlert,
  Users,
  Wallet,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState, KpiTile, PeriodNav } from '@/features/_shared/financeUi'
import { useTeam, type TeamMember } from '@/features/team/api'
import { EditPagesDialog } from '@/features/team/EditPagesDialog'
import { useEmployees } from '@/features/hr/api'
import { AttendanceTab } from '@/features/hr/AttendanceTab'
import { EmployeesTab } from '@/features/hr/EmployeesTab'
import { PayrollTab } from '@/features/hr/PayrollTab'
import { OrgUnitExpensesTab } from '@/features/expenses/OrgUnitExpensesTab'
import { TerminalCard } from '@/features/terminal/TerminalCard'
import { effectiveRoles, hasAnyRole, useAuth } from '@/lib/authContext'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney, formatPercent } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { localeOf } from '@/i18n'
import {
  useOrgUnits,
  useUnitPnl,
  useUnitUsers,
  type OrgUnit,
  type UnitPnlOutletRow,
  type UnitPnlResponse,
} from './api'
import {
  AddUnitDialog,
  DeactivateDialog,
  OrgUnitTypeBadge,
  ReactivateDialog,
  RenameDialog,
  VerticalBadge,
} from './parts'

/**
 * Outlet hub — the Odoo-style record detail page at /org/:unitId for an
 * OUTLET: breadcrumb trail, sheet header (name + type + status + actions), a smart-button row, and
 * notebook tabs (Overview P&L / Outlets / Employees / App access / Expenses / Attendance / Payroll).
 * All copy via i18n (rule 9); money via formatMoney minor units + Intl.
 */

type TabKey =
  | 'overview'
  | 'outlets'
  | 'employees'
  | 'people'
  | 'expenses'
  | 'attendance'
  | 'payroll'
  | 'terminal'

const TAB_KEYS: readonly TabKey[] = [
  'overview',
  'outlets',
  'employees',
  'people',
  'expenses',
  'attendance',
  'payroll',
  'terminal',
]

type DialogState =
  | { kind: 'addOutlet' }
  | { kind: 'rename'; unit: OrgUnit }
  | { kind: 'deactivate'; unit: OrgUnit }
  | { kind: 'reactivate'; unit: OrgUnit }

export function OrgUnitDetail() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const auth = useAuth()
  const { unitId } = useParams<{ unitId: string }>()
  const locale = localeOf(i18n.language)
  // Terminal tab (ADR 0049 Phase 2, device credential + require-PIN policy) is owner/manager only
  // — the /org route is already canDashboard-gated, but this mirrors that check explicitly (the
  // same defense-in-depth PayrollTab uses for its owner-only bank-file download) since the tab
  // exposes a reveal-on-demand secret.
  const isOwnerOrManager = hasAnyRole(effectiveRoles(auth.roles, auth.elevatedRoles), 'owner', 'manager')

  // Preset role-based access model Phase 2 — the People nav group (App.tsx's /people redirector)
  // deep-links here as `/org/:unitId?tab=employees|payroll|attendance`; read it once on mount AND
  // resync it whenever it changes (the redirector navigates here WITHOUT unmounting this
  // component when only the `tab` query changes, e.g. hopping from the Employees to the Payroll
  // nav item while already on this unit). Adjusted DURING RENDER, not in an effect (react.dev
  // "you might not need an effect" — the same pattern SessionProvider/Shell's Sidebar already use
  // elsewhere in this codebase): `lastUrlTab` tracks the last SEEN `?tab=` value so the sync only
  // fires on a genuine URL change, never on every render. Manually switching tabs via the
  // Segmented control below does NOT write back to the URL (one-directional: URL → state only),
  // so this never fights that.
  const [searchParams] = useSearchParams()
  const urlTab = searchParams.get('tab')
  const [tab, setTab] = useState<TabKey>(() =>
    (TAB_KEYS as readonly string[]).includes(urlTab ?? '') ? (urlTab as TabKey) : 'overview',
  )
  const [lastUrlTab, setLastUrlTab] = useState(urlTab)
  if (urlTab !== lastUrlTab) {
    setLastUrlTab(urlTab)
    if (urlTab && (TAB_KEYS as readonly string[]).includes(urlTab)) {
      setTab(urlTab as TabKey)
    }
  }
  const [period, setPeriod] = useState(currentPeriod())
  const [dialog, setDialog] = useState<DialogState | null>(null)

  const unitsQuery = useOrgUnits({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })
  const units = unitsQuery.data ?? []
  const unit = unitId ? units.find((u) => u.id === unitId) : undefined
  // ADR 0070: the tree is flat — every unit is a top-level OUTLET, so there is no non-detail
  // type to guard against, no division to be, no child outlets and no parent.
  const isDetailType = unit?.type === 'OUTLET'
  const isBu = false
  const childOutlets: OrgUnit[] = []
  const parent = undefined as OrgUnit | undefined

  const pnlQuery = useUnitPnl({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    unitId: unitId ?? '',
    period,
    baseCurrency: company?.baseCurrency ?? 'IDR',
    enabled: !!company && !!unitId && isDetailType,
  })
  const usersQuery = useUnitUsers({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    unitId: unitId ?? '',
    enabled: !!company && !!unitId && isDetailType,
  })
  const teamQuery = useTeam({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company && isDetailType,
  })
  // HR headcount for the smart tile — scope is the outlet itself (ADR 0070: nothing nests).
  const hrUnitIds = isBu ? [unitId ?? '', ...childOutlets.map((o) => o.id)] : [unitId ?? '']
  const hrQuery = useEmployees({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    orgUnitIds: hrUnitIds,
    enabled: !!company && !!unitId && isDetailType,
  })

  if (!company) {
    return <EmptyState title={t('org.noCompany')} hint={t('org.noCompanyHint')} />
  }
  if (unitsQuery.isLoading) {
    return (
      <div className="flex flex-col gap-[18px]">
        <StatCardsSkeleton cards={4} />
        <StatCardsSkeleton cards={3} />
        <ListSkeleton rows={4} />
      </div>
    )
  }
  if (!unit || !isDetailType) {
    return (
      <div className="mx-auto max-w-md space-y-4 text-center">
        <EmptyState title={t('orgHub.notFound.title')} hint={t('orgHub.notFound.hint')} />
        <Link to="/org" className="text-sm font-semibold text-brand-700 hover:underline">
          {t('orgHub.notFound.back')}
        </Link>
      </div>
    )
  }

  // 204 (finance has not hydrated this unit yet) → zeros in the base currency; never an error.
  const pnl = pnlQuery.data ?? null
  const currency = pnl?.currency ?? company.baseCurrency
  const netTone = (pnl?.netMinor ?? 0) < 0 ? 'text-loss' : 'text-profit'
  const distinctUsers = new Set((usersQuery.data ?? []).map((u) => u.userId))
  const distinctEmployees = new Set((hrQuery.data ?? []).map((e) => e.employeeId))

  const tabs: { value: TabKey; label: string }[] = [
    { value: 'overview', label: t('orgHub.tabs.overview') },
    ...(isBu ? [{ value: 'outlets' as TabKey, label: t('orgHub.tabs.outlets') }] : []),
    { value: 'employees', label: t('orgHub.tabs.employees') },
    { value: 'people', label: t('orgHub.tabs.people') },
    { value: 'expenses', label: t('orgHub.tabs.expenses') },
    { value: 'attendance', label: t('orgHub.tabs.attendance') },
    { value: 'payroll', label: t('orgHub.tabs.payroll') },
    ...(!isBu && isOwnerOrManager
      ? [{ value: 'terminal' as TabKey, label: t('orgHub.tabs.terminal') }]
      : []),
  ]

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Breadcrumb trail */}
      <nav aria-label={t('orgHub.breadcrumbLabel')} className="flex items-center gap-1.5 text-sm">
        <Link to="/org" className="font-medium text-ink-3 transition-colors hover:text-brand-700">
          {t('org.title')}
        </Link>
        <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
        <span className="font-semibold text-ink">{unit.name}</span>
      </nav>

      {/* Sheet header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
              {unit.name}
            </h1>
            <OrgUnitTypeBadge type={unit.type} />
            <VerticalBadge vertical={unit.vertical} />
            <span className="flex items-center gap-1.5">
              <span
                className={cn(
                  'size-1.5 rounded-full',
                  unit.active ? 'bg-profit' : 'bg-ink-300',
                )}
                aria-hidden="true"
              />
              <span className="text-[12px] text-ink-3">
                {unit.active ? t('org.active') : t('org.inactive')}
              </span>
            </span>
          </div>
          <p className="mt-1.5 text-sm text-ink-3">{t('orgHub.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() => setDialog({ kind: 'rename', unit })}
          >
            {t('org.rename')}
          </Button>
          {unit.active ? (
            <Button
              type="button"
              variant="outline"
              className="text-loss hover:bg-tint-loss"
              onClick={() => setDialog({ kind: 'deactivate', unit })}
            >
              {t('org.deactivate')}
            </Button>
          ) : (
            <Button type="button" onClick={() => setDialog({ kind: 'reactivate', unit })}>
              {t('org.reactivate')}
            </Button>
          )}
        </div>
      </div>

      {/* Smart buttons */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {isBu ? (
          <SmartButton
            icon={<Store className="size-5" aria-hidden="true" />}
            label={t('orgHub.smart.outlets')}
            figure={String(childOutlets.filter((o) => o.active).length)}
            onClick={() => setTab('outlets')}
          />
        ) : (
          <SmartLink
            icon={<Building2 className="size-5" aria-hidden="true" />}
            label={t('orgHub.smart.parentUnit')}
            figure={parent?.name ?? '—'}
            to={parent ? `/org/${parent.id}` : '/org'}
          />
        )}
        <SmartButton
          icon={<Briefcase className="size-5" aria-hidden="true" />}
          label={t('orgHub.smart.employees')}
          figure={hrQuery.isLoading ? '…' : String(distinctEmployees.size)}
          onClick={() => setTab('employees')}
        />
        <SmartButton
          icon={<Users className="size-5" aria-hidden="true" />}
          label={t('orgHub.smart.people')}
          figure={usersQuery.isLoading ? '…' : String(distinctUsers.size)}
          onClick={() => setTab('people')}
        />
        <SmartButton
          icon={<Wallet className="size-5" aria-hidden="true" />}
          label={t('orgHub.smart.net')}
          figure={
            pnlQuery.isLoading
              ? '…'
              : pnlQuery.isError
                ? '—'
                : formatMoney(pnl?.netMinor ?? 0, currency, locale)
          }
          tone={pnlQuery.isError ? undefined : netTone}
          onClick={() => setTab('overview')}
        />
      </div>

      {/* Notebook tabs */}
      <Segmented<TabKey>
        options={tabs}
        value={tab}
        onChange={setTab}
        ariaLabel={t('orgHub.tabsLabel')}
      />

      {tab === 'overview' ? (
        <OverviewTab
          isBu={isBu}
          pnl={pnl}
          loading={pnlQuery.isLoading}
          error={pnlQuery.isError}
          currency={currency}
          locale={locale}
          period={period}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          netTone={netTone}
        />
      ) : null}

      {tab === 'outlets' && isBu ? (
        <OutletsTab
          childOutlets={childOutlets}
          pnlRows={pnl?.outlets ?? []}
          currency={currency}
          locale={locale}
          onAdd={() => setDialog({ kind: 'addOutlet' })}
          onRename={(u) => setDialog({ kind: 'rename', unit: u })}
          onDeactivate={(u) => setDialog({ kind: 'deactivate', unit: u })}
          onReactivate={(u) => setDialog({ kind: 'reactivate', unit: u })}
        />
      ) : null}

      {tab === 'employees' ? (
        <EmployeesTab
          unit={unit}
          units={units}
          companyId={company.companyId}
          actor={company.actor}
          baseCurrency={company.baseCurrency}
          // This hub is owner/manager-only (App.tsx's `orgUnitAllowed`), so `isOwnerOrManager` is
          // always true here — threaded explicitly rather than relying on the prop's own default
          // so nothing about this screen depends on EmployeesTab's default ever staying `true`.
          canManageLogins={isOwnerOrManager}
        />
      ) : null}

      {tab === 'people' ? (
        <PeopleTab usersQuery={usersQuery} teamQuery={teamQuery} />
      ) : null}

      {tab === 'expenses' ? (
        <OrgUnitExpensesTab
          unit={unit}
          companyId={company.companyId}
          actor={company.actor}
          baseCurrency={company.baseCurrency}
        />
      ) : null}
      {tab === 'attendance' ? (
        <AttendanceTab companyId={company.companyId} actor={company.actor} />
      ) : null}
      {tab === 'payroll' ? (
        <PayrollTab
          units={units}
          companyId={company.companyId}
          actor={company.actor}
          baseCurrency={company.baseCurrency}
          locale={locale}
          onNavigateToAttendance={() => setTab('attendance')}
        />
      ) : null}
      {tab === 'terminal' && !isBu && isOwnerOrManager ? (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-ink-3">{t('terminal.subtitle')}</p>
          <TerminalCard outletId={unit.id} companyId={company.companyId} actor={company.actor} />
        </div>
      ) : null}

      {/* Dialogs (lifted org parts; mutations invalidate ['orgUnits'] so the page re-renders) */}
      {dialog?.kind === 'addOutlet' ? (
        <AddUnitDialog
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'rename' ? (
        <RenameDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'deactivate' ? (
        <DeactivateDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'reactivate' ? (
        <ReactivateDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Smart buttons (Odoo stat buttons: Card + icon + figure, linking to a section)
// ---------------------------------------------------------------------------

function SmartButton({
  icon,
  label,
  figure,
  tone,
  onClick,
}: {
  icon: React.ReactNode
  label: string
  figure: string
  tone?: string
  onClick: () => void
}) {
  return (
    <button type="button" onClick={onClick} className="text-left">
      <Card className="flex items-center gap-3.5 p-4 transition-colors hover:bg-hover">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          {icon}
        </span>
        <span className="min-w-0">
          <span className="block text-[11px] font-semibold uppercase tracking-wider text-ink-3">
            {label}
          </span>
          <span className={cn('tnum block truncate font-mono text-lg font-semibold', tone ?? 'text-ink')}>
            {figure}
          </span>
        </span>
      </Card>
    </button>
  )
}

function SmartLink({
  icon,
  label,
  figure,
  to,
}: {
  icon: React.ReactNode
  label: string
  figure: string
  to: string
}) {
  return (
    <Link to={to}>
      <Card className="flex items-center gap-3.5 p-4 transition-colors hover:bg-hover">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] font-semibold uppercase tracking-wider text-ink-3">
            {label}
          </span>
          <span className="block truncate text-lg font-semibold text-ink">{figure}</span>
        </span>
        <ArrowUpRight className="size-4 shrink-0 text-ink-3" aria-hidden="true" />
      </Card>
    </Link>
  )
}

// ---------------------------------------------------------------------------
// Overview tab — per-unit P&L + outlet contribution
// ---------------------------------------------------------------------------

function OverviewTab({
  isBu,
  pnl,
  loading,
  error,
  currency,
  locale,
  period,
  onPrev,
  onNext,
  netTone,
}: {
  isBu: boolean
  pnl: UnitPnlResponse | null
  loading: boolean
  error: boolean
  currency: string
  locale: string
  period: string
  onPrev: () => void
  onNext: () => void
  netTone: string
}) {
  const { t } = useTranslation()

  if (error) {
    return (
      <Card className="p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('orgHub.overview.error')}
      </Card>
    )
  }

  const totalRevenue = pnl?.revenueMinor ?? 0

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={onPrev}
          onNext={onNext}
          prevLabel={t('orgHub.overview.prevMonth')}
          nextLabel={t('orgHub.overview.nextMonth')}
        />
        {pnl?.usesIllustrativeRules ? (
          <Badge tone="amber">{t('orgHub.overview.illustrative')}</Badge>
        ) : null}
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <KpiTile
          label={t('orgHub.overview.revenue')}
          minor={pnl?.revenueMinor ?? 0}
          currency={currency}
          locale={locale}
          loading={loading}
        />
        <KpiTile
          label={t('orgHub.overview.expense')}
          minor={pnl?.expenseMinor ?? 0}
          currency={currency}
          locale={locale}
          loading={loading}
        />
        <KpiTile
          label={t('orgHub.overview.net')}
          minor={pnl?.netMinor ?? 0}
          currency={currency}
          locale={locale}
          loading={loading}
          tone={netTone}
          emphatic
        />
      </div>

      {!loading && (pnl == null || (pnl.revenueMinor === 0 && pnl.expenseMinor === 0)) ? (
        <p className="text-sm text-ink-3">{t('orgHub.overview.noPostings')}</p>
      ) : null}

      {isBu && pnl && pnl.outlets.length > 0 ? (
        <Card className="p-5">
          <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('orgHub.overview.contribution')}
          </h2>
          <div className="mt-4 flex flex-col gap-3.5">
            {pnl.outlets.map((o) => (
              <ContributionRow
                key={o.orgUnitId}
                row={o}
                totalRevenue={totalRevenue}
                currency={currency}
                locale={locale}
              />
            ))}
          </div>
        </Card>
      ) : null}
    </div>
  )
}

/** One outlet's share of the unit's revenue, with a slim progress bar (dashboard pattern). */
function ContributionRow({
  row,
  totalRevenue,
  currency,
  locale,
}: {
  row: UnitPnlOutletRow
  totalRevenue: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const share = totalRevenue > 0 ? row.revenueMinor / totalRevenue : 0
  return (
    <div>
      <div className="flex items-center justify-between gap-3">
        <Link
          to={`/org/${row.orgUnitId}`}
          className="min-w-0 truncate text-sm font-semibold text-ink hover:text-brand-700 hover:underline"
        >
          {row.name ?? row.orgUnitId.slice(0, 8)}
        </Link>
        <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
          {formatMoney(row.revenueMinor, currency, locale)}
          <span className="ml-2 text-[11px] font-normal text-ink-3">
            {t('orgHub.overview.shareOfRevenue', {
              percent: formatPercent(share, locale),
            })}
          </span>
        </span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-ink-50">
        <div
          className="h-full rounded-full bg-emerald"
          style={{ width: `${Math.max(0, Math.min(1, share)) * 100}%` }}
        />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Outlets tab
// ---------------------------------------------------------------------------

function OutletsTab({
  childOutlets,
  pnlRows,
  currency,
  locale,
  onAdd,
  onRename,
  onDeactivate,
  onReactivate,
}: {
  childOutlets: OrgUnit[]
  pnlRows: UnitPnlOutletRow[]
  currency: string
  locale: string
  onAdd: () => void
  onRename: (unit: OrgUnit) => void
  onDeactivate: (unit: OrgUnit) => void
  onReactivate: (unit: OrgUnit) => void
}) {
  const { t } = useTranslation()
  const netByOutlet = new Map(pnlRows.map((r) => [r.orgUnitId, r.netMinor]))

  return (
    <Card className="p-5">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {t('orgHub.tabs.outlets')}
        </h2>
        <Button type="button" onClick={onAdd}>
          <Plus className="size-4" />
          {t('orgHub.outletsTab.add')}
        </Button>
      </div>

      {childOutlets.length === 0 ? (
        <div className="py-8 text-center">
          <p className="font-semibold text-ink">{t('orgHub.outletsTab.empty')}</p>
          <p className="mt-1 text-sm text-ink-3">{t('orgHub.outletsTab.emptyHint')}</p>
        </div>
      ) : (
        <div className="mt-3 flex flex-col">
          {childOutlets.map((outlet) => (
            <div
              key={outlet.id}
              className="group flex items-center gap-3 rounded-xl px-2.5 py-2.5 transition-colors hover:bg-hover"
            >
              <Link
                to={`/org/${outlet.id}`}
                className="min-w-0 flex-1 truncate text-[14.5px] font-semibold text-ink hover:text-brand-700 hover:underline"
              >
                {outlet.name}
              </Link>
              <span className="flex shrink-0 items-center gap-1.5">
                <span
                  className={cn(
                    'size-1.5 rounded-full',
                    outlet.active ? 'bg-profit' : 'bg-ink-300',
                  )}
                  aria-hidden="true"
                />
                <span className="hidden text-[11px] text-ink-3 sm:block">
                  {outlet.active ? t('org.active') : t('org.inactive')}
                </span>
              </span>
              <span className="tnum hidden shrink-0 font-mono text-sm text-ink sm:block">
                {formatMoney(netByOutlet.get(outlet.id) ?? 0, currency, locale)}
              </span>
              <span className="flex items-center gap-1 opacity-0 transition-opacity focus-within:opacity-100 [div:hover>&]:opacity-100">
                <button
                  type="button"
                  className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink"
                  onClick={() => onRename(outlet)}
                >
                  {t('org.rename')}
                </button>
                {outlet.active ? (
                  <button
                    type="button"
                    className="rounded-md px-2 py-1 text-xs text-loss/80 hover:bg-tint-loss hover:text-loss"
                    onClick={() => onDeactivate(outlet)}
                  >
                    {t('org.deactivate')}
                  </button>
                ) : (
                  <button
                    type="button"
                    className="rounded-md px-2 py-1 text-xs text-brand-600/80 hover:bg-emerald-tint hover:text-brand-700"
                    onClick={() => onReactivate(outlet)}
                  >
                    {t('org.reactivate')}
                  </button>
                )}
              </span>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
// People tab — assignments under the unit, identity joined from the team list
// ---------------------------------------------------------------------------

function PeopleTab({
  usersQuery,
  teamQuery,
}: {
  usersQuery: ReturnType<typeof useUnitUsers>
  teamQuery: ReturnType<typeof useTeam>
}) {
  const { t } = useTranslation()
  const { company } = useSession()
  const [editingPages, setEditingPages] = useState<TeamMember | null>(null)

  if (usersQuery.isError) {
    return (
      <Card className="p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('orgHub.people.error')}
      </Card>
    )
  }
  if (usersQuery.isLoading) {
    return <ListSkeleton rows={4} avatar />
  }

  const rows = usersQuery.data ?? []
  if (rows.length === 0) {
    return (
      <Card className="py-10 text-center">
        <p className="font-semibold text-ink">{t('orgHub.people.empty')}</p>
        <p className="mt-1 text-sm text-ink-3">{t('orgHub.people.emptyHint')}</p>
      </Card>
    )
  }

  // Group assignment rows by user; a user may be assigned to several outlets.
  const byUser = new Map<string, { outlets: string[] }>()
  for (const row of rows) {
    const entry = byUser.get(row.userId) ?? { outlets: [] }
    entry.outlets.push(row.outletName)
    byUser.set(row.userId, entry)
  }
  const team = teamQuery.data ?? []

  return (
    <Card className="p-5">
      <div className="flex flex-col">
        {[...byUser.entries()].map(([userId, entry]) => {
          const member = team.find((m) => m.id === userId)
          return (
            <div
              key={userId}
              className="flex flex-wrap items-center gap-3 rounded-xl px-2.5 py-3 transition-colors hover:bg-hover"
            >
              <span className="grid size-9 shrink-0 place-items-center rounded-full bg-emerald-tint text-xs font-bold uppercase text-emerald-2">
                {(member?.username ?? userId).slice(0, 2)}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold text-ink">
                  {member?.username ?? t('orgHub.people.unknownUser')}
                </span>
                <span className="block truncate text-xs text-ink-3">
                  {member?.email ?? userId}
                </span>
              </span>
              {member?.roles?.length ? (
                <Badge tone="info">{member.roles[0]}</Badge>
              ) : null}
              {member ? (
                <button
                  type="button"
                  onClick={() => setEditingPages(member)}
                  className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
                >
                  {t('orgHub.people.editAccess')}
                </button>
              ) : null}
              <span className="flex flex-wrap items-center gap-1.5">
                {entry.outlets.map((name) => (
                  <span
                    key={name}
                    className="rounded-full bg-ink-50 px-2.5 py-0.5 text-[11px] font-semibold text-ink-500"
                  >
                    {name}
                  </span>
                ))}
              </span>
            </div>
          )
        })}
      </div>
      {editingPages ? (
        <EditPagesDialog
          member={editingPages}
          companyId={company?.companyId ?? ''}
          actor={company?.actor ?? ''}
          onClose={() => setEditingPages(null)}
        />
      ) : null}
    </Card>
  )
}
