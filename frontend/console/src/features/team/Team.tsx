/**
 * Team page — lists company teammates, invites new ones (showing a one-time temp password),
 * changes a member's role, and deactivates members. Owner/manager only.
 *
 * All user-facing strings via react-i18next (CLAUDE.md rule 9 — zero hardcoded strings).
 * All calls authenticated + tenant-scoped via apiFetch, mirroring features/org/api.ts.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Plus, TriangleAlert, UserX } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { useAuth } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import {
  useTeam,
  useInviteMember,
  useUpdateMember,
  useDeactivateMember,
  type TeamMember,
  type InviteResponse,
} from './api'

/** The roles the invite/change-role UI offers. */
const ROLES = ['owner', 'manager', 'cashier'] as const
type Role = (typeof ROLES)[number]

// ── Dialog state union ────────────────────────────────────────────────────────

type DialogState =
  | { kind: 'invite' }
  | { kind: 'changeRole'; member: TeamMember }
  | { kind: 'deactivate'; member: TeamMember }

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

// ── Role badge ────────────────────────────────────────────────────────────────

function RoleBadge({ role }: { role: string }) {
  const { t } = useTranslation()
  const tone =
    role === 'owner'
      ? ('info' as const)
      : role === 'manager'
        ? ('amber' as const)
        : ('neutral' as const)
  const label = ROLES.includes(role as Role)
    ? t(`team.role.${role as Role}`)
    : role
  return <Badge tone={tone}>{label}</Badge>
}

// ── Status badge ─────────────────────────────────────────────────────────────

function StatusBadge({ enabled }: { enabled: boolean }) {
  const { t } = useTranslation()
  return (
    <Badge tone={enabled ? 'emerald' : 'neutral'}>
      {enabled ? t('team.statusActive') : t('team.statusDisabled')}
    </Badge>
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
        'text-ink-3 hover:bg-tint-profit/60 hover:text-brand-600',
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

// ── Member row ────────────────────────────────────────────────────────────────

function MemberRow({
  member,
  isSelf,
  onChangeRole,
  onDeactivate,
}: {
  member: TeamMember
  isSelf: boolean
  onChangeRole: (m: TeamMember) => void
  onDeactivate: (m: TeamMember) => void
}) {
  const { t } = useTranslation()
  const primaryRole = member.roles.find((r) => ROLES.includes(r as Role)) ?? member.roles[0] ?? ''

  return (
    <div
      className={cn(
        'group flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl px-3 py-3 transition-colors hover:bg-hover',
        !member.enabled && 'opacity-50',
      )}
    >
      {/* Identity */}
      <div className="min-w-0 flex-1">
        <p className="truncate text-[14px] font-semibold text-ink">{member.email}</p>
        {member.username && member.username !== member.email ? (
          <p className="mt-0.5 truncate text-xs text-ink-3">{member.username}</p>
        ) : null}
      </div>

      {/* Badges */}
      <div className="flex shrink-0 flex-wrap items-center gap-2">
        <RoleBadge role={primaryRole} />
        <StatusBadge enabled={member.enabled} />
      </div>

      {/* Row actions — hidden for the current user (self-lockout prevention) */}
      {!isSelf ? (
        <div className="flex shrink-0 items-center gap-1 opacity-0 transition-opacity focus-within:opacity-100 [div:hover>&]:opacity-100">
          <button
            type="button"
            aria-label={t('team.changeRole')}
            title={t('team.changeRole')}
            className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
            onClick={() => onChangeRole(member)}
          >
            {t('team.changeRole')}
          </button>
          {member.enabled ? (
            <button
              type="button"
              aria-label={t('team.deactivate')}
              title={t('team.deactivate')}
              className="rounded-md px-2 py-1 text-xs text-loss/80 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
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
          <span className="rounded-md px-2 py-1 text-xs text-transparent select-none">
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

  const query = useTeam({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('team.noCompany')} hint={t('team.noCompanyHint')} />
  }

  const members = query.data ?? []

  function closeDialog() {
    setDialog(null)
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('team.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('team.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialog({ kind: 'invite' })}>
          <Plus className="size-4" />
          {t('team.inviteMember')}
        </Button>
      </div>

      {/* Member list */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('team.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : members.length === 0 ? (
        <EmptyState title={t('team.empty')} hint={t('team.emptyHint')} />
      ) : (
        <Card className="rounded-[20px] p-2.5">
          <div>
            {members.map((member) => (
              <MemberRow
                key={member.id}
                member={member}
                isSelf={member.email === currentActor || member.username === currentActor}
                onChangeRole={(m) => setDialog({ kind: 'changeRole', member: m })}
                onDeactivate={(m) => setDialog({ kind: 'deactivate', member: m })}
              />
            ))}
          </div>
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
    </div>
  )
}
