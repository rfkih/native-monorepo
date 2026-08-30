/**
 * Shared org-feature building blocks — the type badge and the add/rename/deactivate/reactivate
 * dialogs, lifted verbatim out of OrgTree.tsx so the org-unit hub detail page can reuse them
 * without importing the tree page module (chunk hygiene). Mirrors the statements/parts.tsx
 * precedent.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { isOrgUnitHasData } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useEmployees } from '@/features/hr/api'
import {
  useCreateOrgUnit,
  useDeleteOrgUnit,
  usePatchOrgUnit,
  useUnitUsers,
  type OrgUnit,
  type OrgUnitType,
} from './api'

/** Type badge pill — color-coded by OrgUnitType. */
export function OrgUnitTypeBadge({ type }: { type: OrgUnitType }) {
  const { t } = useTranslation()
  const classes =
    type === 'BUSINESS_UNIT'
      ? 'bg-tint-info text-info'
      : 'bg-ink-50 text-ink-500'
  return (
    <span
      className={cn(
        'rounded-full px-2.5 py-0.5 text-[11px] font-semibold',
        classes,
      )}
    >
      {t(`org.type.${type}` as Parameters<typeof t>[0])}
    </span>
  )
}

/**
 * Vertical badge pill — renders nothing for a null vertical (outlet/team nodes inherit their
 * business unit's). Restaurant gets the brand tone; other verticals the info tint.
 */
export function VerticalBadge({ vertical }: { vertical: string | null }) {
  const { t } = useTranslation()
  if (!vertical) return null
  const classes =
    vertical === 'restaurant'
      ? 'bg-emerald-tint text-emerald-2'
      : 'bg-tint-info text-info'
  return (
    <span
      className={cn(
        'rounded-full px-2.5 py-0.5 text-[11px] font-semibold',
        classes,
      )}
    >
      {t(`vertical.${vertical}` as Parameters<typeof t>[0], { defaultValue: vertical })}
    </span>
  )
}

/** Simple modal overlay — closes on backdrop click, Escape, or the phone/browser Back button.
 *  Bottom-sheet feel on phone (Native Console Android): bottom-anchored, full-width, rounded top
 *  corners. */
export function DialogOverlay({
  children,
  onClose,
}: {
  children: React.ReactNode
  onClose: () => void
}) {
  useBackDismiss(onClose)
  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm sm:items-center"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-md p-6 max-sm:sheet-up max-sm:max-h-[92dvh] max-sm:max-w-full max-sm:overflow-y-auto max-sm:rounded-b-none max-sm:rounded-t-[26px]">
        {children}
      </Card>
    </div>
  )
}

/** Add-unit dialog (creates a child or top-level node). */
export function AddUnitDialog({
  parentId,
  allUnits,
  companyId,
  actor,
  onClose,
}: {
  parentId: string | null
  allUnits: OrgUnit[]
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const mutation = useCreateOrgUnit({ companyId, actor })

  const parent = parentId ? (allUnits.find((u) => u.id === parentId) ?? null) : null
  // Mirror of the backend hierarchy rule (OrgUnitType.allowedParentTypes, ADR 0012): an
  // outlet only under a business unit; a team only under an outlet.
  const types: OrgUnitType[] = !parent
    ? ['BUSINESS_UNIT']
    : parent.type === 'BUSINESS_UNIT'
      ? ['OUTLET']
      : parent.type === 'OUTLET'
        ? ['TEAM']
        : []
  const [type, setType] = useState<OrgUnitType>(types[0] ?? 'OUTLET')
  // Only a BUSINESS_UNIT carries a vertical (server-required there, rejected elsewhere).
  const [vertical, setVertical] = useState<string>('restaurant')

  const parentName = parent?.name ?? (parentId ?? t('org.addDialog.noParent'))

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      {
        name: name.trim(),
        type,
        parentId,
        ...(type === 'BUSINESS_UNIT' ? { vertical } : {}),
      },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('org.addDialog.title')}</h2>

        <Field label={t('org.addDialog.parentLabel')}>
          <p className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm text-ink">
            {parentName}
          </p>
        </Field>

        <Field label={t('org.addDialog.nameLabel')} htmlFor="add-name">
          <TextInput
            id="add-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('org.addDialog.namePlaceholder')}
            required
            autoFocus
          />
        </Field>

        <Field label={t('org.addDialog.typeLabel')} htmlFor="add-type">
          {types.length === 0 ? (
            <p className="rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5 text-xs leading-relaxed text-amber">
              {t('org.addDialog.noChildAllowed')}
            </p>
          ) : (
            <select
              id="add-type"
              value={type}
              onChange={(e) => setType(e.target.value as OrgUnitType)}
              className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
            >
              {types.map((tp) => (
                <option key={tp} value={tp}>
                  {t(`org.type.${tp}` as Parameters<typeof t>[0])}
                </option>
              ))}
            </select>
          )}
        </Field>

        {type === 'BUSINESS_UNIT' ? (
          <Field label={t('org.addDialog.verticalLabel')} htmlFor="add-vertical">
            <select
              id="add-vertical"
              value={vertical}
              onChange={(e) => setVertical(e.target.value)}
              className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
            >
              {(['restaurant', 'carwash', 'barbershop'] as const).map((v) => (
                <option key={v} value={v}>
                  {t(`vertical.${v}` as Parameters<typeof t>[0])}
                </option>
              ))}
            </select>
          </Field>
        ) : null}

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('org.addDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim() || types.length === 0}>
            {mutation.isPending ? t('org.addDialog.submitting') : t('org.addDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/** Rename dialog. */
export function RenameDialog({
  unit,
  companyId,
  actor,
  onClose,
}: {
  unit: OrgUnit
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(unit.name)
  const mutation = usePatchOrgUnit({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      { id: unit.id, body: { name: name.trim() } },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('org.renameDialog.title')}
        </h2>

        <Field label={t('org.renameDialog.nameLabel')} htmlFor="rename-name">
          <TextInput
            id="rename-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
          />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('org.renameDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim()}>
            {mutation.isPending ? t('org.renameDialog.submitting') : t('org.renameDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/** Deactivate confirmation dialog (cascades to active subtree). */
export function DeactivateDialog({
  unit,
  companyId,
  actor,
  onClose,
}: {
  unit: OrgUnit
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = usePatchOrgUnit({ companyId, actor })

  function handleConfirm() {
    mutation.mutate(
      { id: unit.id, body: { deactivate: true } },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('org.deactivateDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">{t('org.deactivateDialog.body', { name: unit.name })}</p>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('org.deactivateDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            className="bg-loss text-white hover:opacity-90"
            onClick={handleConfirm}
            disabled={mutation.isPending}
          >
            {mutation.isPending
              ? t('org.deactivateDialog.submitting')
              : t('org.deactivateDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/** Reactivate confirmation dialog (parent must already be active). */
export function ReactivateDialog({
  unit,
  companyId,
  actor,
  onClose,
}: {
  unit: OrgUnit
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = usePatchOrgUnit({ companyId, actor })

  function handleConfirm() {
    mutation.mutate(
      { id: unit.id, body: { reactivate: true } },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('org.reactivateDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">{t('org.reactivateDialog.body', { name: unit.name })}</p>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('org.reactivateDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={mutation.isPending}>
            {mutation.isPending
              ? t('org.reactivateDialog.submitting')
              : t('org.reactivateDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/**
 * Ids of `rootId` and all its transitive descendants, from the flat org-unit list — the same
 * subtree the server cascade-deletes. A breadth-first walk over `parentId`; the tree is at most
 * three levels (business_unit > outlet > team, ADR 0012), so it terminates quickly.
 */
function collectSubtreeIds(rootId: string, allUnits: OrgUnit[]): string[] {
  const ids = [rootId]
  const queue = [rootId]
  while (queue.length > 0) {
    const current = queue.shift()!
    for (const u of allUnits) {
      if (u.parentId === current && !ids.includes(u.id)) {
        ids.push(u.id)
        queue.push(u.id)
      }
    }
  }
  return ids
}

/**
 * Permanent-delete confirmation dialog. Hard-deletes an EMPTY unit and its subtree (a business
 * unit with its outlets, or an outlet with its teams — every BU seeds one outlet, ADR 0012). It first checks the unit is empty and, if
 * not, refuses and points the user to deactivate instead (which preserves history):
 *
 * <ul>
 *   <li><em>assigned logins</em> — read from {@code useUnitUsers} (for a BU this already spans its
 *       child outlets). The backend independently enforces this guard with a 409, so a race after
 *       the check is caught and surfaced.
 *   <li><em>employees</em> — read from {@code useEmployees} scoped to the unit and (for a BU) its
 *       child outlets. Employees live in another service, so this guard is console-only (no sync
 *       cross-service call, rule 2); deleting would orphan their HR assignment rows.
 * </ul>
 *
 * This is the "remove a mistake" path — a unit with real data is deactivated, never deleted.
 */
export function DeletePermanentlyDialog({
  unit,
  allUnits,
  companyId,
  actor,
  onClose,
}: {
  unit: OrgUnit
  allUnits: OrgUnit[]
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useDeleteOrgUnit({ companyId, actor })

  // Deleting a unit cascades its entire subtree on the server, so the emptiness check spans the
  // unit AND every descendant (a BU always has ≥1 child outlet — ADR 0012 — so "has children" is
  // never a block on its own; only descendants that carry data are). Collect the subtree ids from
  // the already-loaded flat tree, matching the server's cascade scope.
  const scopeIds = collectSubtreeIds(unit.id, allUnits)

  const employeesQuery = useEmployees({ companyId, actor, orgUnitIds: scopeIds, enabled: true })
  // The login list endpoint rejects a TEAM target (400) — teams never carry logins — so only query
  // it for a BU/outlet; a team's login count is definitionally zero.
  const usersEnabled = unit.type !== 'TEAM'
  const usersQuery = useUnitUsers({ companyId, actor, unitId: unit.id, enabled: usersEnabled })

  const checking =
    employeesQuery.isLoading ||
    employeesQuery.isPending ||
    (usersEnabled && (usersQuery.isLoading || usersQuery.isPending))
  // Fail CLOSED: the backend does not enforce the employee guard (employees live in another
  // service, rule 2), so if either check errors we cannot prove the unit is empty — block the
  // delete rather than risk orphaning employee/assignment rows.
  const cannotVerify = employeesQuery.isError || (usersEnabled && usersQuery.isError)
  const employeeCount = employeesQuery.data?.length ?? 0
  const loginCount = usersQuery.data?.length ?? 0
  const hasData = employeeCount > 0 || loginCount > 0

  // A race lost to a concurrent (or historical) login assignment surfaces as the backend's 409
  // org-unit-has-data — matched by its stable type URI, not a bare status code.
  const conflict = isOrgUnitHasData(mutation.error)

  function handleConfirm() {
    mutation.mutate(unit.id, { onSuccess: () => { onClose() } })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('org.deleteDialog.title')}
        </h2>

        {checking ? (
          <div className="flex items-center gap-3 py-2 text-sm text-ink-3">
            <Spinner className="text-brand-500" />
            {t('org.deleteDialog.checking')}
          </div>
        ) : cannotVerify ? (
          <>
            <p className="text-sm text-ink-2">{t('org.deleteDialog.verifyError')}</p>
            <div className="flex justify-end">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('org.deleteDialog.close')}
              </Button>
            </div>
          </>
        ) : hasData || conflict ? (
          <>
            <p className="text-sm text-ink-2">
              {t('org.deleteDialog.blockedIntro', { name: unit.name })}
            </p>
            <ul className="space-y-1.5 rounded-xl bg-tint-loss/50 px-4 py-3 text-sm text-ink-2">
              {conflict ? <li>{t('org.deleteDialog.conflict')}</li> : null}
              {loginCount > 0 ? (
                <li>{t('org.deleteDialog.blockedLogins', { count: loginCount })}</li>
              ) : null}
              {employeeCount > 0 ? (
                <li>{t('org.deleteDialog.blockedEmployees', { count: employeeCount })}</li>
              ) : null}
            </ul>
            <p className="text-xs text-ink-3">{t('org.deleteDialog.blockedHint')}</p>
            <div className="flex justify-end">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('org.deleteDialog.close')}
              </Button>
            </div>
          </>
        ) : (
          <>
            <p className="text-sm text-ink-2">
              {t(
                unit.type === 'BUSINESS_UNIT'
                  ? 'org.deleteDialog.bodyBu'
                  : 'org.deleteDialog.body',
                { name: unit.name },
              )}
            </p>

            {mutation.isError ? (
              <p className="text-sm text-loss">{t('org.deleteDialog.errorTitle')}</p>
            ) : null}

            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              <Button
                type="button"
                className="bg-loss text-white hover:opacity-90"
                onClick={handleConfirm}
                disabled={mutation.isPending}
              >
                {mutation.isPending
                  ? t('org.deleteDialog.submitting')
                  : t('org.deleteDialog.confirm')}
              </Button>
            </div>
          </>
        )}
      </div>
    </DialogOverlay>
  )
}
