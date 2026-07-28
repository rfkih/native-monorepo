/**
 * EditPagesDialog — per-login page access (ADR 0013, extended). Shows the pages the login's ROLE can
 * reach (role-aware; grants only narrow within the role, never beyond it) and lets an owner/manager
 * toggle which of them this login sees in the console. Reused by the Team page and the org-unit
 * People tab. Owner logins bypass grants entirely, so restricting an owner has no effect.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import { grantablePagesForRoles, type PageKey } from '@/lib/pageAccess'
import { useSetUserPages, useUserPages, type TeamMember } from './api'

export function EditPagesDialog({
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
  // The pages this login's roles can actually reach — grants only subtract within this set.
  const grantable = grantablePagesForRoles(member.roles)
  const pagesQuery = useUserPages({ userId: member.id, companyId, actor, enabled: true })
  const mutation = useSetUserPages({ companyId, actor })

  // Local selection — seeded once the current grants load. mode ALL = every grantable page on.
  const [selected, setSelected] = useState<Set<string> | null>(null)
  if (selected === null && !pagesQuery.isLoading && pagesQuery.data) {
    setSelected(
      pagesQuery.data.mode === 'ALL'
        ? new Set(grantable)
        : new Set(pagesQuery.data.pageKeys.filter((k) => grantable.includes(k as PageKey))),
    )
  }

  function toggle(page: string) {
    setSelected((prev) => {
      const next = new Set(prev ?? [])
      if (next.has(page)) next.delete(page)
      else next.add(page)
      return next
    })
  }

  function handleSave() {
    const chosen = selected ?? new Set(grantable)
    // All grantable pages selected → clear grants (mode ALL — the full role surface). Otherwise send
    // the chosen set PLUS 'me' (the always-available floor is never removed console-side).
    const allSelected = grantable.every((p) => chosen.has(p))
    const pageKeys = allSelected ? [] : [...chosen, 'me']
    mutation.mutate({ userId: member.id, pageKeys }, { onSuccess: onClose })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('team.editPagesDialog.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('team.editPagesDialog.body', { email: member.email || member.username })}
        </p>

        {grantable.length === 0 ? (
          <p className="text-sm text-ink-3">{t('team.editPagesDialog.noneForRole')}</p>
        ) : pagesQuery.isLoading || selected === null ? (
          <div className="flex justify-center py-6">
            <Spinner className="text-emerald" />
          </div>
        ) : (
          <div
            role="group"
            aria-label={t('team.editPagesDialog.title')}
            className="overflow-hidden rounded-xl border border-line"
          >
            {grantable.map((page, idx) => {
              const checkboxId = `page-grant-${page}`
              return (
                <label
                  key={page}
                  htmlFor={checkboxId}
                  className={cn(
                    'flex h-11 cursor-pointer items-center gap-3 px-4 transition-colors hover:bg-hover',
                    idx !== grantable.length - 1 && 'border-b border-ink-50',
                  )}
                >
                  <input
                    id={checkboxId}
                    type="checkbox"
                    checked={(selected ?? new Set()).has(page)}
                    onChange={() => toggle(page)}
                    className="size-4 shrink-0 cursor-pointer accent-emerald"
                  />
                  <span className="text-sm text-ink">{t(`team.pages.${page}` as const)}</span>
                </label>
              )
            })}
          </div>
        )}

        <p className="text-xs text-ink-3">{t('team.editPagesDialog.hint')}</p>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('team.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            onClick={handleSave}
            disabled={mutation.isPending || (grantable.length > 0 && selected === null)}
          >
            {mutation.isPending ? t('team.editPagesDialog.saving') : t('team.editPagesDialog.save')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}
