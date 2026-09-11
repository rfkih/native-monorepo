import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import type { CompanySession } from '@/lib/session'
import { usePayoutSources, useSetSettlementSource, useSettlementSources } from './platformApi'

/**
 * Who pays out the QRIS and card balances (ADR 0076). A merchant using Shopee's QR is paid by Shopee
 * together with its ShopeeFood orders; a Xendit QR settles on its own. Naming a payer here is what
 * MERGES that balance into the payer's payout — and moves what has already built up.
 *
 * <p><strong>A dropdown, never free text.</strong> The value has to match an existing payer exactly
 * to merge with it, so a typo would not fail — it would silently open a SECOND payout group that
 * never settles alongside the first. The options are the payers finance can already see in its own
 * sub-ledger; the sales-channel list belongs to restaurant-service and must not be read across the
 * boundary.
 *
 * <p>Nothing is lost by having no free-text escape: configuring a payer only matters when that
 * payer ALSO pays you for something else. An acquirer that settles QRIS on its own — Xendit,
 * Midtrans, a bank — needs no configuration at all, which is exactly what the standalone option
 * means.
 */
export function SettlementSourceSettings({ session }: { session: CompanySession }) {
  const { t } = useTranslation()
  const sourcesQuery = usePayoutSources(session)

  return (
    <Card className="p-6">
      <h2 className="text-2xs font-bold uppercase tracking-eyebrow text-ink-3">
        {t('platform.sourceConfig.heading')}
      </h2>
      <p className="mt-1.5 max-w-[62ch] text-sm text-ink-3">
        {t('platform.sourceConfig.hint')}
      </p>

      <div className="mt-4 flex flex-col gap-4">
        <PayerPicker session={session} kind="QRIS" />
        <PayerPicker session={session} kind="CARD" />
      </div>

      {(sourcesQuery.data ?? []).every((s) => !s.lines.some((l) => l.sourceKind === 'MARKETPLACE')) ? (
        <p className="mt-3 text-sm text-ink-3">{t('platform.sourceConfig.noPayers')}</p>
      ) : null}
    </Card>
  )
}

/** One tender family's payer. Card and QRIS differ only in which family they name. */
function PayerPicker({ session, kind }: { session: CompanySession; kind: 'QRIS' | 'CARD' }) {
  const { t } = useTranslation()
  const configQuery = useSettlementSources(session)
  const sourcesQuery = usePayoutSources(session)
  const save = useSetSettlementSource(session)

  /** The unset state: the balance settles under its own name, not merged into anyone. */
  const STANDALONE = kind

  const configured = (configQuery.data ?? []).find((c) => c.sourceKind === kind)
  const [choice, setChoice] = useState<string | null>(null)
  const current = choice ?? configured?.sourceCode ?? STANDALONE

  const options = useMemo(() => {
    const payers = (sourcesQuery.data ?? [])
      .filter((s) => s.lines.some((l) => l.sourceKind === 'MARKETPLACE'))
      .map((s) => s.sourceCode)
    // Keep whatever is configured even if that payer currently owes nothing and so is absent from
    // the sub-ledger read — otherwise saving would silently reset it to standalone.
    const configuredCode = configured?.sourceCode
    const all = new Set(payers)
    if (configuredCode && configuredCode !== STANDALONE) all.add(configuredCode)
    return [...all].sort()
  }, [sourcesQuery.data, configured])

  const dirty = current !== (configured?.sourceCode ?? STANDALONE)

  return (
    <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[220px] flex-1">
          <Field label={t(`platform.sourceConfig.${kind === 'QRIS' ? 'qrisLabel' : 'cardLabel'}`)}>
            <Select value={current} onChange={(e) => setChoice(e.target.value)}>
              <option value={STANDALONE}>{t('platform.sourceConfig.standalone')}</option>
              {options.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <Button
          onClick={() =>
            save.mutate({ sourceKind: kind, sourceCode: current }, { onSuccess: () => setChoice(null) })
          }
          disabled={save.isPending || !dirty}
        >
          {t('platform.sourceConfig.save')}
        </Button>
      {save.isError ? (
        <p className="mt-2 w-full text-sm text-loss">{t('platform.sourceConfig.saveFailed')}</p>
      ) : null}
    </div>
  )
}
