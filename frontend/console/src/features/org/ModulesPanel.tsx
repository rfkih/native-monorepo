/**
 * ModulesPanel — owner/manager module-entitlement management: which verticals/platform modules
 * (restaurant, carwash, barbershop, laundromat, hr, finance) this company can use. Backed by
 * entitlement-service's `/api/v1/entitlements` (list/grant/revoke, company-scoped — RLS on
 * tenant_entitlement, no outlet/businessId involved). Mounted as a card on the org tree page
 * (OrgTree.tsx), following its existing Card + confirm-dialog patterns (the revoke dialog mirrors
 * org/parts.tsx's DeactivateDialog).
 *
 * Rules: every user-facing string is an i18n key (rule 9, react-i18next); grantedAt/revokedAt
 * render via Intl.DateTimeFormat, never manual date formatting. No money involved here.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Puzzle, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Skeleton } from '@/components/ui/Skeleton'
import { apiFetch } from '@/lib/api'
import { type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { DialogOverlay } from './parts'

/**
 * The module keys the console has a display label for (mirrors entitlement-service's
 * `native.entitlement.default-modules`). Any OTHER key returned by the API still renders — using
 * the raw key as its own label — so an entitlement the console doesn't know about is never hidden.
 */
const KNOWN_MODULES = ['restaurant', 'carwash', 'barbershop', 'laundromat', 'hr', 'finance'] as const

/** Mirrors entitlement-service's EntitlementResponse (GET/POST /api/v1/entitlements). */
interface EntitlementResponse {
  moduleKey: string
  status: 'ENTITLED' | 'REVOKED'
  grantedAt: string
  revokedAt: string | null
}

function useEntitlements(session: CompanySession) {
  return useQuery({
    queryKey: ['entitlements', session.companyId],
    queryFn: async () => {
      const result = await apiFetch<EntitlementResponse[]>('/api/v1/entitlements', {
        tenant: { companyId: session.companyId, actor: session.actor },
      })
      return result ?? []
    },
  })
}

function useGrantEntitlement(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (moduleKey: string) =>
      apiFetch<EntitlementResponse>('/api/v1/entitlements', {
        method: 'POST',
        tenant: { companyId: session.companyId, actor: session.actor },
        body: { moduleKey },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['entitlements', session.companyId] }),
  })
}

function useRevokeEntitlement(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (moduleKey: string) =>
      apiFetch<null>(`/api/v1/entitlements/${moduleKey}`, {
        method: 'DELETE',
        tenant: { companyId: session.companyId, actor: session.actor },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['entitlements', session.companyId] }),
  })
}

/** One merged row: a known-or-unknown module key plus its entitlement, if any has ever been granted. */
interface ModuleRowData {
  moduleKey: string
  entitlement: EntitlementResponse | null
}

/** Display label for a module key — a known catalog entry gets its translated name; anything else
 * falls back to the raw key (never hides an entitlement the console doesn't recognise). */
function useModuleLabel() {
  const { t } = useTranslation()
  return (moduleKey: string) =>
    t(`modules.catalog.${moduleKey}` as Parameters<typeof t>[0], { defaultValue: moduleKey })
}

export function ModulesPanel({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const moduleLabel = useModuleLabel()

  const query = useEntitlements(session)
  const grant = useGrantEntitlement(session)

  const [revokeTarget, setRevokeTarget] = useState<ModuleRowData | null>(null)

  const entitlements = query.data ?? []
  const byKey = new Map(entitlements.map((e) => [e.moduleKey, e]))
  const extraKeys = entitlements
    .map((e) => e.moduleKey)
    .filter((k) => !(KNOWN_MODULES as readonly string[]).includes(k))
  const rows: ModuleRowData[] = [...KNOWN_MODULES, ...extraKeys].map((moduleKey) => ({
    moduleKey,
    entitlement: byKey.get(moduleKey) ?? null,
  }))

  return (
    <Card className="p-5">
      <div className="mb-4 flex items-start gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          <Puzzle className="size-4" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-ink">{t('modules.title')}</h2>
          <p className="mt-0.5 text-sm text-ink-3">{t('modules.subtitle')}</p>
        </div>
      </div>

      {query.isError ? (
        <p className="flex items-center gap-2 rounded-xl border border-loss/30 bg-tint-loss px-4 py-3 text-sm text-loss">
          <TriangleAlert className="size-4 shrink-0" aria-hidden="true" />
          {t('modules.error')}
        </p>
      ) : query.isLoading ? (
        <ul className="divide-y divide-line">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <li key={i} className="flex items-center gap-3 py-2.5">
              <div className="min-w-0 flex-1">
                <Skeleton className="h-4 w-32" />
              </div>
              <Skeleton className="h-8 w-20 rounded-xl" />
            </li>
          ))}
        </ul>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map((row) => (
            <ModuleRow
              key={row.moduleKey}
              row={row}
              locale={locale}
              label={moduleLabel(row.moduleKey)}
              granting={grant.isPending}
              onGrant={() => grant.mutate(row.moduleKey)}
              onRevokeClick={() => setRevokeTarget(row)}
            />
          ))}
        </ul>
      )}

      {grant.isError ? <p className="mt-3 text-xs text-loss">{t('modules.grantError')}</p> : null}

      {revokeTarget ? (
        <RevokeModuleDialog
          session={session}
          moduleKey={revokeTarget.moduleKey}
          label={moduleLabel(revokeTarget.moduleKey)}
          onClose={() => setRevokeTarget(null)}
        />
      ) : null}
    </Card>
  )
}

function ModuleRow({
  row,
  locale,
  label,
  granting,
  onGrant,
  onRevokeClick,
}: {
  row: ModuleRowData
  locale: string
  label: string
  granting: boolean
  onGrant: () => void
  onRevokeClick: () => void
}) {
  const { t } = useTranslation()
  const isEntitled = row.entitlement?.status === 'ENTITLED'
  const isRevoked = row.entitlement?.status === 'REVOKED'

  const dateLabel = isEntitled
    ? t('modules.grantedOn', { date: formatDate(row.entitlement!.grantedAt, locale) })
    : isRevoked && row.entitlement!.revokedAt
      ? t('modules.revokedOn', { date: formatDate(row.entitlement!.revokedAt, locale) })
      : null

  return (
    <li className="flex items-center gap-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-semibold text-ink">{label}</span>
          <Badge tone={isEntitled ? 'profit' : 'neutral'}>
            {isEntitled
              ? t('modules.statusEntitled')
              : isRevoked
                ? t('modules.statusRevoked')
                : t('modules.notGranted')}
          </Badge>
        </div>
        {dateLabel ? <p className="mt-0.5 text-xs text-ink-3">{dateLabel}</p> : null}
      </div>
      {isEntitled ? (
        <Button size="sm" variant="outline" onClick={onRevokeClick}>
          {t('modules.revoke')}
        </Button>
      ) : (
        <Button size="sm" onClick={onGrant} disabled={granting}>
          {granting ? <Spinner /> : t('modules.grant')}
        </Button>
      )}
    </li>
  )
}

function RevokeModuleDialog({
  session,
  moduleKey,
  label,
  onClose,
}: {
  session: CompanySession
  moduleKey: string
  label: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useRevokeEntitlement(session)

  function handleConfirm() {
    mutation.mutate(moduleKey, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('modules.revokeDialog.title')}</h2>
        <p className="text-sm text-ink-2">{t('modules.revokeDialog.body', { name: label })}</p>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('modules.revokeDialog.errorTitle')}</p>
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
            {mutation.isPending ? t('modules.revokeDialog.submitting') : t('modules.revokeDialog.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

function formatDate(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(iso))
}
