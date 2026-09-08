import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import type { CompanySession } from '@/lib/session'
import {
  usePayoutSources,
  useSetSettlementSource,
  useSettlementSources,
} from './platformApi'

/**
 * Who pays out the QRIS balance (ADR 0076). A merchant using Shopee's QR is paid by Shopee together
 * with its ShopeeFood orders; a Xendit QR settles on its own. Only the merchant knows which, and
 * naming it here is what MERGES the QRIS balance into that payer's payout.
 *
 * <p>Unconfigured is a valid state, not an error: the balance settles under its own name until a
 * payer is named. Naming one also moves the balance already accrued, so there is nothing to
 * migrate by hand afterwards.
 */
export function SettlementSourceSettings({ session }: { session: CompanySession }) {
  const { t } = useTranslation()
  const configQuery = useSettlementSources(session)
  const sourcesQuery = usePayoutSources(session)
  const save = useSetSettlementSource(session)

  const configured = (configQuery.data ?? []).find((c) => c.sourceKind === 'QRIS')
  const [value, setValue] = useState<string | null>(null)
  const current = value ?? configured?.sourceCode ?? ''

  // Suggestions come from the payers finance can already see in its own sub-ledger — the sales
  // channel list belongs to another service and must not be read across the boundary.
  const suggestions = Array.from(
    new Set(
      (sourcesQuery.data ?? [])
        .filter((s) => s.lines.some((l) => l.sourceKind === 'MARKETPLACE'))
        .map((s) => s.sourceCode),
    ),
  )

  return (
    <Card className="p-6">
      <h2 className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
        {t('platform.sourceConfig.heading')}
      </h2>
      <p className="mt-1.5 max-w-[62ch] text-[13px] text-ink-3">
        {t('platform.sourceConfig.hint')}
      </p>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <div className="min-w-[220px] flex-1">
          <Field label={t('platform.sourceConfig.qrisLabel')}>
            <TextInput
              value={current}
              onChange={(e) => setValue(e.target.value.toUpperCase())}
              placeholder={t('platform.sourceConfig.placeholder')}
              list="settlement-source-suggestions"
              maxLength={32}
            />
          </Field>
          <datalist id="settlement-source-suggestions">
            {suggestions.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </div>
        <Button
          onClick={() => {
            const code = current.trim()
            if (!code) return
            save.mutate(
              { sourceKind: 'QRIS', sourceCode: code },
              { onSuccess: () => setValue(null) },
            )
          }}
          disabled={save.isPending || current.trim() === '' || current.trim() === configured?.sourceCode}
        >
          {t('platform.sourceConfig.save')}
        </Button>
      </div>

      {save.isError ? (
        <p className="mt-2 text-[13px] text-loss">{t('platform.sourceConfig.saveFailed')}</p>
      ) : null}
    </Card>
  )
}
