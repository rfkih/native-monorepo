/**
 * Shared org-feature building blocks — the type badge and the add/rename/deactivate/reactivate
 * dialogs, lifted verbatim out of OrgTree.tsx so the org-unit hub detail page can reuse them
 * without importing the tree page module (chunk hygiene). Mirrors the statements/parts.tsx
 * precedent.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { cn } from '@/lib/cn'
import {
  useCreateOrgUnit,
  usePatchOrgUnit,
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

/** Simple modal overlay — closes on backdrop click or Escape. */
export function DialogOverlay({
  children,
  onClose,
}: {
  children: React.ReactNode
  onClose: () => void
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-md p-6">{children}</Card>
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
