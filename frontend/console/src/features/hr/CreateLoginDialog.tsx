/**
 * CreateLoginDialog — mint a console login for an HR employee. Orchestrates two calls: the
 * org-service invite (role `employee`, optionally + `cashier` for POS capability; returns the
 * ONE-TIME temporary password) and the employee-service login-link (Keycloak sub → employee).
 * The login is keyed by a username (defaulted from the employee's name); email is OPTIONAL, since
 * some employees have no email address. If the link fails after the invite succeeded, the dialog
 * stays open in a retry-link state — the invite is not repeatable (the username now exists), but
 * the link is idempotent.
 *
 * The temporary password is displayed ONCE, never cached, never logged.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { DialogOverlay } from '@/features/org/parts'
import { useInviteMember, type InviteResponse } from '@/features/team/api'
import { useLinkLogin, type EmployeeListRow } from './api'

export function CreateLoginDialog({
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
  // A sensible default username derived from the employee's name (owner can edit). Some employees
  // have no email, so the login is keyed by this username; email is optional contact metadata.
  const suggestedUsername = employee.fullName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '.')
    .replace(/(^\.|\.$)/g, '')
  const [username, setUsername] = useState(suggestedUsername)
  const [email, setEmail] = useState('')
  const [posCapable, setPosCapable] = useState(false)
  const [invite, setInvite] = useState<InviteResponse | null>(null)
  const [linkFailed, setLinkFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  const inviteMember = useInviteMember({ companyId, actor })
  const linkLogin = useLinkLogin({ companyId, actor })

  async function link(userId: string) {
    try {
      await linkLogin.mutateAsync({ employeeId: employee.employeeId, userId })
      setLinkFailed(false)
    } catch {
      setLinkFailed(true)
      throw new Error('link failed')
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    try {
      const result = await inviteMember.mutateAsync({
        username: username.trim(),
        email: email.trim() || undefined,
        role: 'employee',
        additionalRoles: posCapable ? ['cashier'] : [],
      })
      if (!result) throw new Error('empty invite response')
      setInvite(result)
      await link(result.id)
    } catch {
      // invite error renders below; link failure flips to the retry state (invite kept).
    } finally {
      setBusy(false)
    }
  }

  async function handleRetryLink() {
    if (!invite) return
    setBusy(true)
    try {
      await link(invite.id)
    } catch {
      // stays in retry state
    } finally {
      setBusy(false)
    }
  }

  // Success view: the one-time password (shown once — copy it now).
  if (invite && !linkFailed) {
    return (
      <DialogOverlay onClose={onClose}>
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('hr.createLogin.doneTitle', { name: employee.fullName })}
          </h2>
          <p className="text-sm text-ink-2">{t('hr.createLogin.doneBody')}</p>
          <Field label={t('hr.createLogin.tempPassword')}>
            <p className="select-all rounded-xl border border-line bg-paper px-3.5 py-2.5 font-mono text-sm text-ink">
              {invite.temporaryPassword}
            </p>
          </Field>
          <p className="rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5 text-xs leading-relaxed text-amber">
            {t('hr.createLogin.tempPasswordNote')}
          </p>
          <div className="flex justify-end">
            <Button type="button" onClick={onClose}>
              {t('hr.createLogin.close')}
            </Button>
          </div>
        </div>
      </DialogOverlay>
    )
  }

  // Retry-link view: the login exists in Keycloak but is not yet attached to the employee.
  if (invite && linkFailed) {
    return (
      <DialogOverlay onClose={onClose}>
        <div className="space-y-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('hr.createLogin.linkFailedTitle')}
          </h2>
          <p className="text-sm text-ink-2">{t('hr.createLogin.linkFailedBody')}</p>
          <div className="flex justify-end gap-3">
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="button" onClick={handleRetryLink} disabled={busy}>
              {busy ? t('hr.createLogin.creating') : t('hr.createLogin.retryLink')}
            </Button>
          </div>
        </div>
      </DialogOverlay>
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('hr.createLogin.title', { name: employee.fullName })}
        </h2>

        <Field
          label={t('hr.createLogin.username')}
          htmlFor="login-username"
          hint={t('hr.createLogin.usernameHint')}
        >
          <TextInput
            id="login-username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            autoFocus
          />
        </Field>

        <Field
          label={t('hr.createLogin.email')}
          htmlFor="login-email"
          hint={t('hr.createLogin.emailHint')}
        >
          <TextInput
            id="login-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>

        <label className="flex cursor-pointer items-start gap-3 rounded-2xl border border-line bg-surface p-4">
          <input
            type="checkbox"
            checked={posCapable}
            onChange={(e) => setPosCapable(e.target.checked)}
            className="mt-0.5 size-4 accent-emerald"
          />
          <span>
            <span className="block text-sm font-medium text-ink">
              {t('hr.createLogin.posCheckbox')}
            </span>
            <span className="mt-0.5 block text-xs text-ink-3">
              {t('hr.createLogin.posCheckboxHint')}
            </span>
          </span>
        </label>

        {inviteMember.isError ? (
          <p className="text-sm text-loss">
            {(inviteMember.error as { status?: number } | null)?.status === 409
              ? t('hr.createLogin.usernameTaken')
              : t('hr.assign.error')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || username.trim().length < 3}>
            {busy ? t('hr.createLogin.creating') : t('hr.createLogin.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
