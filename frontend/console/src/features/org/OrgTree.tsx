import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ChevronDown, ChevronRight, Plus, Trash2, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { useOrgUnits, type OrgUnit } from './api'
import {
  AddUnitDialog,
  DeactivateDialog,
  DeletePermanentlyDialog,
  OrgUnitTypeBadge,
  ReactivateDialog,
  RenameDialog,
  VerticalBadge,
} from './parts'

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
  | { kind: 'delete'; unit: OrgUnit }

/** Action buttons that appear on hover over a node row. */
function NodeActions({
  unit,
  onAddChild,
  onRename,
  onDeactivate,
  onReactivate,
  onDelete,
}: {
  unit: OrgUnit
  onAddChild: (parentId: string) => void
  onRename: (unit: OrgUnit) => void
  onDeactivate: (unit: OrgUnit) => void
  onReactivate: (unit: OrgUnit) => void
  onDelete: (unit: OrgUnit) => void
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
      <button
        type="button"
        aria-label={t('org.delete')}
        title={t('org.delete')}
        className="grid size-7 place-items-center rounded-md text-ink-3 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
        onClick={() => onDelete(unit)}
      >
        <Trash2 className="size-3.5" />
      </button>
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
  onDelete,
}: {
  unit: OrgUnit
  treeMap: Map<string | null, OrgUnit[]>
  depth: number
  onAddChild: (parentId: string) => void
  onRename: (unit: OrgUnit) => void
  onDeactivate: (unit: OrgUnit) => void
  onReactivate: (unit: OrgUnit) => void
  onDelete: (unit: OrgUnit) => void
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

        {/* node name — BU/outlet click through to the unit hub (teams have no detail page) */}
        {unit.type === 'TEAM' ? (
          <span
            className={cn(
              'flex-1 text-[14.5px] font-semibold',
              unit.active ? 'text-ink' : 'text-ink-3',
            )}
          >
            {unit.name}
          </span>
        ) : (
          <Link
            to={`/org/${unit.id}`}
            className={cn(
              'min-w-0 flex-1 truncate text-[14.5px] font-semibold transition-colors',
              'hover:text-brand-700 hover:underline',
              unit.active ? 'text-ink' : 'text-ink-3',
            )}
          >
            {unit.name}
          </Link>
        )}

        {/* type + vertical badges (vertical renders only on business units) */}
        <OrgUnitTypeBadge type={unit.type} />
        <VerticalBadge vertical={unit.vertical} />

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
          onDelete={onDelete}
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
              onDelete={onDelete}
            />
          ))}
        </div>
      ) : null}
    </div>
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
  function openDelete(unit: OrgUnit) {
    setDialog({ kind: 'delete', unit })
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
              onDelete={openDelete}
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
      {dialog?.kind === 'delete' ? (
        <DeletePermanentlyDialog
          unit={dialog.unit}
          allUnits={units}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
    </div>
  )
}
