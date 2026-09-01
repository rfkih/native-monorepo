import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Segmented } from '@/components/ui/Segmented'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { AttendanceTab } from '@/features/hr/AttendanceTab'
import { EmployeesTab } from '@/features/hr/EmployeesTab'
import { PayrollTab } from '@/features/hr/PayrollTab'
import { useOrgUnits } from '@/features/org/api'
import { localeOf } from '@/i18n'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canOps, canPayroll } from '@/lib/rolePreset'
import { useSession } from '@/lib/session'
import {
  businessUnitsOf,
  defaultBusinessUnitId,
  visiblePeopleTabs,
  type PeopleTabKey,
} from './peopleAccess'

const SELECT_CLASSES =
  'h-11 w-full max-w-sm rounded-xl border border-line bg-surface px-3.5 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

/**
 * People — the standalone HR landing page (ADR 0052, preset role-based access model Phase 2's
 * last gap): the `/people` route target for owner/manager/hr, replacing the old `/people` →
 * `/org/:unitId` redirect that dead-ended an hr-alone login on `OrgUnitDetail` (an OPS management
 * screen — its `useTeam`/`useUnitUsers` calls hit org-service's OPS-only `/api/v1/users/**`, 403ing
 * for `hr`). This page reuses the SAME Employees/Attendance/Payroll tab components `OrgUnitDetail`
 * uses, over the SAME `useOrgUnits` read (now hr/accountant-readable at the gateway too) — just
 * without ever mounting an OPS-only call, and with its own business-unit picker instead of a URL
 * unit id.
 *
 * Tab visibility is capability-gated defensively (`visiblePeopleTabs`, mirrors `rolePreset.ts`
 * exactly): Employees + Attendance for any HR-capable login, Payroll additionally only for a
 * PAYROLL-capable one (owner/hr — a manager sees People WITHOUT Payroll, matching the gateway).
 * `EmployeesTab` additionally receives `canManageLogins={canOps(roles)}` so an hr-alone login sees
 * employee records without the console-login-creation affordance (org-service, OPS-only) rather
 * than a hidden 403.
 */
export function PeoplePage() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const auth = useAuth()
  const locale = localeOf(i18n.language)
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const opsOk = canOps(roles)
  const payrollOk = canPayroll(roles)
  const tabKeys = visiblePeopleTabs(roles)

  const [searchParams] = useSearchParams()
  const urlTab = searchParams.get('tab')
  const [tab, setTab] = useState<PeopleTabKey>(() =>
    (tabKeys as readonly string[]).includes(urlTab ?? '')
      ? (urlTab as PeopleTabKey)
      : (tabKeys[0] ?? 'employees'),
  )
  // Resync from the URL on a genuine change only (e.g. hopping from the Employees to the Payroll
  // nav item while already on this page) — same one-directional URL→state pattern OrgUnitDetail
  // uses for its own `?tab=` (react.dev "you might not need an effect"). Manually switching tabs
  // via the Segmented control below never writes back to the URL, so this never fights that.
  const [lastUrlTab, setLastUrlTab] = useState(urlTab)
  if (urlTab !== lastUrlTab) {
    setLastUrlTab(urlTab)
    if (urlTab && (tabKeys as readonly string[]).includes(urlTab)) {
      setTab(urlTab as PeopleTabKey)
    }
  }

  const unitsQuery = useOrgUnits({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })
  const units = unitsQuery.data ?? []
  const businessUnits = businessUnitsOf(units)
  const [selectedUnitId, setSelectedUnitId] = useState<string | null>(null)
  const unitId = selectedUnitId ?? defaultBusinessUnitId(businessUnits, company?.businessId ?? null)
  const unit = businessUnits.find((u) => u.id === unitId) ?? null

  if (!company) {
    return <EmptyState title={t('people.noCompany')} hint={t('people.noCompanyHint')} />
  }

  if (unitsQuery.isLoading) {
    return (
      <div className="flex flex-col gap-[18px]">
        <StatCardsSkeleton cards={3} />
        <ListSkeleton rows={5} avatar />
      </div>
    )
  }

  if (unitsQuery.isError) {
    return (
      <Card className="p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('people.error')}
      </Card>
    )
  }

  if (!unit) {
    return <EmptyState title={t('people.noBusinessUnit.title')} hint={t('people.noBusinessUnit.hint')} />
  }

  const tabOptions = [
    { value: 'employees' as const, label: t('orgHub.tabs.employees') },
    { value: 'attendance' as const, label: t('orgHub.tabs.attendance') },
    ...(payrollOk ? [{ value: 'payroll' as const, label: t('orgHub.tabs.payroll') }] : []),
  ]

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('people.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('people.subtitle')}</p>
      </div>

      {businessUnits.length > 1 ? (
        <div className="max-w-sm space-y-1.5">
          <label htmlFor="people-unit" className="block text-sm font-medium text-ink">
            {t('people.unitPicker.label')}
          </label>
          <select
            id="people-unit"
            value={unit.id}
            onChange={(e) => setSelectedUnitId(e.target.value)}
            className={SELECT_CLASSES}
          >
            {businessUnits.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <Segmented<PeopleTabKey>
        options={tabOptions}
        value={tab}
        onChange={setTab}
        ariaLabel={t('people.tabsLabel')}
      />

      {tab === 'employees' ? (
        <EmployeesTab
          unit={unit}
          units={units}
          companyId={company.companyId}
          actor={company.actor}
          baseCurrency={company.baseCurrency}
          canManageLogins={opsOk}
        />
      ) : null}

      {tab === 'attendance' ? (
        <AttendanceTab companyId={company.companyId} actor={company.actor} />
      ) : null}

      {tab === 'payroll' && payrollOk ? (
        <PayrollTab
          units={units}
          companyId={company.companyId}
          actor={company.actor}
          baseCurrency={company.baseCurrency}
          locale={locale}
          onNavigateToAttendance={() => setTab('attendance')}
        />
      ) : null}
    </div>
  )
}
