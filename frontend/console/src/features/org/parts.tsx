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
import { useScrollLock } from '@/components/mobile/useScrollLock'
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

/**
 * Type badge pill. Since ADR 0070 there is exactly one kind (OUTLET), so the badge is a plain
 * label rather than a colour-coded discriminator — kept because it still reads as a row's kind.
 */
export function OrgUnitTypeBadge({ type }: { type: OrgUnitType }) {
  const { t } = useTranslation()
  const classes = 'bg-ink-50 text-ink-500'
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
 * company's). Restaurant gets the brand tone; other verticals the info tint.
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
  useScrollLock()
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

/** Add-outlet dialog. ADR 0070: every outlet is top-level, so the name is the whole form. */
export function AddUnitDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const mutation = useCreateOrgUnit({ companyId, actor })

  // ADR 0070: the tree is flat, so an outlet has no parent to pick, no type to choose (there is
  // only one) and no vertical of its own (it is a company attribute). The name is the whole form.
  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate({ name: name.trim() }, { onSuccess: () => { onClose() } })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('org.addDialog.title')}</h2>

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


        {mutation.isError ? (
          <p className="text-sm text-loss">{t('org.addDialog.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim()}>
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
 * Ids of `rootId` and all its transitive descendants, from the flat org-unit list — the same scope
 * the server deletes. Since ADR 0070 nothing nests (`parentId` is always null), so this is always
 * just `[rootId]`; the walk is kept because it is the honest expression of "the delete scope" and
 * costs nothing on a list this size.
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
 * Permanent-delete confirmation dialog. Hard-deletes an EMPTY outlet (ADR 0070: nothing nests, so
 * there is no subtree to take with it). It first checks the outlet is empty and, if not, refuses
 * and points the user to deactivate instead (which preserves history):
 *
 * <ul>
 *   <li><em>assigned logins</em> — read from {@code useUnitUsers}. The backend independently
 *       enforces this guard with a 409, so a race after the check is caught and surfaced.
 *   <li><em>employees</em> — read from {@code useEmployees} scoped to the outlet. Employees live in
 *       another service, so this guard is console-only (no sync cross-service call, rule 2);
 *       deleting would orphan their HR assignment rows.
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

  // The delete scope, matching the server's: since ADR 0070 that is the outlet alone.
  const scopeIds = collectSubtreeIds(unit.id, allUnits)

  const employeesQuery = useEmployees({ companyId, actor, orgUnitIds: scopeIds, enabled: true })
  // ADR 0070 removed the TEAM level, so every unit is a valid target for the login list.
  const usersEnabled = true
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
              {t('org.deleteDialog.body', { name: unit.name })}
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
