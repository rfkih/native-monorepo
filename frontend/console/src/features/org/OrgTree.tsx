import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import {
  useOrgUnits,
  useCreateOrgUnit,
  usePatchOrgUnit,
  type OrgUnit,
  type OrgUnitType,
} from './api'

/** Build a parent→children map from the flat list returned by the backend. */
function buildTree(units: OrgUnit[]): Map<string | null, OrgUnit[]> {
  const map = new Map<string | null, OrgUnit[]>()
  for (const u of units) {
    const key = u.parentId ?? null
    const existing = map.get(key) ?? []
    existing.push(u)
    map.set(key, existing)
  }
  return map
}

/** Dialog state union — null = no dialog open. */
type DialogState =
  | { kind: 'add'; parentId: string | null }
  | { kind: 'rename'; unit: OrgUnit }
  | { kind: 'deactivate'; unit: OrgUnit }
  | { kind: 'reactivate'; unit: OrgUnit }

/** Type badge pill — color-coded by OrgUnitType. */
function OrgUnitTypeBadge({ type }: { type: OrgUnitType }) {
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

/** Action buttons that appear on hover over a node row. */
function NodeActions({
  unit,
  onAddChild,
  onRename,
  onDeactivate,
  onReactivate,
}: {
  unit: OrgUnit
  onAddChild: (parentId: string) => void
  onRename: (unit: OrgUnit) => void
  onDeactivate: (unit: OrgUnit) => void
  onReactivate: (unit: OrgUnit) => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-1 opacity-0 transition-opacity focus-within:opacity-100 [div:hover>&]:opacity-100">
      {unit.active ? (
        <button
          type="button"
          aria-label={t('org.addChild')}
          title={t('org.addChild')}
          className="grid size-7 place-items-center rounded-md text-ink-3 hover:bg-emerald-tint/60 hover:text-brand-600 focus-visible:outline-2 focus-visible:outline-brand-500"
          onClick={() => onAddChild(unit.id)}
        >
          <Plus className="size-3.5" />
        </button>
      ) : null}
      <button
        type="button"
        aria-label={t('org.rename')}
        title={t('org.rename')}
        className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
        onClick={() => onRename(unit)}
      >
        {t('org.rename')}
      </button>
      {unit.active ? (
        <button
          type="button"
          aria-label={t('org.deactivate')}
          title={t('org.deactivate')}
          className="rounded-md px-2 py-1 text-xs text-loss/80 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
          onClick={() => onDeactivate(unit)}
        >
          {t('org.deactivate')}
        </button>
      ) : (
        <button
          type="button"
          aria-label={t('org.reactivate')}
          title={t('org.reactivate')}
          className="rounded-md px-2 py-1 text-xs text-brand-600/80 hover:bg-emerald-tint hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-brand-500"
          onClick={() => onReactivate(unit)}
        >
          {t('org.reactivate')}
        </button>
      )}
    </div>
  )
}

/** A single org-tree node with expand/collapse and its recursive subtree. */
function OrgNode({
  unit,
  treeMap,
  depth,
  onAddChild,
  onRename,
  onDeactivate,
  onReactivate,
}: {
  unit: OrgUnit
  treeMap: Map<string | null, OrgUnit[]>
  depth: number
  onAddChild: (parentId: string) => void
  onRename: (unit: OrgUnit) => void
  onDeactivate: (unit: OrgUnit) => void
  onReactivate: (unit: OrgUnit) => void
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(true)
  const children = treeMap.get(unit.id) ?? []
  const hasChildren = children.length > 0

  return (
    <div>
      <div
        className="group flex items-center gap-2.5 rounded-xl px-2.5 py-2.5 hover:bg-hover transition-colors"
      >
        {/* indent spacer */}
        <div style={{ width: depth * 26 }} aria-hidden="true" />

        {/* chevron toggle */}
        <button
          type="button"
          aria-label={expanded ? t('org.collapse') : t('org.expand')}
          className={cn(
            'grid size-[22px] shrink-0 place-items-center rounded-md text-ink-3 transition-colors',
            hasChildren ? 'hover:bg-ink-100 hover:text-ink' : 'pointer-events-none',
          )}
          onClick={() => setExpanded((e) => !e)}
          disabled={!hasChildren}
          tabIndex={hasChildren ? 0 : -1}
        >
          {hasChildren ? (
            expanded ? (
              <ChevronDown className="size-3.5" />
            ) : (
              <ChevronRight className="size-3.5" />
            )
          ) : (
            <span className="size-3.5" />
          )}
        </button>

        {/* node name */}
        <span
          className={cn(
            'flex-1 text-[14.5px] font-semibold',
            unit.active ? 'text-ink' : 'text-ink-3',
          )}
        >
          {unit.name}
        </span>

        {/* type badge */}
        <OrgUnitTypeBadge type={unit.type} />

        {/* active/inactive indicator */}
        <span className="flex items-center gap-1.5 shrink-0">
          <span
            className={cn(
              'size-1.5 rounded-full',
              unit.active ? 'bg-profit' : 'bg-ink-300',
            )}
            aria-hidden="true"
          />
          <span className="text-[11px] text-ink-3 hidden sm:block">
            {unit.active ? t('org.active') : t('org.inactive')}
          </span>
        </span>

        <NodeActions
          unit={unit}
          onAddChild={onAddChild}
          onRename={onRename}
          onDeactivate={onDeactivate}
          onReactivate={onReactivate}
        />
      </div>

      {expanded && children.length > 0 ? (
        <div>
          {children.map((child) => (
            <OrgNode
              key={child.id}
              unit={child}
              treeMap={treeMap}
              depth={depth + 1}
              onAddChild={onAddChild}
              onRename={onRename}
              onDeactivate={onDeactivate}
              onReactivate={onReactivate}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

/** Simple modal overlay — closes on backdrop click or Escape. */
function DialogOverlay({
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
function AddUnitDialog({
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

  const parentName = parent?.name ?? (parentId ?? t('org.addDialog.noParent'))

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate({ name: name.trim(), type, parentId }, { onSuccess: () => { onClose() } })
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
function RenameDialog({
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
function DeactivateDialog({
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
function ReactivateDialog({
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
 * Org Tree page — renders the company's org hierarchy, assembled client-side from the flat list
 * returned by GET /api/v1/org-units. Owner/manager only. Includes add-child, rename, deactivate,
 * and reactivate actions as modest inline dialogs. All user-facing strings via i18n (rule 9). No
 * hardcoded strings. The tree map is built once per query result and passed through props —
 * no module-level mutation.
 */
export function OrgTree() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [dialog, setDialog] = useState<DialogState | null>(null)

  const query = useOrgUnits({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('org.noCompany')} hint={t('org.noCompanyHint')} />
  }

  const units = query.data ?? []
  const treeMap = buildTree(units)
  const roots = treeMap.get(null) ?? []

  function openAddChild(parentId: string) {
    setDialog({ kind: 'add', parentId })
  }
  function openRename(unit: OrgUnit) {
    setDialog({ kind: 'rename', unit })
  }
  function openDeactivate(unit: OrgUnit) {
    setDialog({ kind: 'deactivate', unit })
  }
  function openReactivate(unit: OrgUnit) {
    setDialog({ kind: 'reactivate', unit })
  }
  function closeDialog() {
    setDialog(null)
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('org.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('org.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialog({ kind: 'add', parentId: null })}>
          <Plus className="size-4" />
          {t('org.addUnit')}
        </Button>
      </div>

      {/* Tree body */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('org.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : roots.length === 0 ? (
        <EmptyState title={t('org.empty')} hint={t('org.emptyHint')} />
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {roots.map((root) => (
            <OrgNode
              key={root.id}
              unit={root}
              treeMap={treeMap}
              depth={0}
              onAddChild={openAddChild}
              onRename={openRename}
              onDeactivate={openDeactivate}
              onReactivate={openReactivate}
            />
          ))}
        </Card>
      )}

      {/* Dialogs */}
      {dialog?.kind === 'add' ? (
        <AddUnitDialog
          parentId={dialog.parentId}
          allUnits={units}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'rename' ? (
        <RenameDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'deactivate' ? (
        <DeactivateDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'reactivate' ? (
        <ReactivateDialog
          unit={dialog.unit}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
    </div>
  )
}
