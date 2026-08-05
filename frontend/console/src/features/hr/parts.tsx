/**
 * HR dialog building blocks for the org-unit hub Employees tab. Reuses the org feature's
 * DialogOverlay (backdrop + Escape) and the house Field/TextInput/Button primitives. All copy via
 * i18n (rule 9). Salary is PII: the compensation dialog collects a major-unit amount once,
 * converts to integer minor units (never a float on the wire beyond the input parse), and only
 * ever renders masked packages back.
 */

import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ErrorDetails } from '@/components/ErrorDetails'
import { DialogOverlay } from '@/features/org/parts'
import type { OrgUnit } from '@/features/org/api'
import { localeOf } from '@/i18n'
import { formatPercent, isoMinorExponent } from '@/lib/money'
import {
  OPEN_ENDED,
  ROLE_PRESETS,
  useAddAssignment,
  useAddContract,
  useCommissions,
  useCompensation,
  useCreateCompensation,
  useCreateEmployee,
  useEmployee,
  useEmployees,
  useEndAssignment,
  useEndCompensation,
  useEndCommission,
  useOrgUnitLookup,
  useSetCommission,
  useUpdateEmployee,
  type EmployeeListRow,
} from './api'

const PTKP_STATUSES = ['TK0', 'TK1', 'TK2', 'TK3', 'K0', 'K1', 'K2', 'K3'] as const
const EMPLOYMENT_TYPES = ['PERMANENT', 'CONTRACT'] as const

const SELECT_CLASSES =
  'w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

/** Job-role input: free text + a datalist of preset suggestions (i18n labels as hints). */
export function RolePresetInput({
  id,
  value,
  onChange,
}: {
  id: string
  value: string
  onChange: (v: string) => void
}) {
  const { t } = useTranslation()
  const listId = `${id}-presets`
  return (
    <>
      <TextInput
        id={id}
        list={listId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={t('hr.form.rolePlaceholder')}
        maxLength={128}
        required
      />
      <datalist id={listId}>
        {ROLE_PRESETS.map((r) => (
          <option key={r} value={r}>
            {t(`hr.roles.${r}` as Parameters<typeof t>[0], { defaultValue: r })}
          </option>
        ))}
      </datalist>
    </>
  )
}

/** Selectable org units for an assignment: the current unit + (on a BU) its active child outlets. */
function unitOptions(unit: OrgUnit, childOutlets: OrgUnit[]): OrgUnit[] {
  return unit.type === 'BUSINESS_UNIT'
    ? [unit, ...childOutlets.filter((o) => o.active)]
    : [unit]
}

// ---------------------------------------------------------------------------
// Create / edit employee
// ---------------------------------------------------------------------------

/**
 * Create = a sequential chain (POST employee → POST contract → POST assignment). Not atomic: a
 * step failure surfaces which step broke; the partial employee stays visible in the list and the
 * missing pieces are recoverable via the Assign / Compensation actions.
 */
export function EmployeeFormDialog({
  unit,
  childOutlets,
  companyId,
  actor,
  edit,
  onClose,
}: {
  unit: OrgUnit
  childOutlets: OrgUnit[]
  companyId: string
  actor: string
  /** When set, the dialog PATCHes name/PTKP instead of running the create chain. */
  edit: EmployeeListRow | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const options = unitOptions(unit, childOutlets)

  const [fullName, setFullName] = useState(edit?.fullName ?? '')
  const [ptkpStatus, setPtkpStatus] = useState(edit?.ptkpStatus ?? 'TK0')
  const [nik, setNik] = useState('')
  const [bankAccount, setBankAccount] = useState('')
  const [employmentType, setEmploymentType] = useState<string>('PERMANENT')
  const [orgUnitId, setOrgUnitId] = useState(options[0]?.id ?? unit.id)
  const [role, setRole] = useState('')
  const [startDate, setStartDate] = useState(todayIso())
  const [failedStep, setFailedStep] = useState<'employee' | 'contract' | 'assignment' | null>(null)
  const [clientError, setClientError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const createEmployee = useCreateEmployee({ companyId, actor })
  const addContract = useAddContract({ companyId, actor })
  const addAssignment = useAddAssignment({ companyId, actor })
  const updateEmployee = useUpdateEmployee({ companyId, actor })
  const lookup = useOrgUnitLookup({ companyId, actor, ids: [orgUnitId], enabled: !edit })

  const legalEmployerId = lookup.data?.[0]?.legalEmployerId ?? null
  // Why Save might be disabled — surfaced as text, never a silently dead button.
  const unitNotSynced = !edit && !lookup.isLoading && !lookup.isError && !legalEmployerId

  /** Client-side sanity, mirroring the server whitelists (NIK 16 digits, bank 6–32 digits). */
  function validate(): string | null {
    if (!fullName.trim() || fullName.trim().length > 255) return 'hr.form.nameInvalid'
    if (edit) return null
    if (!/^\d{16}$/.test(nik.trim())) return 'hr.form.nikInvalid'
    if (!/^\d{6,32}$/.test(bankAccount.trim())) return 'hr.form.bankInvalid'
    if (!role.trim() || role.trim().length > 128) return 'hr.form.roleInvalid'
    if (!startDate) return 'hr.form.dateInvalid'
    return null
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const invalid = validate()
    if (invalid) {
      setClientError(invalid)
      return
    }
    setClientError(null)
    if (edit) {
      updateEmployee.mutate(
        { employeeId: edit.employeeId, body: { fullName: fullName.trim(), ptkpStatus } },
        { onSuccess: onClose },
      )
      return
    }
    if (!legalEmployerId) return
    setSaving(true)
    setFailedStep(null)
    try {
      let employeeId: string
      try {
        const created = await createEmployee.mutateAsync({
          fullName: fullName.trim(),
          ptkpStatus,
          nik: nik.trim(),
          bankAccount: bankAccount.trim(),
        })
        if (!created) {
          throw new Error('empty create response')
        }
        employeeId = created.id
      } catch (err) {
        setFailedStep('employee')
        throw err
      }
      try {
        await addContract.mutateAsync({
          employeeId,
          body: { employmentType, legalEmployerId, effectiveFrom: startDate },
        })
      } catch (err) {
        setFailedStep('contract')
        throw err
      }
      try {
        await addAssignment.mutateAsync({
          employeeId,
          body: { orgUnitId, role: role.trim(), effectiveFrom: startDate },
        })
      } catch (err) {
        setFailedStep('assignment')
        throw err
      }
      onClose()
    } catch {
      // failedStep carries which step broke; the list shows the recoverable partial state.
    } finally {
      setSaving(false)
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="max-h-[80vh] space-y-4 overflow-y-auto">
        <h2 className="font-display text-lg font-semibold text-ink">
          {edit ? t('hr.form.editTitle') : t('hr.form.createTitle')}
        </h2>

        <Field label={t('hr.form.fullName')} htmlFor="hr-name">
          <TextInput
            id="hr-name"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            required
            autoFocus
          />
        </Field>

        <Field label={t('hr.form.ptkpStatus')} htmlFor="hr-ptkp">
          <select
            id="hr-ptkp"
            value={ptkpStatus}
            onChange={(e) => setPtkpStatus(e.target.value)}
            className={SELECT_CLASSES}
          >
            {PTKP_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>

        {!edit ? (
          <>
            <Field label={t('hr.form.nik')} htmlFor="hr-nik" hint={t('hr.form.nikHint')}>
              <TextInput
                id="hr-nik"
                value={nik}
                onChange={(e) => setNik(e.target.value.replace(/\D/g, ''))}
                inputMode="numeric"
                maxLength={16}
                required
              />
            </Field>
            <Field label={t('hr.form.bankAccount')} htmlFor="hr-bank" hint={t('hr.form.bankHint')}>
              <TextInput
                id="hr-bank"
                value={bankAccount}
                onChange={(e) => setBankAccount(e.target.value.replace(/\D/g, ''))}
                inputMode="numeric"
                maxLength={32}
                required
              />
            </Field>
            <Field label={t('hr.form.employmentType')} htmlFor="hr-emptype">
              <select
                id="hr-emptype"
                value={employmentType}
                onChange={(e) => setEmploymentType(e.target.value)}
                className={SELECT_CLASSES}
              >
                {EMPLOYMENT_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {t(
                      type === 'PERMANENT' ? 'hr.form.typePermanent' : 'hr.form.typeContract',
                    )}
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('hr.form.unit')} htmlFor="hr-unit">
              <select
                id="hr-unit"
                value={orgUnitId}
                onChange={(e) => setOrgUnitId(e.target.value)}
                className={SELECT_CLASSES}
              >
                {options.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
              {/* Save gates on the unit's legal employer — say so instead of a dead button. */}
              {lookup.isLoading ? (
                <p className="mt-1 text-xs text-ink-3">{t('hr.form.unitChecking')}</p>
              ) : null}
              {unitNotSynced || lookup.isError ? (
                <p className="mt-1 text-xs text-loss">{t('hr.form.unitNotSynced')}</p>
              ) : null}
            </Field>
            <Field label={t('hr.form.role')} htmlFor="hr-role">
              <RolePresetInput id="hr-role" value={role} onChange={setRole} />
            </Field>
            <Field label={t('hr.form.startDate')} htmlFor="hr-start">
              <TextInput
                id="hr-start"
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                required
              />
            </Field>
          </>
        ) : null}

        {clientError ? (
          <p className="text-sm text-loss">
            {t(clientError as Parameters<typeof t>[0])}
          </p>
        ) : null}
        {failedStep ? (
          <p className="text-sm text-loss">
            {t(
              failedStep === 'employee'
                ? 'hr.form.stepEmployeeFailed'
                : failedStep === 'contract'
                  ? 'hr.form.stepContractFailed'
                  : 'hr.form.stepAssignmentFailed',
            )}
          </p>
        ) : null}
        {edit && updateEmployee.isError ? (
          <p className="text-sm text-loss">{t('hr.form.stepEmployeeFailed')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            disabled={
              saving ||
              updateEmployee.isPending ||
              !fullName.trim() ||
              (!edit && (!role.trim() || !legalEmployerId))
            }
          >
            {saving || updateEmployee.isPending ? t('hr.form.saving') : t('hr.form.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Assign to a unit/outlet
// ---------------------------------------------------------------------------

export function AssignDialog({
  employee,
  unit,
  childOutlets,
  companyId,
  actor,
  onClose,
}: {
  employee: EmployeeListRow
  unit: OrgUnit
  childOutlets: OrgUnit[]
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const options = unitOptions(unit, childOutlets)
  const [orgUnitId, setOrgUnitId] = useState(options[0]?.id ?? unit.id)
  const [role, setRole] = useState(employee.role ?? '')
  const [startDate, setStartDate] = useState(todayIso())
  const mutation = useAddAssignment({ companyId, actor })

  const conflict =
    mutation.isError &&
    (mutation.error as { status?: number } | null)?.status === 409

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      {
        employeeId: employee.employeeId,
        body: { orgUnitId, role: role.trim(), effectiveFrom: startDate },
      },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.assign.title', { name: employee.fullName })}
        </h2>

        <Field label={t('hr.assign.unit')} htmlFor="assign-unit">
          <select
            id="assign-unit"
            value={orgUnitId}
            onChange={(e) => setOrgUnitId(e.target.value)}
            className={SELECT_CLASSES}
          >
            {options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t('hr.assign.role')} htmlFor="assign-role">
          <RolePresetInput id="assign-role" value={role} onChange={setRole} />
        </Field>
        <Field label={t('hr.assign.startDate')} htmlFor="assign-start">
          <TextInput
            id="assign-start"
            type="date"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            required
          />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">
            {conflict ? t('hr.assign.legalEmployerConflict') : t('hr.assign.error')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !role.trim()}>
            {mutation.isPending ? t('hr.form.saving') : t('hr.assign.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Assign an EXISTING employee to an outlet (outlets never create employees —
// employees are created at the business-unit level and positioned from there).
// ---------------------------------------------------------------------------

export function AssignExistingDialog({
  outlet,
  units,
  companyId,
  actor,
  onClose,
}: {
  outlet: OrgUnit
  units: OrgUnit[]
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()

  // The pool is the parent business unit's employees (the BU + all its outlets) — the "employees of
  // this business". Outlets assign from that pool; they never create.
  const parentBu = units.find((u) => u.id === outlet.parentId)
  const buScope = parentBu
    ? [parentBu.id, ...units.filter((u) => u.parentId === parentBu.id).map((u) => u.id)]
    : [outlet.id]
  const employeesQuery = useEmployees({ companyId, actor, orgUnitIds: buScope, enabled: true })

  // Distinct ACTIVE employees, excluding any already positioned at THIS outlet.
  const pool = useMemo(() => {
    const seen = new Map<string, EmployeeListRow>()
    const atOutlet = new Set<string>()
    for (const r of employeesQuery.data ?? []) {
      if (r.orgUnitId === outlet.id && r.assignmentId) atOutlet.add(r.employeeId)
      if (r.status === 'ACTIVE' && !seen.has(r.employeeId)) seen.set(r.employeeId, r)
    }
    return [...seen.values()].filter((e) => !atOutlet.has(e.employeeId))
  }, [employeesQuery.data, outlet.id])

  const [employeeId, setEmployeeId] = useState('')
  const [role, setRole] = useState('')
  const [startDate, setStartDate] = useState(todayIso())
  const mutation = useAddAssignment({ companyId, actor })
  const conflict =
    mutation.isError && (mutation.error as { status?: number } | null)?.status === 409

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!employeeId) return
    mutation.mutate(
      { employeeId, body: { orgUnitId: outlet.id, role: role.trim(), effectiveFrom: startDate } },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.assignExisting.title', { unit: outlet.name })}
        </h2>
        <p className="text-sm text-ink-3">{t('hr.assignExisting.subtitle')}</p>

        {employeesQuery.isError ? (
          // A dead/erroring employee-service must read as a failure with a way out — never as
          // endless loading, and never as the misleading "no employees" empty state.
          <div className="space-y-3">
            <p className="text-sm text-loss">{t('hr.list.error')}</p>
            <ErrorDetails error={employeesQuery.error} />
            <Button type="button" variant="outline" onClick={() => employeesQuery.refetch()}>
              {t('common.retry')}
            </Button>
          </div>
        ) : employeesQuery.isLoading ? (
          <p className="text-sm text-ink-3">{t('common.loading')}</p>
        ) : pool.length === 0 ? (
          <p className="text-sm text-ink-3">{t('hr.assignExisting.empty')}</p>
        ) : (
          <>
            <Field label={t('hr.assignExisting.employee')} htmlFor="assign-existing-emp">
              <select
                id="assign-existing-emp"
                value={employeeId}
                onChange={(e) => setEmployeeId(e.target.value)}
                className={SELECT_CLASSES}
                required
              >
                <option value="" disabled>
                  {t('hr.assignExisting.pick')}
                </option>
                {pool.map((e) => (
                  <option key={e.employeeId} value={e.employeeId}>
                    {e.fullName}
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('hr.assign.role')} htmlFor="assign-existing-role">
              <RolePresetInput id="assign-existing-role" value={role} onChange={setRole} />
            </Field>
            <Field label={t('hr.assign.startDate')} htmlFor="assign-existing-start">
              <TextInput
                id="assign-existing-start"
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                required
              />
            </Field>
          </>
        )}

        {mutation.isError ? (
          <p className="text-sm text-loss">
            {conflict ? t('hr.assign.legalEmployerConflict') : t('hr.assign.error')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            disabled={mutation.isPending || !employeeId || !role.trim() || pool.length === 0}
          >
            {mutation.isPending ? t('hr.form.saving') : t('hr.assignExisting.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// End an assignment
// ---------------------------------------------------------------------------

export function EndAssignmentDialog({
  employee,
  companyId,
  actor,
  onClose,
}: {
  employee: EmployeeListRow
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [endOn, setEndOn] = useState(todayIso())
  const mutation = useEndAssignment({ companyId, actor })

  function handleConfirm() {
    if (!employee.assignmentId) return
    mutation.mutate(
      { employeeId: employee.employeeId, assignmentId: employee.assignmentId, endOn },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.endAssignment.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('hr.endAssignment.body', { name: employee.fullName })}
        </p>
        <Field label={t('hr.endAssignment.endOn')} htmlFor="end-on">
          <TextInput
            id="end-on"
            type="date"
            value={endOn}
            onChange={(e) => setEndOn(e.target.value)}
            required
          />
        </Field>
        {mutation.isError ? <p className="text-sm text-loss">{t('hr.assign.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={mutation.isPending}>
            {mutation.isPending ? t('hr.form.saving') : t('hr.endAssignment.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Compensation (masked; replace = end old + create new)
// ---------------------------------------------------------------------------

export function CompensationDialog({
  employee,
  companyId,
  actor,
  baseCurrency,
  onClose,
}: {
  employee: EmployeeListRow
  companyId: string
  actor: string
  baseCurrency: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [amountMajor, setAmountMajor] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState(todayIso())

  const detail = useEmployee({
    companyId,
    actor,
    employeeId: employee.employeeId,
    enabled: true,
  })
  const packages = useCompensation({
    companyId,
    actor,
    employeeId: employee.employeeId,
    enabled: true,
  })
  const createComp = useCreateCompensation({ companyId, actor })
  const endComp = useEndCompensation({ companyId, actor })

  // The employee's contracts come from the detail read; default to the newest.
  const contracts = useMemo(() => detail.data?.contracts ?? [], [detail.data])
  const effectiveContractId = contracts[contracts.length - 1]?.id ?? ''

  const openPackage = (packages.data ?? []).find((p) => p.effectiveTo === OPEN_ENDED) ?? null
  // The replace flow ends the open package the day BEFORE the new start — impossible when the
  // open package began on/after that date (the end would precede its start → server 400). Guard
  // with an explanation instead of a dead-ended request.
  const replaceDateConflict = openPackage !== null && openPackage.effectiveFrom >= effectiveFrom

  // Integer minor units — parse the major-unit input and scale by the ISO exponent (never a
  // float on the wire; Math.round guards binary-representation dust). Sanity-capped so a fat
  // finger cannot book an absurd salary (10^13 minor ≈ IDR 10 trillion).
  const MAX_MINOR = 10_000_000_000_000
  const exponent = isoMinorExponent(baseCurrency)
  const parsedMajor = Number(amountMajor)
  const rawMinor =
    Number.isFinite(parsedMajor) && parsedMajor > 0
      ? Math.round(parsedMajor * 10 ** exponent)
      : null
  const basePayMinor = rawMinor !== null && rawMinor > 0 && rawMinor <= MAX_MINOR ? rawMinor : null
  const amountInvalid = amountMajor !== '' && basePayMinor === null

  const saving = createComp.isPending || endComp.isPending

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!basePayMinor || !effectiveContractId || replaceDateConflict) return
    try {
      if (openPackage) {
        // Replace flow: end the open package the day before the new one starts.
        const dayBefore = new Date(effectiveFrom)
        dayBefore.setDate(dayBefore.getDate() - 1)
        await endComp.mutateAsync({
          employeeId: employee.employeeId,
          packageId: openPackage.id,
          endOn: dayBefore.toISOString().slice(0, 10),
        })
      }
      await createComp.mutateAsync({
        employeeId: employee.employeeId,
        body: {
          employmentContractId: effectiveContractId,
          basePayMinor,
          currency: baseCurrency,
          effectiveFrom,
        },
      })
      onClose()
    } catch {
      // The mutation error state renders below.
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.compensation.title', { name: employee.fullName })}
        </h2>

        {/* Current packages — always masked (salary PII never renders). */}
        <div className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
            {t('hr.compensation.current')}
          </p>
          {(packages.data ?? []).length === 0 ? (
            <p className="mt-1 text-ink-3">{t('hr.compensation.none')}</p>
          ) : (
            (packages.data ?? []).map((p) => (
              <p key={p.id} className="mt-1 font-mono text-ink">
                {t('hr.compensation.masked', {
                  from: p.effectiveFrom,
                  to: p.effectiveTo === OPEN_ENDED ? '—' : p.effectiveTo,
                })}
              </p>
            ))
          )}
        </div>

        {/* Own-sales commission (only once an open package exists to attach it to). */}
        {openPackage ? (
          <CommissionControl
            employeeId={employee.employeeId}
            packageId={openPackage.id}
            companyId={companyId}
            actor={actor}
          />
        ) : null}

        <Field
          label={t('hr.compensation.base', { currency: baseCurrency })}
          htmlFor="comp-amount"
          hint={t('hr.compensation.baseHint')}
        >
          <TextInput
            id="comp-amount"
            type="number"
            min="1"
            step="any"
            value={amountMajor}
            onChange={(e) => setAmountMajor(e.target.value)}
            required
          />
        </Field>
        <Field label={t('hr.compensation.effectiveFrom')} htmlFor="comp-from">
          <TextInput
            id="comp-from"
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
            required
          />
        </Field>

        {amountInvalid ? (
          <p className="text-sm text-loss">{t('hr.compensation.amountInvalid')}</p>
        ) : null}
        {replaceDateConflict ? (
          <p className="text-sm text-loss">
            {t('hr.compensation.replaceDateConflict', { date: openPackage.effectiveFrom })}
          </p>
        ) : null}
        {!effectiveContractId && !detail.isLoading ? (
          <p className="text-sm text-loss">{t('hr.compensation.noContract')}</p>
        ) : null}
        {createComp.isError || endComp.isError ? (
          <p className="text-sm text-loss">
            {(createComp.error as { status?: number } | null)?.status === 409
              ? t('hr.compensation.overlapError')
              : t('hr.assign.error')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            disabled={saving || !basePayMinor || !effectiveContractId || replaceDateConflict}
          >
            {saving
              ? t('hr.compensation.saving')
              : openPackage
                ? t('hr.compensation.replace')
                : t('hr.compensation.set')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/**
 * Own-sales commission on a compensation package — a percentage the employee earns on the sales
 * they ring under their own login. Non-PII config (the % is echoed as its real value). Shows the
 * current commission with a clear action, or a set form. Standalone from the base-pay form.
 */
function CommissionControl({
  employeeId,
  packageId,
  companyId,
  actor,
}: {
  employeeId: string
  packageId: string
  companyId: string
  actor: string
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const commissions = useCommissions({ companyId, actor, employeeId, packageId, enabled: true })
  const setCommission = useSetCommission({ companyId, actor })
  const endCommission = useEndCommission({ companyId, actor })
  const [percent, setPercent] = useState('')

  const open = (commissions.data ?? []).find((c) => c.effectiveTo === OPEN_ENDED) ?? null

  function handleSet() {
    const pct = Number(percent)
    if (!Number.isFinite(pct) || pct <= 0 || pct > 100) return
    setCommission.mutate(
      { employeeId, packageId, percentBasisPoints: Math.round(pct * 100) },
      { onSuccess: () => setPercent('') },
    )
  }

  return (
    <div className="rounded-xl border border-line bg-paper px-3.5 py-3">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('hr.commission.title')}
      </p>
      {open ? (
        <div className="mt-2 flex items-center justify-between gap-3">
          <span className="text-sm text-ink">
            {t('hr.commission.current', {
              pct: formatPercent(open.percentBasisPoints / 10000, locale),
            })}
          </span>
          <button
            type="button"
            onClick={() => endCommission.mutate({ employeeId, packageId, ruleId: open.id })}
            disabled={endCommission.isPending}
            className="rounded-md px-2 py-1 text-xs font-semibold text-loss/80 hover:bg-tint-loss hover:text-loss"
          >
            {t('hr.commission.clear')}
          </button>
        </div>
      ) : (
        <div className="mt-2 flex items-center gap-2">
          <div className="relative flex-1">
            <TextInput
              id="commission-pct"
              type="number"
              min="0.1"
              max="100"
              step="0.1"
              value={percent}
              onChange={(e) => setPercent(e.target.value)}
              placeholder={t('hr.commission.placeholder')}
            />
          </div>
          <Button
            type="button"
            variant="outline"
            onClick={handleSet}
            disabled={setCommission.isPending || !percent}
          >
            {t('hr.commission.set')}
          </Button>
        </div>
      )}
      <p className="mt-1.5 text-xs text-ink-3">{t('hr.commission.hint')}</p>
      {setCommission.isError ? (
        <p className="mt-1 text-xs text-loss">{t('hr.assign.error')}</p>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Terminate (end open assignments, then INACTIVE)
// ---------------------------------------------------------------------------

export function TerminateDialog({
  employee,
  companyId,
  actor,
  onClose,
}: {
  employee: EmployeeListRow
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [failed, setFailed] = useState(false)
  const [saving, setSaving] = useState(false)
  const detail = useEmployee({
    companyId,
    actor,
    employeeId: employee.employeeId,
    enabled: true,
  })
  const endAssignment = useEndAssignment({ companyId, actor })
  const updateEmployee = useUpdateEmployee({ companyId, actor })

  async function handleConfirm() {
    setSaving(true)
    setFailed(false)
    const endOn = todayIso()
    try {
      const openAssignments = (detail.data?.assignments ?? []).filter(
        (a) => a.effectiveTo === OPEN_ENDED,
      )
      for (const assignment of openAssignments) {
        await endAssignment.mutateAsync({
          employeeId: employee.employeeId,
          assignmentId: assignment.id,
          endOn,
        })
      }
      await updateEmployee.mutateAsync({
        employeeId: employee.employeeId,
        body: { status: 'INACTIVE' },
      })
      onClose()
    } catch {
      // Partial state is recoverable: re-open the dialog and confirm again.
      setFailed(true)
    } finally {
      setSaving(false)
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('hr.terminate.title')}</h2>
        <p className="text-sm text-ink-2">{t('hr.terminate.body', { name: employee.fullName })}</p>
        {failed ? <p className="text-sm text-loss">{t('hr.terminate.partialError')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            className="bg-loss text-white hover:opacity-90"
            onClick={handleConfirm}
            disabled={saving || detail.isLoading}
          >
            {saving ? t('hr.form.saving') : t('hr.terminate.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}
