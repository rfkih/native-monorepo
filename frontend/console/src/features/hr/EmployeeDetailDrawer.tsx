/**
 * EmployeeDetailDrawer — the "Kelola" side panel, Native Console Web design: one always-visible
 * entry point per row instead of six hover-hidden actions, and one panel hosting every
 * per-employee action (assign / end assignment / compensation / login / edit / terminate) while
 * the list stays visible behind it. Reused by both `OrgUnitDetail` (owner/manager) and the
 * standalone `PeoplePage` (owner/manager/hr, ADR 0052) — see `canManageLogins` below.
 *
 * The Login section's centrepiece: the linked login's USERNAME (resolved from the Team list —
 * Keycloak owns it) plus, until the employee first signs in, the one-time PASSWORD held for them
 * (decrypted, ADR 0014). Once the employee activates, the password is gone and the card shows
 * "active". Owner/manager can Reset the password (issues a fresh one-time password, held again)
 * or Remove the login — gated by `canManageLogins`, since both the username lookup and the reset
 * call hit org-service's OPS-only `/api/v1/users/**`. An hr-alone login still sees WHETHER a login
 * exists and the held one-time password (employee-service's `/api/v1/employees/**` is HR-gated,
 * and handing a new hire their credentials is a normal HR task) — just not the username or the
 * manage actions. The one-time password is a credential: shown here for hand-over, never
 * persisted client-side beyond the query cache, never logged.
 *
 * Compensation stays MASKED (salary PII, rule 6) — this panel shows only whether a package is set
 * and the action to change it; amounts never render in the console.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Check,
  Copy,
  KeyRound,
  Lock,
  Pencil,
  Plus,
  ShieldCheck,
  Trash2,
  TriangleAlert,
  UserPlus,
  X,
} from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { Skeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { useTeam, useUpdateMember } from '@/features/team/api'
import { localeOf } from '@/i18n'
import { BUSINESS_ROLES, type BusinessRole } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import { displayLoginId } from '@/lib/loginId'
import { useSession } from '@/lib/session'
import { hasAccessRoleMismatch, impliedAccessRole, uniqueBusinessRoles } from './accessRoleMatch'
import { isActiveAssignment } from './assignments'
import {
  useEmployee,
  useEmployeeLogin,
  useLinkLogin,
  useResetPassword,
  useUnlinkLogin,
  useUpdateEmployee,
  type EmployeeListRow,
} from './api'
import type { GroupedEmployee } from './EmployeesTab'

export function EmployeeDetailDrawer({
  employee,
  unitName,
  companyId,
  actor,
  canManageLogins,
  onClose,
  onCreateLogin,
  onEdit,
  onAssign,
  onEndAssignment,
  onCompensation,
  onTerminate,
  onSetOperatorPin,
}: {
  employee: GroupedEmployee
  unitName: (id: string | null) => string
  companyId: string
  actor: string
  /**
   * Whether this login may create/reset/remove a console LOGIN for this employee (org-service
   * `/api/v1/users/**`, OPS-only — owner/manager). When `false` (an hr-alone login on the People
   * page), the whole login-management surface below is read-only — most importantly, the team
   * list (`useTeam`) is never even FETCHED, since that call itself is OPS-only.
   */
  canManageLogins: boolean
  onClose: () => void
  onCreateLogin: () => void
  onEdit: () => void
  onAssign: () => void
  onEndAssignment: (row: EmployeeListRow) => void
  onCompensation: () => void
  onTerminate: () => void
  onSetOperatorPin: () => void
}) {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const employeeId = employee.employeeId
  const hasLogin = !!employee.userId
  const active = employee.status === 'ACTIVE'
  // ACTIVE only (effectiveTo === OPEN_ENDED) — `/api/v1/employees` returns one row per assignment
  // CURRENT as of today, which still includes an already-ended assignment (a real effectiveTo date)
  // for as long as that date hasn't passed yet; see `isActiveAssignment`. Both the map below and the
  // `assignments[0]` header/summary reference derive from this single filtered array, so an ended
  // assignment never renders (own row or as the header) and an employee whose only assignments have
  // ended correctly falls through to the "unassigned" empty state.
  const assignments = employee.rows.filter(isActiveAssignment)

  const detail = useEmployee({ companyId, actor, employeeId, enabled: true })
  // useEmployeeLogin reads employee-service's `/api/v1/employees/{id}/login` — HR-gated, so this
  // stays enabled regardless of `canManageLogins` (an hr login legitimately sees whether a login
  // exists and the held one-time password, since handing that over is a normal HR onboarding
  // task). useTeam below is the one that must NOT fire for a non-OPS login — it hits org-service's
  // `/api/v1/users`, OPS-only, and is needed only to resolve the login's USERNAME.
  const login = useEmployeeLogin({ companyId, actor, employeeId, enabled: hasLogin })
  const team = useTeam({ companyId, actor, enabled: hasLogin && canManageLogins })
  const reset = useResetPassword({ companyId, actor })
  const relink = useLinkLogin({ companyId, actor })
  const unlink = useUnlinkLogin({ companyId, actor })
  const updateEmployee = useUpdateEmployee({ companyId, actor })
  // Phase 1 (HR-job-title vs. app-access-role visibility) — grants the implied access role onto
  // the SAME Team endpoint the Team page's own change-role dialog uses (`PATCH /api/v1/users/{id}`,
  // OPS-only), so this button only ever renders inside the `canManageLogins` branch below.
  const grantAccess = useUpdateMember({ companyId, actor })
  const [busy, setBusy] = useState(false)
  // The freshly reset password, shown IMMEDIATELY from the reset result — so even if the follow-up
  // re-hold call fails, the owner still has the working credential in front of them (review Finding 5).
  const [revealedTemp, setRevealedTemp] = useState<string | null>(null)
  // Track P Phase P8 — the hire_date inline editor (feeds THR proration).
  const [editingHireDate, setEditingHireDate] = useState(false)
  const [hireDateDraft, setHireDateDraft] = useState('')
  // Track P Phase P10 — the npwp inline editor (Track P Phase P1/ADR 0031: drives the payroll
  // engine's no-NPWP ×120% surcharge). PII (rule 6): sent plaintext only when changing it, shown
  // masked everywhere after that — same posture as NIK/bank account, mirrors the hire_date editor.
  const [editingNpwp, setEditingNpwp] = useState(false)
  const [npwpDraft, setNpwpDraft] = useState('')
  const npwpValid = /^\d{15,16}$/.test(npwpDraft)

  async function handleSaveHireDate() {
    if (!hireDateDraft) return
    try {
      await updateEmployee.mutateAsync({ employeeId, body: { hireDate: hireDateDraft } })
      setEditingHireDate(false)
    } catch {
      // surfaced by updateEmployee.isError below
    }
  }

  async function handleSaveNpwp() {
    if (!npwpValid) return
    try {
      await updateEmployee.mutateAsync({ employeeId, body: { npwp: npwpDraft } })
      setEditingNpwp(false)
      setNpwpDraft('')
    } catch {
      // surfaced by updateEmployee.isError below
    }
  }

  const userId = login.data?.userId ?? employee.userId
  // The team list returns the LOCAL username (org-service strips the `<companyCode>.` prefix for a
  // clean display). Re-compose the FULL company-scoped login id here — that is what the employee
  // actually types to sign in (ADR 0054), and handing over the bare local (`leha`) was the bug.
  const username = displayLoginId(
    team.data?.find((m) => m.id === userId)?.username,
    company?.companyCode,
  ) || null
  const tempPassword = revealedTemp ?? login.data?.temporaryPassword ?? null

  async function handleReset() {
    if (!userId) return
    setBusy(true)
    try {
      const res = await reset.mutateAsync({ userId })
      if (!res) return
      setRevealedTemp(res.temporaryPassword)
      // Re-hold the fresh one-time password encrypted so it shows here until next sign-in.
      await relink.mutateAsync({ employeeId, userId, temporaryPassword: res.temporaryPassword })
      await login.refetch()
    } catch {
      // surfaced by the mutation error state below
    } finally {
      setBusy(false)
    }
  }

  async function handleRemove() {
    setBusy(true)
    try {
      await unlink.mutateAsync({ employeeId })
      await login.refetch()
    } catch {
      // surfaced below
    } finally {
      setBusy(false)
    }
  }

  const emp = detail.data?.employee
  const initials = employee.fullName
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p.charAt(0).toUpperCase())
    .join('')
  const headline = assignments[0]?.role
    ? `${assignments[0].role} · ${unitName(assignments[0].orgUnitId)}`
    : t('hr.list.unassigned')
  const monthYear = (iso: string | null) =>
    iso ? new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric' }).format(new Date(iso)) : null

  // Phase 1 (HR-job-title vs. app-access-role visibility) — the CURRENT assignment's job title
  // (same row the header/`headline` above already reads) vs. the linked login's actual Team page
  // access role(s). `accessMember` reuses the SAME `team` query the username lookup above already
  // fires (OPS-only, `hasLogin && canManageLogins`) — no extra API call.
  const currentAssignmentRole = assignments[0]?.role ?? null
  const accessMember = team.data?.find((m) => m.id === userId) ?? null
  const impliedRole = impliedAccessRole(currentAssignmentRole)
  const accessMismatch =
    accessMember != null && hasAccessRoleMismatch(currentAssignmentRole, accessMember.roles)

  async function handleGrantAccess() {
    if (!accessMember || !impliedRole) return
    try {
      // ADDITIVE union — useUpdateMember's PATCH replaces the whole role set, so this must include
      // every role the login already holds, not just the newly implied one.
      await grantAccess.mutateAsync({
        id: accessMember.id,
        body: { roles: uniqueBusinessRoles([...accessMember.roles, impliedRole]) },
      })
    } catch {
      // surfaced by grantAccess.isError below
    }
  }

  return (
    <Drawer onClose={onClose} ariaLabel={employee.fullName}>
      {/* Header */}
      <div className="flex flex-none items-center gap-3 border-b border-line px-5 pb-4 pt-5">
        <span className="grid size-11 shrink-0 place-items-center rounded-full bg-emerald-tint text-[15px] font-semibold text-emerald-2">
          {initials}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h2 className="truncate font-display text-[17px] font-bold text-ink">
              {employee.fullName}
            </h2>
            {!active ? <Badge tone="amber">{t('hr.list.inactive')}</Badge> : null}
          </div>
          <div className="mt-0.5 truncate text-[12.5px] font-medium text-ink-3">{headline}</div>
        </div>
        <button
          type="button"
          onClick={onEdit}
          aria-label={t('hr.list.actionEdit')}
          title={t('hr.list.actionEdit')}
          className="grid size-9 shrink-0 place-items-center rounded-[10px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          <Pencil className="size-4" />
        </button>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-9 shrink-0 place-items-center rounded-[10px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          <X className="size-[18px]" />
        </button>
      </div>

      {/* Body */}
      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
        {/* Assignments — one row per ACTIVE assignment (see `isActiveAssignment` above), ended IN
            PLACE (the row carries its own assignmentId, so "end" always targets exactly the
            assignment you can see). */}
        <SectionHeading>{t('hr.detail.assignments')}</SectionHeading>
        <div className="mt-2 flex flex-col gap-1.5">
          {assignments.length === 0 ? (
            <p className="text-sm text-ink-3">{t('hr.list.unassigned')}</p>
          ) : (
            assignments.map((row) => (
              <div
                key={row.assignmentId}
                className="flex items-center justify-between gap-2.5 rounded-xl border border-line px-3.5 py-3"
              >
                <span className="min-w-0">
                  <span className="block truncate text-[13px] font-semibold text-ink">
                    {row.role}
                  </span>
                  <span className="mt-0.5 block truncate text-[11.5px] font-medium text-ink-3">
                    {unitName(row.orgUnitId)}
                    {monthYear(row.effectiveFrom)
                      ? ` · ${t('hr.detail.since', { date: monthYear(row.effectiveFrom) })}`
                      : ''}
                  </span>
                </span>
                <button
                  type="button"
                  onClick={() => onEndAssignment(row)}
                  className="h-[30px] shrink-0 rounded-[9px] border border-line px-2.5 text-[11.5px] font-semibold text-ink-3 transition-colors hover:border-loss/40 hover:text-loss"
                >
                  {t('hr.detail.endAssignment')}
                </button>
              </div>
            ))
          )}
          {active ? (
            <button
              type="button"
              onClick={onAssign}
              className="mt-0.5 flex h-10 w-full items-center justify-center gap-1.5 rounded-[11px] border border-dashed border-line-strong text-[12.5px] font-semibold text-emerald-2 transition-colors hover:border-brand-300 hover:bg-emerald-tint"
            >
              <Plus className="size-3.5" />
              {t('hr.detail.assignAnother')}
            </button>
          ) : null}
        </div>

        {/* Compensation — masked by design (salary PII): state + action only, never amounts. */}
        <SectionHeading className="mt-6">{t('hr.detail.compensation')}</SectionHeading>
        <div className="mt-2 rounded-[14px] border border-line bg-paper p-4">
          <div className="flex items-center justify-between gap-3">
            {employee.hasCompensation ? (
              <Badge tone="emerald">{t('hr.list.compSet')}</Badge>
            ) : (
              <Badge tone="neutral">{t('hr.list.compNone')}</Badge>
            )}
            <span className="text-xs text-ink-3">{t('hr.detail.compHint')}</span>
          </div>
          <Button
            type="button"
            variant="outline"
            className="mt-3 w-full"
            onClick={onCompensation}
          >
            {t('hr.detail.compChange')}
          </Button>
        </div>

        {/* Login card */}
        <SectionHeading className="mt-6">{t('hr.detail.loginTitle')}</SectionHeading>
        <div className="mt-2 rounded-[14px] border border-line p-4">
          {!hasLogin ? (
            <div className="space-y-3">
              <p className="text-sm text-ink-3">
                {canManageLogins ? t('hr.detail.noLogin') : t('hr.detail.noLoginReadOnly')}
              </p>
              {canManageLogins ? (
                <Button type="button" variant="outline" onClick={onCreateLogin}>
                  <UserPlus className="size-4" />
                  {t('hr.list.actionCreateLogin')}
                </Button>
              ) : null}
            </div>
          ) : login.isLoading ? (
            <div className="space-y-3">
              <div>
                <Skeleton className="h-3 w-20" />
                <Skeleton className="mt-1.5 h-4 w-32" />
              </div>
              <Skeleton className="h-8 w-40 rounded-lg" />
              <div className="flex gap-2 pt-1">
                <Skeleton className="h-9 w-32 rounded-xl" />
                <Skeleton className="h-9 w-28 rounded-lg" />
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              {/* Username resolution needs the org-service team list (`useTeam`), OPS-only — an
                  hr-alone login (`!canManageLogins`) never fires that query, so it never renders a
                  username row rather than showing a permanent "…" placeholder. */}
              {canManageLogins ? (
                <Detail label={t('hr.detail.username')} value={username ?? '…'} mono />
              ) : null}

              {tempPassword ? (
                <div className="space-y-1.5">
                  <span className="text-xs font-medium text-ink-3">
                    {t('hr.detail.tempPassword')}
                  </span>
                  <div className="flex items-center gap-2 rounded-xl bg-paper px-3 py-2">
                    <code className="flex-1 truncate font-mono text-sm text-ink">
                      {tempPassword}
                    </code>
                    <CopyButton text={tempPassword} />
                  </div>
                  <p className="text-xs text-ink-3">{t('hr.detail.tempPasswordHint')}</p>
                </div>
              ) : (
                <div className="flex items-center gap-2">
                  <Badge tone="emerald">{t('hr.detail.passwordActive')}</Badge>
                  <span className="text-xs text-ink-3">{t('hr.detail.passwordActiveHint')}</span>
                </div>
              )}

              {/* Reset (org-service `/api/v1/users/{id}/reset-password`, OPS-only) and Remove are
                  bundled behind ONE `canManageLogins` gate — deliberately conservative: Remove
                  itself is HR-gated at the API (employee-service login-link), but pairing "can
                  delete this login" with "cannot create one" reads as a confusing half-permission,
                  so both stay owner/manager-only here, matching Create above. */}
              {canManageLogins ? (
                <div className="flex flex-wrap gap-2 pt-1">
                  <Button type="button" variant="outline" disabled={busy} onClick={handleReset}>
                    <KeyRound className="size-4" />
                    {busy && reset.isPending
                      ? t('hr.detail.resetting')
                      : t('hr.detail.resetPassword')}
                  </Button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={handleRemove}
                    className="inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs text-loss/80 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss disabled:opacity-50"
                  >
                    <Trash2 className="size-4" />
                    {t('hr.detail.removeLogin')}
                  </button>
                </div>
              ) : (
                <p className="text-xs text-ink-3">{t('hr.detail.loginManageRestricted')}</p>
              )}
              {canManageLogins && (reset.isError || relink.isError || unlink.isError) ? (
                <p className="text-sm text-loss">{t('hr.assign.error')}</p>
              ) : null}
            </div>
          )}
        </div>

        {/* App access (Phase 1 — HR-job-title vs. app-access-role visibility fix). The job title
            above is FREE TEXT and grants nothing; this shows the login's REAL Keycloak access
            role(s) (the Team page) and, on a mismatch, offers a 1-click additive grant. */}
        <SectionHeading className="mt-6">{t('hr.appAccess.title')}</SectionHeading>
        <div className="mt-2 rounded-[14px] border border-line p-4">
          {!hasLogin ? (
            <p className="text-sm text-ink-3">{t('hr.appAccess.noLogin')}</p>
          ) : !canManageLogins ? (
            // useTeam (org-service /api/v1/users) is OPS-only, so it is never even FETCHED for an
            // hr-alone viewer (same reasoning as the Username row above) — there is no real access-
            // role value to show read-only here, only this restriction notice.
            <p className="text-sm text-ink-3">{t('hr.appAccess.restricted')}</p>
          ) : team.isLoading ? (
            <div className="space-y-2">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="h-4 w-56" />
            </div>
          ) : !accessMember ? (
            <p className="text-sm text-ink-3">{t('hr.appAccess.unknown')}</p>
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-1.5">
                {accessMember.roles.length === 0 ? (
                  <Badge tone="neutral">{t('hr.appAccess.none')}</Badge>
                ) : (
                  accessMember.roles.map((r) => <AccessRoleBadge key={r} role={r} />)
                )}
              </div>

              {accessMismatch && impliedRole ? (
                <div className="space-y-2.5 rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5">
                  <p className="flex items-start gap-2 text-xs leading-relaxed text-amber">
                    <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
                    {t('hr.appAccess.mismatch', {
                      role: currentAssignmentRole,
                      access:
                        accessMember.roles.length > 0
                          ? accessMember.roles.map((r) => t(`team.role.${r}`)).join(', ')
                          : t('hr.appAccess.none'),
                    })}
                  </p>
                  <Button
                    type="button"
                    variant="outline"
                    className="h-9 text-xs"
                    disabled={grantAccess.isPending}
                    onClick={handleGrantAccess}
                  >
                    <ShieldCheck className="size-3.5" />
                    {grantAccess.isPending
                      ? t('hr.appAccess.granting')
                      : t('hr.appAccess.grant', { role: t(`team.role.${impliedRole}`) })}
                  </Button>
                  {grantAccess.isError ? (
                    <p className="text-xs text-loss">{t('hr.appAccess.grantError')}</p>
                  ) : null}
                </div>
              ) : null}
            </div>
          )}
        </div>

        {/* Operator PIN (ADR 0049 P1) — the till PIN, separate from the console login above. Never
            revealed here (write-only, rule 6): the action always opens a fresh set/reset dialog. */}
        <SectionHeading className="mt-6">{t('hr.detail.operatorPinTitle')}</SectionHeading>
        <div className="mt-2 rounded-[14px] border border-line p-4">
          <p className="text-sm text-ink-3">{t('hr.detail.operatorPinHint')}</p>
          <Button type="button" variant="outline" className="mt-3" onClick={onSetOperatorPin}>
            <Lock className="size-4" />
            {t('hr.detail.operatorPinAction')}
          </Button>
        </div>

        {/* Profile (PII masked) */}
        <SectionHeading className="mt-6">{t('hr.detail.profile')}</SectionHeading>
        <div className="mt-2 grid grid-cols-2 gap-3 rounded-[14px] border border-line p-4 text-sm">
          <Detail label={t('hr.detail.nik')} value={emp?.maskedNik ?? '—'} />
          <Detail label={t('hr.detail.bank')} value={emp?.maskedBankAccount ?? '—'} />
          <Detail label={t('hr.form.ptkpStatus')} value={employee.rows[0]?.ptkpStatus ?? '—'} />
        </div>

        {/* hire_date (Track P Phase P8, ADR 0035) — NOT PII, feeds THR proration. */}
        <section className="mt-3 rounded-[14px] border border-line p-4">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3 className="text-sm font-semibold text-ink">{t('hr.detail.hireDate')}</h3>
              <p className="mt-0.5 text-xs text-ink-3">{t('hr.detail.hireDateHint')}</p>
            </div>
            {!editingHireDate ? (
              <Button
                type="button"
                variant="outline"
                className="h-8 px-3 text-xs"
                onClick={() => {
                  setHireDateDraft(emp?.hireDate ?? '')
                  setEditingHireDate(true)
                }}
              >
                <Pencil className="size-3.5" />
                {t('hr.detail.hireDateEdit')}
              </Button>
            ) : null}
          </div>

          {editingHireDate ? (
            <div className="mt-3 space-y-2">
              <Field label={t('hr.detail.hireDate')} htmlFor="hr-hire-date">
                <TextInput
                  id="hr-hire-date"
                  type="date"
                  value={hireDateDraft}
                  onChange={(e) => setHireDateDraft(e.target.value)}
                />
              </Field>
              {updateEmployee.isError ? (
                <p className="text-sm text-loss">{t('hr.detail.hireDateError')}</p>
              ) : null}
              <div className="flex gap-2">
                <Button
                  type="button"
                  className="h-8 px-3 text-xs"
                  onClick={handleSaveHireDate}
                  disabled={!hireDateDraft || updateEmployee.isPending}
                >
                  {updateEmployee.isPending
                    ? t('hr.detail.hireDateSaving')
                    : t('hr.detail.hireDateSave')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="h-8 px-3 text-xs"
                  onClick={() => setEditingHireDate(false)}
                  disabled={updateEmployee.isPending}
                >
                  {t('hr.detail.hireDateCancel')}
                </Button>
              </div>
            </div>
          ) : (
            <p className="mt-2 text-sm text-ink">{emp?.hireDate ?? t('hr.detail.hireDateNone')}</p>
          )}
        </section>

        {/* npwp (Track P Phase P1/P10, ADR 0031) — PII (rule 6): masked once on file; missing NPWP
            drives the payroll engine's ×120% no-NPWP surcharge, so this nudges completion loudly. */}
        <section className="mt-3 rounded-[14px] border border-line p-4">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3 className="text-sm font-semibold text-ink">{t('hr.detail.npwp')}</h3>
              <p className="mt-0.5 text-xs text-ink-3">{t('hr.detail.npwpHint')}</p>
            </div>
            {!editingNpwp ? (
              <Button
                type="button"
                variant="outline"
                className="h-8 px-3 text-xs"
                onClick={() => {
                  setNpwpDraft('')
                  setEditingNpwp(true)
                }}
              >
                <Pencil className="size-3.5" />
                {emp?.hasNpwp ? t('hr.detail.npwpEdit') : t('hr.detail.npwpAdd')}
              </Button>
            ) : null}
          </div>

          {editingNpwp ? (
            <div className="mt-3 space-y-2">
              <Field label={t('hr.detail.npwp')} htmlFor="hr-npwp" hint={t('hr.form.npwpHint')}>
                <TextInput
                  id="hr-npwp"
                  value={npwpDraft}
                  onChange={(e) => setNpwpDraft(e.target.value.replace(/\D/g, ''))}
                  inputMode="numeric"
                  maxLength={16}
                />
              </Field>
              {npwpDraft && !npwpValid ? (
                <p className="text-sm text-loss">{t('hr.form.npwpInvalid')}</p>
              ) : updateEmployee.isError ? (
                <p className="text-sm text-loss">{t('hr.detail.npwpError')}</p>
              ) : null}
              <div className="flex gap-2">
                <Button
                  type="button"
                  className="h-8 px-3 text-xs"
                  onClick={handleSaveNpwp}
                  disabled={!npwpValid || updateEmployee.isPending}
                >
                  {updateEmployee.isPending ? t('hr.detail.hireDateSaving') : t('hr.detail.hireDateSave')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="h-8 px-3 text-xs"
                  onClick={() => {
                    // P10 review S2 — clear the plaintext draft on cancel too (in-memory only, but
                    // matches the masked-everywhere posture the rest of this drawer holds to).
                    setNpwpDraft('')
                    setEditingNpwp(false)
                  }}
                  disabled={updateEmployee.isPending}
                >
                  {t('hr.detail.hireDateCancel')}
                </Button>
              </div>
            </div>
          ) : emp?.hasNpwp ? (
            <p className="mt-2 font-mono text-sm text-ink">{emp.maskedNpwp}</p>
          ) : (
            <div className="mt-2 flex items-center gap-2">
              <Badge tone="amber">{t('hr.detail.npwpMissingBadge')}</Badge>
              <span className="text-xs text-ink-3">{t('hr.detail.npwpMissingHint')}</span>
            </div>
          )}
        </section>
      </div>

      {/* Footer — the destructive action lives here, visible but out of the everyday path. */}
      <div className="flex flex-none gap-2.5 border-t border-line px-5 py-4">
        {active ? (
          <button
            type="button"
            onClick={onTerminate}
            className="h-11 shrink-0 rounded-xl border border-loss/30 px-4 text-[13px] font-semibold text-loss transition-colors hover:bg-tint-loss"
          >
            {t('hr.list.actionTerminate')}
          </button>
        ) : null}
        <Button type="button" className="h-11 flex-1" onClick={onClose}>
          {t('common.close')}
        </Button>
      </div>
    </Drawer>
  )
}

function SectionHeading({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'text-[10.5px] font-bold uppercase tracking-[0.08em] text-ink-3',
        className,
      )}
    >
      {children}
    </div>
  )
}

/**
 * A single app-access-role badge — mirrors `features/team/Team.tsx`'s own `RoleBadge` (tinted
 * fill, `owner` alone carries the brand tint, every other role neutral) so the same role reads
 * identically here and on the Team page. Not imported from `Team.tsx` (that file exports no
 * components) — reuses `BUSINESS_ROLES`/`team.role.<role>`, the single source of truth for the 8
 * access roles, instead of redefining the list.
 */
function AccessRoleBadge({ role }: { role: string }) {
  const { t } = useTranslation()
  const tone = role === 'owner' ? ('emerald' as const) : ('neutral' as const)
  const label = (BUSINESS_ROLES as readonly string[]).includes(role)
    ? t(`team.role.${role as BusinessRole}`)
    : role
  return <Badge tone={tone}>{label}</Badge>
}

function Detail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-ink-3">{label}</div>
      <div className={cn('truncate text-ink', mono && 'font-mono text-[13px]')}>{value}</div>
    </div>
  )
}

function CopyButton({ text }: { text: string }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  function handleCopy() {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }
  return (
    <button
      type="button"
      aria-label={t('hr.detail.copyPassword')}
      title={t('hr.detail.copyPassword')}
      onClick={handleCopy}
      className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink"
    >
      {copied ? <Check className="size-4 text-emerald-2" /> : <Copy className="size-4" />}
    </button>
  )
}
