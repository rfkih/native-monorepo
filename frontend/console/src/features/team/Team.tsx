/**
 * Team page — lists company teammates, invites new ones (showing a one-time temp password),
 * changes a member's role, and deactivates members. Owner/manager only.
 *
 * All user-facing strings via react-i18next (CLAUDE.md rule 9 — zero hardcoded strings).
 * All calls authenticated + tenant-scoped via apiFetch, mirroring features/org/api.ts.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Plus, Search, Store, TriangleAlert, UserX } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState } from '@/features/_shared/financeUi'
import { useOutlets } from '@/features/org/api'
import { useSession } from '@/lib/session'
import { useAuth } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import {
  useTeam,
  useInviteMember,
  useUpdateMember,
  useDeactivateMember,
  useUserOutlets,
  useSetUserOutlets,
  type TeamMember,
  type InviteResponse,
} from './api'

/** The roles the invite/change-role UI offers. */
const ROLES = ['owner', 'manager', 'cashier', 'employee'] as const
type Role = (typeof ROLES)[number]

// ── Dialog state union ────────────────────────────────────────────────────────

type DialogState =
  | { kind: 'invite' }
  | { kind: 'changeRole'; member: TeamMember }
  | { kind: 'deactivate'; member: TeamMember }
  | { kind: 'editOutlets'; member: TeamMember }

// ── DialogOverlay (same pattern as OrgTree.tsx) ───────────────────────────────

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

// ── Role badge — tinted fill + legible ink; only the owner carries the brand tint ──

function RoleBadge({ role }: { role: string }) {
  const { t } = useTranslation()
  const tone = role === 'owner' ? ('emerald' as const) : ('neutral' as const)
  const label = ROLES.includes(role as Role)
    ? t(`team.role.${role as Role}`)
    : role
  return <Badge tone={tone}>{label}</Badge>
}

// ── Status — a dot plus text, green only when the account is live ─────────────

function StatusDot({ enabled }: { enabled: boolean }) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-[13px] font-semibold',
        enabled ? 'text-profit-ink' : 'text-ink-3',
      )}
    >
      <span
        className={cn('size-[7px] rounded-full', enabled ? 'bg-profit' : 'bg-ink-300')}
        aria-hidden
      />
      {enabled ? t('team.statusActive') : t('team.statusDisabled')}
    </span>
  )
}

// ── Copy-to-clipboard button ──────────────────────────────────────────────────

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
      aria-label={t('team.copyPassword')}
      title={t('team.copyPassword')}
      onClick={handleCopy}
      className={cn(
        'grid size-8 place-items-center rounded-lg transition-colors',
        'text-ink-3 hover:bg-emerald-tint/60 hover:text-brand-600',
        'focus-visible:outline-2 focus-visible:outline-brand-500',
      )}
    >
      {copied ? <Check className="size-4 text-profit" /> : <Copy className="size-4" />}
    </button>
  )
}

// ── Invite dialog ─────────────────────────────────────────────────────────────

function InviteDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('cashier')
  const [result, setResult] = useState<InviteResponse | null>(null)
  const mutation = useInviteMember({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      { email: email.trim(), role },
      {
        onSuccess: (res) => {
          if (res) setResult(res)
        },
      },
    )
  }

  function apiErrorMessage(): string {
    if (!mutation.isError) return ''
    const err = mutation.error as { problem?: { type?: string }; message?: string } | null
    const type = err?.problem?.type ?? ''
    if (type.includes('email-already-exists')) return t('team.errorEmailExists')
    return t('team.errorGeneric')
  }

  const roleOptions = ROLES.map((r) => ({ value: r, label: t(`team.role.${r}`) }))

  // ── Post-success: show the one-time temp password ─────────────────────────

  if (result) {
    return (
      <DialogOverlay onClose={onClose}>
        <div className="space-y-4">
          <div className="flex items-center gap-2">
            <div className="grid size-9 place-items-center rounded-full bg-tint-profit text-profit">
              <Check className="size-5" />
            </div>
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('team.inviteDialog.successTitle')}
            </h2>
          </div>

          <p className="text-sm text-ink-2">
            {t('team.inviteDialog.successHint', { email: result.email })}
          </p>

          {/* Temp password — copyable */}
          <div>
            <p className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-ink-3">
              {t('team.inviteDialog.tempPasswordLabel')}
            </p>
            <div className="flex items-center gap-2 rounded-xl border border-line bg-paper px-3.5 py-2.5">
              <span className="flex-1 select-all font-mono text-sm text-ink">
                {result.temporaryPassword}
              </span>
              <CopyButton text={result.temporaryPassword} />
            </div>
            <p className="mt-2 rounded-xl border border-amber/30 bg-amber-tint px-3 py-2 text-xs leading-relaxed text-amber">
              {t('team.inviteDialog.tempPasswordNote')}
            </p>
          </div>

          <div className="flex justify-end">
            <Button type="button" onClick={onClose}>
              {t('team.inviteDialog.done')}
            </Button>
          </div>
        </div>
      </DialogOverlay>
    )
  }

  // ── Invite form ───────────────────────────────────────────────────────────

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('team.inviteDialog.title')}
        </h2>

        <Field label={t('team.inviteDialog.emailLabel')} htmlFor="invite-email">
          <TextInput
            id="invite-email"
            type="email"
            autoFocus
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t('team.inviteDialog.emailPlaceholder')}
            required
          />
        </Field>

        <Field label={t('team.inviteDialog.roleLabel')}>
          <Segmented
            options={roleOptions}
            value={role}
            onChange={setRole}
            ariaLabel={t('team.inviteDialog.roleLabel')}
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {apiErrorMessage()}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending || !email.trim()}>
            {mutation.isPending
              ? t('team.inviteDialog.submitting')
              : t('team.inviteDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ── Change-role dialog ────────────────────────────────────────────────────────

function ChangeRoleDialog({
  member,
  companyId,
  actor,
  onClose,
}: {
  member: TeamMember
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const currentRole = (member.roles.find((r) => ROLES.includes(r as Role)) ?? 'cashier') as Role
  const [role, setRole] = useState<Role>(currentRole)
  const mutation = useUpdateMember({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate(
      { id: member.id, body: { role } },
      { onSuccess: () => onClose() },
    )
  }

  const roleOptions = ROLES.map((r) => ({ value: r, label: t(`team.role.${r}`) }))

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('team.changeRoleDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('team.changeRoleDialog.body', { email: member.email })}
        </p>

        <Field label={t('team.changeRoleDialog.roleLabel')}>
          <Segmented
            options={roleOptions}
            value={role}
            onChange={setRole}
            ariaLabel={t('team.changeRoleDialog.roleLabel')}
          />
        </Field>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('team.errorGeneric')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            disabled={mutation.isPending || role === currentRole}
          >
            {mutation.isPending
              ? t('team.changeRoleDialog.submitting')
              : t('team.changeRoleDialog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ── Deactivate dialog ─────────────────────────────────────────────────────────

function DeactivateDialog({
  member,
  companyId,
  actor,
  onClose,
}: {
  member: TeamMember
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useDeactivateMember({ companyId, actor })

  function handleConfirm() {
    mutation.mutate(member.id, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('team.deactivateDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('team.deactivateDialog.body', { email: member.email })}
        </p>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('team.deactivateDialog.errorTitle')}</p>
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
              ? t('team.deactivateDialog.submitting')
              : t('team.deactivateDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ── Edit-outlets dialog ───────────────────────────────────────────────────────

function EditOutletsDialog({
  member,
  companyId,
  actor,
  onClose,
}: {
  member: TeamMember
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()

  // All company outlets (the option list)
  const outletsQuery = useOutlets(companyId, actor)
  const allOutlets = outletsQuery.data ?? []

  // The user's current assignment
  const assignedQuery = useUserOutlets({
    userId: member.id,
    companyId,
    actor,
    enabled: true,
  })
  const assignedOutlets = assignedQuery.data ?? []

  // Local selection state — initialised once the assigned list loads
  const [selected, setSelected] = useState<Set<string> | null>(null)

  // Once we have both lists, seed local state (only once)
  if (selected === null && !assignedQuery.isLoading && !outletsQuery.isLoading) {
    setSelected(new Set(assignedOutlets.map((o) => o.orgUnitId)))
  }

  const mutation = useSetUserOutlets({ companyId, actor })

  function toggleOutlet(id: string) {
    setSelected((prev) => {
      const next = new Set(prev ?? [])
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  function handleSave() {
    mutation.mutate(
      { userId: member.id, orgUnitIds: Array.from(selected ?? []) },
      { onSuccess: () => onClose() },
    )
  }

  function apiErrorMessage(): string {
    if (!mutation.isError) return ''
    const err = mutation.error as { status?: number } | null
    if (err?.status === 400) return t('team.editOutletsDialog.errorInvalid')
    if (err?.status === 404) return t('team.editOutletsDialog.errorNotFound')
    return t('team.errorGeneric')
  }

  const isLoading = outletsQuery.isLoading || assignedQuery.isLoading

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('team.editOutletsDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('team.editOutletsDialog.body', { email: member.email })}
        </p>

        {isLoading ? (
          <div className="flex justify-center py-6">
            <Spinner className="text-emerald" />
          </div>
        ) : allOutlets.length === 0 ? (
          <p className="rounded-xl border border-line bg-paper px-4 py-3 text-sm text-ink-3">
            {t('team.editOutletsDialog.noOutlets')}
          </p>
        ) : (
          <div
            role="group"
            aria-label={t('team.editOutletsDialog.title')}
            className="max-h-[300px] overflow-y-auto rounded-xl border border-line"
          >
            {allOutlets.map((outlet, idx) => {
              const isChecked = selected?.has(outlet.id) ?? false
              const checkboxId = `outlet-assign-${outlet.id}`
              return (
                <label
                  key={outlet.id}
                  htmlFor={checkboxId}
                  className={cn(
                    'flex h-11 cursor-pointer items-center gap-3 px-4 transition-colors hover:bg-hover',
                    'focus-within:outline-2 focus-within:outline-offset-[-2px] focus-within:outline-emerald',
                    idx !== allOutlets.length - 1 && 'border-b border-ink-50',
                  )}
                >
                  <input
                    id={checkboxId}
                    type="checkbox"
                    checked={isChecked}
                    onChange={() => toggleOutlet(outlet.id)}
                    className="size-4 shrink-0 cursor-pointer accent-emerald focus-visible:outline-2 focus-visible:outline-emerald"
                  />
                  <span className="flex min-w-0 flex-1 items-center gap-2 text-sm text-ink">
                    <Store className="size-3.5 shrink-0 text-ink-3" aria-hidden />
                    <span className="truncate">{outlet.name}</span>
                  </span>
                </label>
              )
            })}
          </div>
        )}

        {/* Selection hint: 0 checked = unrestricted */}
        {allOutlets.length > 0 && selected !== null ? (
          <p className="text-xs text-ink-3">
            {selected.size === 0
              ? t('team.editOutletsDialog.hintAllOutlets')
              : t('team.editOutletsDialog.hintSelected', { count: selected.size })}
          </p>
        ) : null}

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {apiErrorMessage()}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            onClick={handleSave}
            disabled={mutation.isPending || isLoading || allOutlets.length === 0}
          >
            {mutation.isPending
              ? t('team.editOutletsDialog.saving')
              : t('team.editOutletsDialog.save')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ── Member row ────────────────────────────────────────────────────────────────

/** The 2d table row: person · role · outlets · status · hover-revealed actions. */
function MemberRow({
  member,
  isSelf,
  onChangeRole,
  onDeactivate,
  onEditOutlets,
}: {
  member: TeamMember
  isSelf: boolean
  onChangeRole: (m: TeamMember) => void
  onDeactivate: (m: TeamMember) => void
  onEditOutlets: (m: TeamMember) => void
}) {
  const { t } = useTranslation()
  const primaryRole = member.roles.find((r) => ROLES.includes(r as Role)) ?? member.roles[0] ?? ''
  const initials = (member.username || member.email)
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join('')

  // Phase 5 (enforcement live) — role-aware copy: owner/manager BYPASS the outlet guard, so
  // 0 assignments genuinely means "All outlets" for them. A cashier is DEFAULT-CLOSED (0
  // assignments = cannot ring once the tenant adopts outlet scoping), so 0 renders as an
  // amber "No outlets assigned" warning instead.
  const outletCount = member.outletCount ?? 0
  const cashierUnassigned = outletCount === 0 && primaryRole === 'cashier'
  const outletSummary =
    outletCount === 0
      ? cashierUnassigned
        ? t('team.noOutletsAssigned')
        : t('team.allOutlets')
      : t('team.outletCount', { count: outletCount })

  return (
    <div
      className={cn(
        'group grid grid-cols-[minmax(0,2fr)_minmax(80px,1fr)_minmax(100px,1fr)_minmax(80px,1fr)_auto] items-center gap-4 border-b border-ink-50 px-6 py-3.5 transition-colors last:border-0 hover:bg-hover',
        !member.enabled && 'opacity-60',
      )}
    >
      {/* Person */}
      <div className="flex min-w-0 items-center gap-3">
        <span
          className={cn(
            'grid size-[38px] shrink-0 place-items-center rounded-full text-[13px] font-bold',
            primaryRole === 'owner' ? 'bg-emerald-tint text-emerald-2' : 'bg-ink-50 text-ink-2',
          )}
          aria-hidden
        >
          {initials || '—'}
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-ink">{member.email}</p>
          {member.username && member.username !== member.email ? (
            <p className="mt-px truncate text-xs text-ink-3">{member.username}</p>
          ) : null}
        </div>
      </div>

      {/* Role */}
      <span>
        <RoleBadge role={primaryRole} />
      </span>

      {/* Outlets */}
      <span
        className={cn(
          'truncate text-[13px]',
          cashierUnassigned ? 'font-semibold text-amber-2' : 'text-ink-2',
        )}
      >
        {outletSummary}
      </span>

      {/* Status */}
      <StatusDot enabled={member.enabled} />

      {/* Row actions — hidden for the current user (self-lockout prevention) */}
      {!isSelf ? (
        <div className="flex shrink-0 items-center justify-end gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
          <button
            type="button"
            aria-label={t('team.editOutlets')}
            title={t('team.editOutlets')}
            className="rounded-lg px-2 py-1 text-xs font-semibold text-ink-3 hover:bg-ink-50 hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
            onClick={() => onEditOutlets(member)}
          >
            {t('team.editOutlets')}
          </button>
          <button
            type="button"
            aria-label={t('team.changeRole')}
            title={t('team.changeRole')}
            className="rounded-lg px-2 py-1 text-xs font-semibold text-ink-3 hover:bg-ink-50 hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
            onClick={() => onChangeRole(member)}
          >
            {t('team.changeRole')}
          </button>
          {member.enabled ? (
            <button
              type="button"
              aria-label={t('team.deactivate')}
              title={t('team.deactivate')}
              className="rounded-lg px-2 py-1 text-xs font-semibold text-loss/80 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
              onClick={() => onDeactivate(member)}
            >
              <UserX className="mr-1 inline size-3" />
              {t('team.deactivate')}
            </button>
          ) : null}
        </div>
      ) : (
        /* Reserve space so rows have consistent height when the action buttons are absent */
        <div className="shrink-0" aria-hidden="true">
          <span className="px-2 py-1 text-xs text-transparent select-none">
            {t('team.changeRole')}
          </span>
        </div>
      )}
    </div>
  )
}

// ── Team page ─────────────────────────────────────────────────────────────────

/**
 * Team page — owner/manager only. Lists company teammates from GET /api/v1/users,
 * all calls tenant-scoped via apiFetch (mirroring features/org/api.ts).
 */
export function Team() {
  const { t } = useTranslation()
  const { company } = useSession()
  const { actor: currentActor } = useAuth()
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState<'all' | Role>('all')

  const query = useTeam({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('team.noCompany')} hint={t('team.noCompanyHint')} />
  }

  const members = query.data ?? []
  const needle = search.trim().toLowerCase()
  const visible = members.filter((m) => {
    if (roleFilter !== 'all' && !m.roles.includes(roleFilter)) return false
    if (!needle) return true
    return (
      m.email.toLowerCase().includes(needle) ||
      (m.username ?? '').toLowerCase().includes(needle)
    )
  })

  function closeDialog() {
    setDialog(null)
  }

  return (
    <div className="flex flex-col gap-5">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
            {t('team.title')}
          </h1>
          <p className="mt-1.5 text-[15px] text-ink-3">{t('team.subtitle')}</p>
        </div>
        <Button type="button" size="md" onClick={() => setDialog({ kind: 'invite' })}>
          <Plus className="size-4" />
          {t('team.inviteMember')}
        </Button>
      </div>

      {/* Search + role filter */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative w-full max-w-[320px]">
          <Search
            className="pointer-events-none absolute left-3.5 top-1/2 z-10 size-[17px] -translate-y-1/2 text-ink-3"
            aria-hidden
          />
          <TextInput
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('team.searchPlaceholder')}
            aria-label={t('team.searchPlaceholder')}
            className="h-11 pl-10 pr-4 text-sm"
          />
        </div>
        <Segmented
          ariaLabel={t('team.title')}
          value={roleFilter}
          onChange={setRoleFilter}
          className="h-11"
          options={[
            { value: 'all', label: t('team.filterAll') },
            ...ROLES.map((r) => ({ value: r, label: t(`team.role.${r}`) })),
          ]}
        />
      </div>

      {/* Member table */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('team.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-emerald" />
        </Card>
      ) : members.length === 0 ? (
        <EmptyState title={t('team.empty')} hint={t('team.emptyHint')} />
      ) : (
        <Card className="overflow-hidden p-0">
          {/* Header row */}
          <div className="grid grid-cols-[minmax(0,2fr)_minmax(80px,1fr)_minmax(100px,1fr)_minmax(80px,1fr)_auto] gap-4 border-b border-line bg-paper px-6 py-3.5">
            <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('team.colPerson')}
            </span>
            <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('team.colRole')}
            </span>
            <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('team.colOutlets')}
            </span>
            <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('team.colStatus')}
            </span>
            <span aria-hidden />
          </div>
          {visible.length === 0 ? (
            <p className="px-6 py-8 text-center text-sm text-ink-3">{t('team.noMatches')}</p>
          ) : (
            visible.map((member) => (
              <MemberRow
                key={member.id}
                member={member}
                isSelf={member.email === currentActor || member.username === currentActor}
                onChangeRole={(m) => setDialog({ kind: 'changeRole', member: m })}
                onDeactivate={(m) => setDialog({ kind: 'deactivate', member: m })}
                onEditOutlets={(m) => setDialog({ kind: 'editOutlets', member: m })}
              />
            ))
          )}
        </Card>
      )}

      {/* Dialogs */}
      {dialog?.kind === 'invite' ? (
        <InviteDialog
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'changeRole' ? (
        <ChangeRoleDialog
          member={dialog.member}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'deactivate' ? (
        <DeactivateDialog
          member={dialog.member}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'editOutlets' ? (
        <EditOutletsDialog
          member={dialog.member}
          companyId={company.companyId}
          actor={company.actor}
          onClose={closeDialog}
        />
      ) : null}
    </div>
  )
}
