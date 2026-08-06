import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { ToggleRow } from '@/components/ui/ToggleRow'
import { useSession } from '@/lib/session'
import { useTierAccess } from '@/lib/featureTier'
import { useSetPlanTier } from './api'

/**
 * `/settings/features` — the owner-only "Simple mode" toggle (P1 tier-mode,
 * `~/.claude/plans/umkm-tier-mode.md`). One card, one switch: OFF = FREE (the lean UMKM console —
 * POS, products, kitchen, printer, dashboard, expenses, team), ON = FULL (today's complete
 * back-office). The escape hatch itself stays reachable from the Structure nav group regardless of
 * the current tier (Shell.tsx, plan Risk 1) — this route is registered as a sibling of
 * `/settings/printer` (App.tsx), but owner-gated there since flipping a company-wide setting is a
 * real authorization decision, unlike the printer's per-device pairing.
 */
export function FeaturesSettings() {
  const { t } = useTranslation()
  const { company } = useSession()
  const tierAccess = useTierAccess()
  const setPlanTier = useSetPlanTier({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
  })

  if (!company) return null

  const onToggle = () => {
    setPlanTier.mutate(tierAccess.isExtended ? 'FREE' : 'FULL')
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
          {t('settings.tier.title')}
        </h1>
      </div>

      <Card className="p-5">
        <ToggleRow
          label={t('settings.tier.switchLabel')}
          hint={t('settings.tier.help')}
          checked={tierAccess.isExtended}
          disabled={setPlanTier.isPending}
          onToggle={onToggle}
        />
      </Card>

      {setPlanTier.isError ? (
        <Card className="p-4 text-sm text-loss">{t('settings.tier.error')}</Card>
      ) : null}
    </div>
  )
}
