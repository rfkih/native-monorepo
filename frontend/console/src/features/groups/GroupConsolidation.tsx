import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Play, Plus, TriangleAlert } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState, PeriodNav } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canFinance } from '@/lib/rolePreset'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { currentPeriod, shiftPeriod, formatPeriod } from '@/lib/period'
import { cn } from '@/lib/cn'
import {
  useConsolidationGroups,
  useGroupMembers,
  useGroupConsolidation,
  useCreateGroup,
  useAddGroupMember,
  useRemoveGroupMember,
  useRunGroupClose,
  type ConsolidationGroup,
  type ConsolidationState,
} from './api'

/** Dialog state union — null = closed. */
type DialogState =
  | { kind: 'defineGroup' }
  | { kind: 'addMember'; groupId: string }
  | { kind: 'removeMember'; groupId: string; memberCompanyId: string }
  | { kind: 'runClose'; groupId: string; period: string; nextSeq: number }

function StateLabel({ state }: { state: ConsolidationState }) {
  const { t } = useTranslation()
  const label = t(`groups.states.${state}` as Parameters<typeof t>[0])
  const tone = state === 'CLOSED' ? 'emerald' : 'amber'
  return <Badge tone={tone}>{state === 'CLOSED' ? <Check className="size-3" /> : null}{label}</Badge>
}

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
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
      onKeyDown={(e) => { if (e.key === 'Escape') onClose() }}
    >
      <Card className="w-full max-w-md p-6">{children}</Card>
    </div>
  )
}

/** Define-group dialog. */
function DefineGroupDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [currency, setCurrency] = useState('IDR')
  const mutation = useCreateGroup({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      { name: name.trim(), reportingCurrency: currency.trim().toUpperCase() },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('groups.defineDialog.title')}
        </h2>
        <Field label={t('groups.defineDialog.nameLabel')} htmlFor="grp-name">
          <TextInput
            id="grp-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('groups.defineDialog.namePlaceholder')}
            required
            autoFocus
          />
        </Field>
        <Field label={t('groups.defineDialog.currencyLabel')} htmlFor="grp-currency">
          <TextInput
            id="grp-currency"
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
            maxLength={3}
            required
          />
        </Field>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t('groups.defineDialog.errorTitle')}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !name.trim()}>
            {mutation.isPending
              ? t('groups.defineDialog.submitting')
              : t('groups.defineDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/** Add-member dialog. */
function AddMemberDialog({
  groupId,
  companyId,
  actor,
  onClose,
}: {
  groupId: string
  companyId: string
  actor: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  const { t } = useTranslation()
  const [memberId, setMemberId] = useState('')
  const mutation = useAddGroupMember({ companyId, actor, groupId })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      { memberCompanyId: memberId.trim() },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('groups.addMemberDialog.title')}
        </h2>
        <Field label={t('groups.addMemberDialog.memberIdLabel')} htmlFor="member-id">
          <TextInput
            id="member-id"
            value={memberId}
            onChange={(e) => setMemberId(e.target.value)}
            placeholder={t('groups.addMemberDialog.memberIdPlaceholder')}
            required
            autoFocus
          />
        </Field>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t('groups.addMemberDialog.errorTitle')}</p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !memberId.trim()}>
            {mutation.isPending
              ? t('groups.addMemberDialog.submitting')
              : t('groups.addMemberDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

/** Remove-member confirmation dialog. */
function RemoveMemberDialog({
  groupId,
  memberCompanyId,
  companyId,
  actor,
  onClose,
}: {
  groupId: string
  memberCompanyId: string
  companyId: string
  actor: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  const { t } = useTranslation()
  const mutation = useRemoveGroupMember({ companyId, actor, groupId })

  function handleConfirm() {
    mutation.mutate(memberCompanyId, { onSuccess: () => { onClose() } })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('groups.removeMemberDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('groups.removeMemberDialog.body', { id: memberCompanyId })}
        </p>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t('groups.removeMemberDialog.errorTitle')}</p>
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
              ? t('groups.removeMemberDialog.submitting')
              : t('groups.removeMemberDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/** Run-group-close confirmation dialog. */
function RunCloseDialog({
  groupId,
  period,
  nextSeq,
  companyId,
  actor,
  onClose,
}: {
  groupId: string
  period: string
  nextSeq: number
  companyId: string
  actor: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const mutation = useRunGroupClose({ companyId, actor, groupId })

  function handleConfirm() {
    mutation.mutate(
      { period, closeRunSeq: nextSeq },
      { onSuccess: () => { onClose() } },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('groups.closeDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('groups.closeDialog.body', { period: formatPeriod(period, locale) })}
        </p>
        {mutation.isError ? (
          <p className="text-sm text-loss">{t('groups.closeDialog.errorTitle')}</p>
        ) : null}
        {mutation.data ? (
          <p className="text-sm text-brand-700">
            {t('groups.closeDialog.result', {
              state: mutation.data.state,
              seq: String(mutation.data.closeRunSeq),
            })}
          </p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={mutation.isPending}>
            {mutation.isPending
              ? t('groups.closeDialog.submitting')
              : t('groups.closeDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/** Group-detail panel shown when a group is selected. */
function GroupDetail({
  group,
  companyId,
  actor,
  onDialog,
}: {
  group: ConsolidationGroup
  companyId: string
  actor: string
  onDialog: (d: DialogState) => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const auth = useAuth()
  const [period, setPeriod] = useState(currentPeriod())

  // The page reaches here via the OPS gate (owner/manager manage group MEMBERSHIP,
  // /consolidation-groups). The consolidated figures + run-close are the detailed books
  // (/api/v1/groups/**, FINANCE = owner/accountant) — a manager is deliberately NOT shown them and
  // we must not even FIRE the read (it would 403). See ADR 0052.
  const canViewConsolidation = canFinance(effectiveRoles(auth.roles, auth.elevatedRoles))

  const membersQuery = useGroupMembers({ companyId, actor, groupId: group.id, enabled: true })
  const consolidationQuery = useGroupConsolidation({
    companyId,
    actor,
    groupId: group.id,
    period,
    enabled: canViewConsolidation,
  })

  const data = consolidationQuery.data ?? null
  const currency = data?.reportingCurrency ?? group.reportingCurrency
  const nextSeq = (data?.closeRunSeq ?? 0) + 1

  return (
    <div className="grid gap-[18px] lg:grid-cols-[1.4fr_1fr]">
      {/* LEFT column */}
      <div className="flex flex-col gap-[18px]">
        {/* Group header card */}
        <Card className="rounded-[20px] p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="font-display text-xl font-bold text-ink">{group.name}</h2>
              <p className="mt-1 text-sm text-ink-3">
                {t('groups.leadCompany')} ·{' '}
                <span className="font-medium text-ink-2">{t('groups.reportingCurrency')}: {group.reportingCurrency}</span>
              </p>
            </div>
            {canViewConsolidation ? (
              <div className="flex shrink-0 flex-col items-end gap-2">
                {data ? (
                  <span className="rounded-full bg-tint-profit px-3 py-1.5 text-xs font-semibold text-profit-ink">
                    {t(`groups.states.${data.state}` as Parameters<typeof t>[0])} #{data.closeRunSeq}
                  </span>
                ) : null}
                <Button
                  className="px-3.5 text-xs"
                  onClick={() => onDialog({ kind: 'runClose', groupId: group.id, period, nextSeq })}
                >
                  <Play className="size-3.5" /> {t('groups.runGroupClose')}
                </Button>
              </div>
            ) : null}
          </div>

          {canViewConsolidation ? (
            <>
              {/* Period nav */}
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
                <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                  {t('groups.consolidation')}
                </span>
                <PeriodNav
                  period={period}
                  locale={locale}
                  onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
                  onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
                  prevLabel={t('groups.prevPeriod')}
                  nextLabel={t('groups.nextPeriod')}
                />
              </div>

              {/* Provisional badges */}
              {data ? (
                <div className="mt-3 flex flex-wrap gap-2">
                  {data.usesIllustrativeRules ? (
                    <Badge tone="amber">
                      <TriangleAlert className="size-3" /> {t('groups.illustrative')}
                    </Badge>
                  ) : null}
                  {data.usesStubFx ? (
                    <Badge tone="amber">
                      <TriangleAlert className="size-3" /> {t('groups.stubFx')}
                    </Badge>
                  ) : null}
                  {data.usesSimplifiedTranslationPolicy ? (
                    <Badge tone="amber">
                      <TriangleAlert className="size-3" /> {t('groups.simplifiedPolicy')}
                    </Badge>
                  ) : null}
                </div>
              ) : null}
            </>
          ) : null}
        </Card>

        {/* Members card */}
        <Card className="rounded-[20px] p-5">
          <div className="flex items-center justify-between gap-3">
            <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
              {t('groups.members')}
            </p>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-brand-700 hover:text-brand-600 focus-visible:outline-2 focus-visible:outline-brand-500 rounded-lg px-2 py-1"
              onClick={() => onDialog({ kind: 'addMember', groupId: group.id })}
            >
              <Plus className="size-3.5" /> {t('groups.addMember')}
            </button>
          </div>

          {membersQuery.isLoading ? (
            <div className="mt-3 flex flex-col gap-3">
              {[0, 1, 2].map((i) => (
                <div key={i} className="flex items-center justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="mt-1.5 h-3 w-20" />
                  </div>
                  <Skeleton className="h-3 w-14 shrink-0" />
                </div>
              ))}
            </div>
          ) : membersQuery.isError ? (
            <p className="mt-3 text-sm text-loss">{t('groups.error')}</p>
          ) : !membersQuery.data || membersQuery.data.length === 0 ? (
            <p className="mt-3 text-sm text-ink-3">{t('groups.noMembers')}</p>
          ) : (
            <div className="mt-3 divide-y divide-line">
              {membersQuery.data.map((m) => (
                <div
                  key={m.memberCompanyId}
                  className="flex items-center justify-between py-2.5"
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <span
                      className={cn(
                        'size-[7px] shrink-0 rounded-full',
                        m.active ? 'bg-profit' : 'bg-ink-300',
                      )}
                      aria-hidden="true"
                    />
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-ink">
                        {m.memberCompanyId}
                      </p>
                      <p className="text-xs text-ink-3">
                        {currency} · {t('groups.effectiveFrom')} {m.effectiveFrom}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <span className="text-xs text-ink-3">
                      {m.active ? t('groups.memberActive') : t('groups.memberInactive')}
                    </span>
                    {m.active ? (
                      <button
                        type="button"
                        className="text-xs text-loss/70 hover:text-loss focus-visible:outline-2 focus-visible:outline-loss rounded px-1"
                        onClick={() =>
                          onDialog({
                            kind: 'removeMember',
                            groupId: group.id,
                            memberCompanyId: m.memberCompanyId,
                          })
                        }
                      >
                        {t('groups.removeMember')}
                      </button>
                    ) : null}
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      {/* RIGHT column */}
      <div className="flex flex-col gap-[18px]">
        {!canViewConsolidation ? (
          <Card className="rounded-[20px] p-10 text-center">
            <h3 className="font-display text-lg font-semibold text-ink">
              {t('groups.financeOnly')}
            </h3>
            <p className="mt-2 text-sm text-ink-3">{t('groups.financeOnlyHint')}</p>
          </Card>
        ) : consolidationQuery.isError ? (
          <Card className="rounded-[20px] p-8 text-center text-sm text-loss">
            {t('groups.consolidationError')}
          </Card>
        ) : consolidationQuery.isLoading ? (
          <>
            <Skeleton className="h-40 rounded-[20px]" />
            <Skeleton className="h-32 rounded-[20px]" />
            <Skeleton className="h-24 rounded-[20px]" />
          </>
        ) : data == null ? (
          <Card className="rounded-[20px] p-10 text-center">
            <h3 className="font-display text-lg font-semibold text-ink">{t('groups.noClose')}</h3>
            <p className="mt-2 text-sm text-ink-3">{t('groups.noCloseHint')}</p>
          </Card>
        ) : (
          <>
            {/* Consolidated figures card */}
            <Card className="rounded-[20px] p-5">
              <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('groups.consolidation')} · {currency}
              </p>
              <div className="mt-4 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-ink-2">{t('groups.revenue')}</span>
                  <span className="tnum font-mono text-sm font-semibold text-ink">
                    {formatMoney(data.groupRevenueMinor, currency, locale)}
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-sm text-ink-2">{t('groups.expense')}</span>
                  <span className="tnum font-mono text-sm font-semibold text-ink">
                    {formatMoney(data.groupExpenseMinor, currency, locale)}
                  </span>
                </div>
              </div>
              {/* Net box */}
              <div className="mt-4 rounded-[12px] bg-brand-50 px-4 py-3.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-brand-800">{t('groups.net')}</span>
                  <span
                    className={cn(
                      'tnum font-mono text-lg font-extrabold',
                      data.groupNetMinor >= 0 ? 'text-brand-700' : 'text-loss',
                    )}
                  >
                    {formatMoney(data.groupNetMinor, currency, locale)}
                  </span>
                </div>
              </div>
            </Card>

            {/* Eliminations card */}
            <Card className="rounded-[20px] p-5">
              <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                {t('groups.eliminations')}
              </p>
              <div className="mt-4 grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-ink-3">{t('groups.totalReferences')}</p>
                  <p className="tnum mt-1 font-mono text-2xl font-bold text-ink">
                    {data.eliminations.totalReferences}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-ink-3">{t('groups.unreconciledReferences')}</p>
                  <p
                    className={cn(
                      'tnum mt-1 font-mono text-2xl font-bold',
                      data.eliminations.unreconciledReferences === 0
                        ? 'text-brand-700'
                        : 'text-ink',
                    )}
                  >
                    {data.eliminations.unreconciledReferences}
                  </p>
                </div>
              </div>
              <div className="mt-4">
                <span
                  className={cn(
                    'inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold',
                    data.eliminations.allReconciled
                      ? 'bg-tint-profit text-profit-ink'
                      : 'bg-tint-warning text-warning',
                  )}
                >
                  {data.eliminations.allReconciled ? (
                    <Check className="size-3" />
                  ) : (
                    <TriangleAlert className="size-3" />
                  )}
                  {data.eliminations.allReconciled
                    ? t('groups.allReconciled')
                    : t('groups.notAllReconciled')}
                </span>
              </div>
            </Card>

            {/* Close state card */}
            <Card className="rounded-[20px] p-5">
              <div className="flex items-center justify-between gap-2">
                <div>
                  <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                    {t('groups.state')}
                  </p>
                  <div className="mt-2">
                    <StateLabel state={data.state} />
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-3">
                    {t('groups.closeRunSeq')}
                  </p>
                  <p className="tnum mt-2 font-mono text-sm text-ink">#{data.closeRunSeq}</p>
                </div>
              </div>
            </Card>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Group Consolidation page — lists groups led by this company, shows members + consolidated KPI
 * tiles for a selected group and period. Supports define-group, add/remove-member, and run-group-
 * close actions. Badges usesIllustrativeRules / usesStubFx / usesSimplifiedTranslationPolicy per
 * FRONTEND-STRUCTURE rule 7. Money via formatMoney (minor units, rule 8).
 *
 * <p><strong>Split by capability (ADR 0052):</strong> the page + group MEMBERSHIP management
 * ({@code /consolidation-groups}) is OPS (owner/manager — that gates the route + nav); the
 * consolidated FIGURES + run-close ({@code /api/v1/groups/**}) are the detailed books, FINANCE
 * (owner/accountant). A manager manages who is in a group but is not shown the consolidated numbers
 * (and the read is not even fired — see {@code canViewConsolidation}).
 */
export function GroupConsolidation() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null)
  const [dialog, setDialog] = useState<DialogState | null>(null)

  const groupsQuery = useConsolidationGroups({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('groups.noCompany')} hint={t('groups.noCompanyHint')} />
  }

  const groups = groupsQuery.data ?? []
  const selectedGroup = groups.find((g) => g.id === selectedGroupId) ?? null

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('groups.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('groups.subtitle')}</p>
        </div>
        <Button
          type="button"
          onClick={() => setDialog({ kind: 'defineGroup' })}
        >
          <Plus className="size-4" />
          {t('groups.defineGroup')}
        </Button>
      </div>

      {groupsQuery.isError ? (
        <Card className="rounded-[20px] p-8 text-center text-sm text-loss">
          {t('groups.error')}
        </Card>
      ) : groupsQuery.isLoading ? (
        <div className="grid gap-[18px] md:grid-cols-[260px_1fr]">
          <ListSkeleton rows={4} className="rounded-[20px]" />
          <ListSkeleton rows={3} className="rounded-[20px]" />
        </div>
      ) : groups.length === 0 ? (
        <EmptyState title={t('groups.empty')} hint={t('groups.emptyHint')} />
      ) : (
        <div className="grid gap-[18px] md:grid-cols-[260px_1fr]">
          {/* Group list (left pane) */}
          <Card className="h-fit rounded-[20px] p-2.5">
            {groups.map((g) => (
              <button
                key={g.id}
                type="button"
                className={cn(
                  'flex w-full flex-col gap-0.5 rounded-xl px-3 py-2.5 text-left transition-colors',
                  g.id === selectedGroupId
                    ? 'bg-brand-50 text-brand-700'
                    : 'text-ink hover:bg-hover',
                )}
                onClick={() => setSelectedGroupId(g.id === selectedGroupId ? null : g.id)}
              >
                <span className="text-sm font-semibold">{g.name}</span>
                <span className="text-xs text-ink-3">{g.reportingCurrency}</span>
              </button>
            ))}
            <div className="mt-2 border-t border-line pt-2">
              <button
                type="button"
                className="flex w-full items-center gap-1.5 rounded-xl px-3 py-2 text-left text-xs font-semibold text-brand-700 hover:bg-hover transition-colors focus-visible:outline-2 focus-visible:outline-brand-500"
                onClick={() => setDialog({ kind: 'defineGroup' })}
              >
                <Plus className="size-3.5" /> {t('groups.defineGroup')}
              </button>
            </div>
          </Card>

          {/* Detail pane */}
          <div>
            {selectedGroup ? (
              <GroupDetail
                group={selectedGroup}
                companyId={company.companyId}
                actor={company.actor}
                onDialog={setDialog}
              />
            ) : (
              <Card className="rounded-[20px] p-10 text-center">
                <p className="text-sm text-ink-3">{t('groups.selectPrompt')}</p>
              </Card>
            )}
          </div>
        </div>
      )}

      {/* Dialogs */}
      {dialog?.kind === 'defineGroup' ? (
        <DefineGroupDialog
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'addMember' ? (
        <AddMemberDialog
          groupId={dialog.groupId}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'removeMember' ? (
        <RemoveMemberDialog
          groupId={dialog.groupId}
          memberCompanyId={dialog.memberCompanyId}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'runClose' ? (
        <RunCloseDialog
          groupId={dialog.groupId}
          period={dialog.period}
          nextSeq={dialog.nextSeq}
          companyId={company.companyId}
          actor={company.actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}
