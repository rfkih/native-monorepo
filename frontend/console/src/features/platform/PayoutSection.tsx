import { useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { DialogOverlay } from '@/components/ui/Dialog'
import { Field, TextInput } from '@/components/ui/Field'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import type { CompanySession } from '@/lib/session'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { cn } from '@/lib/cn'
import { parsePlatformAmountInput } from './amount'
import { usePayoutSources, useSettlePayout, type PayoutSource, type PayoutSourceLine } from './platformApi'

/**
 * Record ONE payout covering everything a payer settled (ADR 0076): a Shopee transfer clears both
 * the ShopeeFood receivable and the counter QRIS balance, so it is entered once, not twice.
 *
 * <p>The merchant types the NET — the figure on their bank statement — and the deduction is derived
 * (`Σ gross − net`) rather than asked for: nobody knows their MDR and commission rates, but
 * everybody can read what landed in their account.
 *
 * Strings are i18n keys (rule 9); money is integer minor units in, `formatMoney` out (rule 8).
 */
export function PayoutSection({
  session,
  locale,
}: {
  session: CompanySession
  locale: string
}) {
  const { t } = useTranslation()
  const query = usePayoutSources(session)
  const sources = query.data ?? []

  const [selected, setSelected] = useState<string>('')
  const source = sources.find((s) => s.sourceCode === selected) ?? null

  if (query.isError) {
    return (
      <Card className="p-8 text-center text-sm text-loss">{t('platform.payout.error')}</Card>
    )
  }
  if (query.isLoading) {
    return <ListSkeleton rows={3} className="rounded-card" />
  }
  if (sources.length === 0) {
    return (
      <EmptyState title={t('platform.payout.noneTitle')} hint={t('platform.payout.noneHint')} />
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <Card className="p-6">
        <h2 className="text-2xs font-bold uppercase tracking-eyebrow text-ink-3">
          {t('platform.payout.owedHeading')}
        </h2>
        <div className="mt-3 flex flex-col">
          {sources.map((s) => (
            <button
              key={s.sourceCode}
              type="button"
              onClick={() => setSelected(s.sourceCode === selected ? '' : s.sourceCode)}
              aria-pressed={s.sourceCode === selected}
              className={cn(
                'flex items-center gap-3 rounded-lg border-b border-ink-50 px-2 py-3 text-left last:border-0',
                'hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                s.sourceCode === selected && 'bg-emerald-tint',
              )}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold text-ink">{s.sourceCode}</span>
                <span className="block truncate text-xs text-ink-3">
                  {s.lines.map((l) => kindLabel(t, l.sourceKind)).join(' · ')}
                </span>
              </span>
              <span
                className={cn(
                  'tnum shrink-0 font-mono text-sm font-semibold',
                  s.outstandingMinor < 0 ? 'text-loss' : 'text-ink',
                )}
              >
                {formatMoney(s.outstandingMinor, s.currency, locale)}
              </span>
            </button>
          ))}
        </div>
      </Card>

      {/* `key` REMOUNTS the form when the payer changes: its ticked lines and prefilled gross are
          useState initializers, which only run on mount — without this, switching payer left every
          box unticked and every amount blank. */}
      {source ? (
        <PayoutForm
          key={`${source.sourceCode}:${source.currency}`}
          session={session}
          source={source}
          locale={locale}
        />
      ) : null}
    </div>
  )
}

function kindLabel(t: (key: string) => string, kind: PayoutSourceLine['sourceKind']): string {
  return t(`platform.payout.kind.${kind}`)
}

function PayoutForm({
  session,
  source,
  locale,
}: {
  session: CompanySession
  source: PayoutSource
  locale: string
}) {
  const { t } = useTranslation()
  const settle = useSettlePayout(session)
  const exponent = isoMinorExponent(source.currency)

  // Only what CAN be cleared here is selectable; a card balance is shown on the payer row above so
  // the total is never silently short, but it has no fee account yet and cannot be settled.
  const settleable = useMemo(() => source.lines.filter((l) => l.settleable), [source.lines])

  const [checked, setChecked] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(settleable.map((l) => [lineKey(l), l.outstandingMinor > 0])),
  )
  const [gross, setGross] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      settleable.map((l) => [lineKey(l), majorOf(l.outstandingMinor, exponent)]),
    ),
  )
  const [netMajor, setNetMajor] = useState('')
  // Minted ONCE when the confirm opens, never inside the mutation: a key generated per retry would
  // defeat the server's replay de-dupe and could double-book a payout (the ap/api.ts idiom).
  const [confirming, setConfirming] = useState<{ key: string } | null>(null)

  const chosen = settleable.filter((l) => checked[lineKey(l)])
  const grossMinors = chosen.map((l) => parsePlatformAmountInput(gross[lineKey(l)] ?? '', source.currency))
  const anyGrossInvalid = grossMinors.some((g) => g == null || g <= 0)
  const totalGross = grossMinors.reduce<number>((sum, g) => sum + (g ?? 0), 0)
  const netMinor = parsePlatformAmountInput(netMajor, source.currency, { allowZero: true })
  const feeMinor = netMinor == null ? null : totalGross - netMinor

  // A payout of NOTHING is not a payout. The field asks what reached the bank, and "0" is what a
  // reader types when the answer is "it hasn't yet" — which booked an entire card balance as fee on
  // the first real use. Recording nothing received is never the right entry; waiting is.
  const nothingReceived = netMinor === 0 && totalGross > 0
  // A deduction over this share is possible but rare enough to be worth a second look before it
  // becomes an expense that quietly eats the month's profit.
  const feeShare = feeMinor != null && totalGross > 0 ? feeMinor / totalGross : 0
  const feeLooksWrong = !nothingReceived && feeShare > 0.3

  const blocked =
    chosen.length === 0 ||
    anyGrossInvalid ||
    netMinor == null ||
    feeMinor == null ||
    feeMinor < 0 ||
    nothingReceived

  function submit(e: FormEvent) {
    e.preventDefault()
    if (blocked) return
    setConfirming({ key: crypto.randomUUID() })
  }

  return (
    <Card className="p-6">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div>
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('platform.payout.formTitle', { source: source.sourceCode })}
          </h2>
          <p className="mt-1 text-sm text-ink-3">{t('platform.payout.formHint')}</p>
        </div>

        {/* A payer holding only card money has nothing to tick — say why, rather than showing an
            empty list above a permanently disabled button. */}
        {settleable.length === 0 ? (
          <p className="text-sm text-ink-2">{t('platform.payout.nothingSettleable')}</p>
        ) : null}

        <div className="flex flex-col">
          <div className="flex items-baseline gap-3 pb-1 text-2xs font-bold uppercase tracking-eyebrow text-ink-3">
            <span className="flex-1">{t('platform.payout.settledHeading')}</span>
            <span>{t('platform.payout.grossHeading')}</span>
          </div>
          {settleable.map((line) => {
            const key = lineKey(line)
            return (
              <label
                key={key}
                className="flex items-center gap-3 border-b border-ink-50 py-2.5 last:border-0"
              >
                <input
                  type="checkbox"
                  checked={checked[key] ?? false}
                  onChange={(e) => setChecked((c) => ({ ...c, [key]: e.target.checked }))}
                  className="size-4 shrink-0 accent-[color:var(--color-emerald)]"
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm text-ink-2">
                    {kindLabel(t, line.sourceKind)}
                  </span>
                  <span className="block truncate font-mono text-2xs text-ink-3">
                    {line.channelCode}
                  </span>
                </span>
                <input
                  inputMode="decimal"
                  value={gross[key] ?? ''}
                  onChange={(e) => setGross((g) => ({ ...g, [key]: e.target.value }))}
                  aria-label={t('platform.payout.grossHeading')}
                  className="tnum w-32 shrink-0 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-right font-mono text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                />
              </label>
            )
          })}
        </div>

        <div className="flex items-baseline gap-3 border-t border-line-strong pt-3">
          <span className="flex-1 text-sm font-semibold text-ink">
            {t('platform.payout.totalGross')}
          </span>
          <span className="tnum font-mono text-sm font-semibold text-ink">
            {formatMoney(totalGross, source.currency, locale)}
          </span>
        </div>

        <Field label={t('platform.payout.netLabel')} hint={t('platform.payout.netHint')}>
          <TextInput
            inputMode="decimal"
            value={netMajor}
            onChange={(e) => setNetMajor(e.target.value)}
            placeholder={t('platform.payout.netPlaceholder')}
          />
        </Field>

        {/* The deduction is DERIVED and read-only — it is whatever the platform kept, not something
            the merchant is asked to know. Negative means the net exceeds the gross, which is a
            subsidy and out of scope (the server rejects it too). */}
        <div className="flex items-baseline gap-3">
          <span className="flex-1 text-sm text-ink-2">{t('platform.payout.feeLabel')}</span>
          <span
            className={cn(
              'tnum font-mono text-sm font-semibold',
              feeMinor != null && feeMinor < 0 ? 'text-loss' : 'text-ink',
            )}
          >
            {feeMinor == null ? '—' : formatMoney(feeMinor, source.currency, locale)}
          </span>
        </div>
        {feeMinor != null && feeMinor < 0 ? (
          <p className="flex items-start gap-2 text-sm text-loss">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            {t('platform.payout.netExceedsGross')}
          </p>
        ) : null}
        {nothingReceived ? (
          <p className="flex items-start gap-2 text-sm text-loss">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            {t('platform.payout.nothingReceived')}
          </p>
        ) : null}
        {feeLooksWrong ? (
          <p className="flex items-start gap-2 text-sm text-amber-2">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            {t('platform.payout.feeLooksHigh', {
              pct: `${Math.round(feeShare * 100)}%`,
            })}
          </p>
        ) : null}

        <div>
          <Button type="submit" disabled={blocked || settle.isPending}>
            {t('platform.payout.review')}
          </Button>
        </div>
      </form>

      {confirming ? (
        <ConfirmPayout
          source={source}
          locale={locale}
          netMinor={netMinor ?? 0}
          feeMinor={feeMinor ?? 0}
          lines={chosen.map((l, i) => ({
            sourceKind: l.sourceKind,
            channelCode: l.channelCode,
            grossMinor: grossMinors[i] ?? 0,
          }))}
          pending={settle.isPending}
          failed={settle.isError}
          onClose={() => setConfirming(null)}
          onConfirm={() => {
            settle.mutate(
              {
                sourceCode: source.sourceCode,
                lines: chosen.map((l, i) => ({
                  sourceKind: l.sourceKind,
                  channelCode: l.channelCode,
                  grossMinor: grossMinors[i] ?? 0,
                })),
                netMinor: netMinor ?? 0,
                currency: source.currency,
                idempotencyKey: confirming.key,
              },
              {
                onSuccess: () => {
                  setConfirming(null)
                  setNetMajor('')
                },
              },
            )
          }}
        />
      ) : null}
    </Card>
  )
}

function ConfirmPayout({
  source,
  locale,
  netMinor,
  feeMinor,
  lines,
  pending,
  failed,
  onClose,
  onConfirm,
}: {
  source: PayoutSource
  locale: string
  netMinor: number
  feeMinor: number
  lines: { sourceKind: string; channelCode: string; grossMinor: number }[]
  pending: boolean
  failed: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const { t } = useTranslation()
  return (
    <DialogOverlay onClose={onClose} ariaLabel={t('platform.payout.confirmTitle')}>
      <div className="flex flex-col gap-4 p-5">
        <div>
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('platform.payout.confirmTitle')}
          </h2>
          {/* Says "recorded as settled", not "in the bank": the money reaches 1000 Bank only once
              the statement line is reconciled (ADR 0016 keeps that the single Dr-BANK writer). */}
          <p className="mt-1 text-sm text-ink-2">{t('platform.payout.confirmBody')}</p>
          {/* The old habit — reconciling the deposit under the QRIS category — would credit 1901 a
              SECOND time and double the MDR expense. Name the right category here, where the
              decision is about to be made. */}
          <p className="mt-1.5 text-xs text-amber-2">{t('platform.payout.reconcileWarning')}</p>
        </div>

        <div className="flex flex-col gap-1.5 rounded-lg border border-line p-3.5">
          {lines.map((l) => (
            <div key={`${l.sourceKind}:${l.channelCode}`} className="flex items-baseline gap-3 text-sm">
              <span className="min-w-0 flex-1 truncate text-ink-2">
                {t(`platform.payout.kind.${l.sourceKind}`)}
              </span>
              <span className="tnum shrink-0 font-mono text-ink">
                {formatMoney(l.grossMinor, source.currency, locale)}
              </span>
            </div>
          ))}
          <div className="mt-1 flex items-baseline gap-3 border-t border-line pt-2 text-sm">
            <span className="flex-1 font-semibold text-ink">{t('platform.payout.netLabel')}</span>
            <span className="tnum font-mono font-semibold text-ink">
              {formatMoney(netMinor, source.currency, locale)}
            </span>
          </div>
          <div className="flex items-baseline gap-3 text-sm">
            <span className="flex-1 text-ink-3">{t('platform.payout.feeLabel')}</span>
            <span className="tnum font-mono text-ink-3">
              {formatMoney(feeMinor, source.currency, locale)}
            </span>
          </div>
        </div>

        {failed ? (
          <p className="text-sm text-loss">{t('platform.payout.submitFailed')}</p>
        ) : null}

        <div className="flex justify-end gap-2.5">
          <Button variant="outline" onClick={onClose} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button onClick={onConfirm} disabled={pending}>
            {t('platform.payout.confirm')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

function lineKey(line: PayoutSourceLine): string {
  return `${line.sourceKind}:${line.channelCode}`
}

/** Minor units back to the major-unit string the inputs are typed in (rule 8 stays at the edge). */
function majorOf(minor: number, exponent: number): string {
  if (exponent === 0) return String(minor)
  return (minor / 10 ** exponent).toFixed(exponent)
}
