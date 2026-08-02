/**
 * ConfirmSettleDialog — the review step before a platform payout actually posts (ADR 0036 Phase
 * C2). Settling money is a one-way ledger post (Dr cash-clearing + Dr platform-fee / Cr
 * platform-receivable), so the settle form's submit does not fire the mutation directly — it opens
 * this dialog with the parsed figures, and ONLY the explicit "Confirm" click here calls the API.
 *
 * Idempotency-Key: minted ONCE when this dialog MOUNTS (`useState(() => crypto.randomUUID())`),
 * not inside the mutation function and not on every submit click. Because the parent only mounts
 * this component while the dialog is open (`{pending ? <ConfirmSettleDialog .../> : null}`), the
 * key is naturally:
 *  - stable across repeated "Confirm" clicks for the SAME reviewed attempt (a network hiccup or a
 *    422 the user corrects-and-retries within the same open dialog reuses it), and
 *  - fresh the next time the dialog opens (closing and reopening remounts the component, which
 *    re-runs the state initializer).
 * This is the same "one key per user-confirmed attempt" contract as
 * features/ap/api.ts's RecordPaymentVariables, applied one step earlier (at dialog-open instead of
 * at submit) because settling is review-then-confirm rather than a single-step form.
 *
 * Any failure (incl. the 422 over-settlement / net-exceeds-gross and the 409 key-conflict problems)
 * surfaces the API's own error message directly — no separate mapped i18n key, mirroring
 * features/channels/ChannelDialog.tsx.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useSettlePlatform } from './platformApi'
import { DialogOverlay } from './parts'

export interface PendingSettlement {
  channelCode: string
  currency: string
  grossMinor: number
  netMinor: number
  feeMinor: number
}

interface Props {
  session: CompanySession
  pending: PendingSettlement
  onClose: () => void
  /** Called after a successful settle (fresh 201 or a same-key replay 200) — the caller resets the form. */
  onSettled: () => void
}

export function ConfirmSettleDialog({ session, pending, onClose, onSettled }: Props) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const settle = useSettlePlatform(session)
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const [error, setError] = useState<string | null>(null)

  function confirm() {
    setError(null)
    settle.mutate(
      {
        channelCode: pending.channelCode,
        grossMinor: pending.grossMinor,
        netMinor: pending.netMinor,
        currency: pending.currency,
        idempotencyKey,
      },
      {
        onSuccess: () => onSettled(),
        onError: (err) => setError(err instanceof Error ? err.message : String(err)),
      },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="font-display text-lg font-semibold text-ink">{t('platform.confirm.title')}</h2>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-5" />
          </button>
        </div>

        <p className="text-sm text-ink-3">{t('platform.confirm.body')}</p>

        <dl className="space-y-2 rounded-xl border border-line bg-paper p-4 text-sm">
          <Row label={t('platform.confirm.channelLabel')} value={pending.channelCode} mono />
          <Row
            label={t('platform.confirm.grossLabel')}
            value={formatMoney(pending.grossMinor, pending.currency, locale)}
          />
          <Row
            label={t('platform.confirm.netLabel')}
            value={formatMoney(pending.netMinor, pending.currency, locale)}
          />
          <Row
            label={t('platform.confirm.feeLabel')}
            value={formatMoney(pending.feeMinor, pending.currency, locale)}
          />
        </dl>

        {error ? (
          <p className="text-sm text-loss" role="alert">
            {error}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose} disabled={settle.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={confirm} disabled={settle.isPending}>
            {settle.isPending ? <Spinner /> : t('platform.confirm.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-ink-3">{label}</dt>
      <dd className={mono ? 'font-mono font-semibold text-ink' : 'tnum font-mono font-semibold text-ink'}>
        {value}
      </dd>
    </div>
  )
}
