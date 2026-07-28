/**
 * EmployeeDetailDrawer — the employee detail view (owner/manager). Its centrepiece is the Login
 * card: the linked login's USERNAME (resolved from the Team list — Keycloak owns it) plus, until the
 * employee first signs in, the one-time PASSWORD held for them (decrypted, ADR 0014). Once the
 * employee activates, the password is gone and the card shows "active". Owners can Reset the
 * password (issues a fresh one-time password, held again) or Remove the login.
 *
 * The one-time password is a credential: shown here for the owner to hand over, never persisted
 * client-side beyond the query cache, never logged.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, KeyRound, Trash2, UserPlus } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { DialogOverlay } from '@/features/org/parts'
import { useTeam } from '@/features/team/api'
import { cn } from '@/lib/cn'
import {
  useEmployee,
  useEmployeeLogin,
  useLinkLogin,
  useResetPassword,
  useUnlinkLogin,
  type EmployeeListRow,
} from './api'

export function EmployeeDetailDrawer({
  employee,
  companyId,
  actor,
  onClose,
  onCreateLogin,
}: {
  employee: EmployeeListRow
  companyId: string
  actor: string
  onClose: () => void
  onCreateLogin: () => void
}) {
  const { t } = useTranslation()
  const employeeId = employee.employeeId
  const hasLogin = !!employee.userId

  const detail = useEmployee({ companyId, actor, employeeId, enabled: true })
  const login = useEmployeeLogin({ companyId, actor, employeeId, enabled: hasLogin })
  const team = useTeam({ companyId, actor, enabled: hasLogin })
  const reset = useResetPassword({ companyId, actor })
  const relink = useLinkLogin({ companyId, actor })
  const unlink = useUnlinkLogin({ companyId, actor })
  const [busy, setBusy] = useState(false)
  // The freshly reset password, shown IMMEDIATELY from the reset result — so even if the follow-up
  // re-hold call fails, the owner still has the working credential in front of them (review Finding 5).
  const [revealedTemp, setRevealedTemp] = useState<string | null>(null)

  const userId = login.data?.userId ?? employee.userId
  const username = team.data?.find((m) => m.id === userId)?.username ?? null
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

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-5">
        {/* Header */}
        <div className="flex items-center gap-3">
          <span className="grid size-11 shrink-0 place-items-center rounded-full bg-emerald-tint text-sm font-semibold text-emerald-2">
            {initials}
          </span>
          <div className="min-w-0">
            <h2 className="font-display text-lg font-semibold text-ink">{employee.fullName}</h2>
            <div className="mt-0.5 flex items-center gap-2">
              <Badge tone={employee.status === 'ACTIVE' ? 'emerald' : 'amber'}>
                {employee.status === 'ACTIVE' ? t('hr.detail.active') : t('hr.list.inactive')}
              </Badge>
              <span className="text-xs text-ink-3">{employee.ptkpStatus}</span>
            </div>
          </div>
        </div>

        {/* Profile (PII masked) */}
        <div className="grid grid-cols-2 gap-3 rounded-2xl border border-line bg-surface p-4 text-sm">
          <Detail label={t('hr.detail.nik')} value={emp?.maskedNik ?? '—'} />
          <Detail label={t('hr.detail.bank')} value={emp?.maskedBankAccount ?? '—'} />
        </div>

        {/* Login card — the point of this view */}
        <section className="rounded-2xl border border-line bg-surface p-4">
          <h3 className="mb-3 text-sm font-semibold text-ink">{t('hr.detail.loginTitle')}</h3>

          {!hasLogin ? (
            <div className="space-y-3">
              <p className="text-sm text-ink-3">{t('hr.detail.noLogin')}</p>
              <Button type="button" variant="outline" onClick={onCreateLogin}>
                <UserPlus className="size-4" />
                {t('hr.list.actionCreateLogin')}
              </Button>
            </div>
          ) : login.isLoading ? (
            <Spinner className="text-brand-500" />
          ) : (
            <div className="space-y-3">
              <Detail label={t('hr.detail.username')} value={username ?? '…'} mono />

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
              {reset.isError || relink.isError || unlink.isError ? (
                <p className="text-sm text-loss">{t('hr.assign.error')}</p>
              ) : null}
            </div>
          )}
        </section>

        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.close')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
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
