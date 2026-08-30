import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import { useCreateDeferral, useDeferrals, type DeferralKind } from './api'
import { assetErrorKey } from './format'

const SELECT_CLASSES =
  'w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7)
}

function toMinor(major: number, currency: string): number {
  return Math.round(major * 10 ** isoMinorExponent(currency))
}

/**
 * Deferrals — prepaid expenses and deferred revenue spread straight-line over months. Creating one
 * posts its opening entry; the monthly run (on the Fixed assets page) posts each month's share.
 * Owner/manager only. ILLUSTRATIVE / SME-gated — see ADR 0020.
 */
export function Deferrals() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const enabled = !!company
  const currency = company?.baseCurrency ?? 'IDR'

  const deferralsQuery = useDeferrals({ companyId, actor, enabled })
  const createDeferral = useCreateDeferral({ companyId, actor })

  const [dialogOpen, setDialogOpen] = useState(false)
  const [kind, setKind] = useState<DeferralKind>('PREPAID_EXPENSE')
  const [description, setDescription] = useState('')
  const [total, setTotal] = useState('')
  const [months, setMonths] = useState('12')
  const [startPeriod, setStartPeriod] = useState(currentMonth())
  const [error, setError] = useState<string | null>(null)

  useBackDismiss(() => setDialogOpen(false), dialogOpen)

  if (!company) {
    return <EmptyState title={t('assets.noCompany')} hint={t('assets.noCompanyHint')} />
  }

  const deferrals = deferralsQuery.data ?? []

  function openDialog() {
    setKind('PREPAID_EXPENSE')
    setDescription('')
    setTotal('')
    setMonths('12')
    setStartPeriod(currentMonth())
    setError(null)
    setDialogOpen(true)
  }

  async function submit() {
    setError(null)
    if (!description.trim() || !total.trim() || Number(total) <= 0 || Number(months) < 1) {
      setError(t('assets.errors.invalid'))
      return
    }
    try {
      await createDeferral.mutateAsync({
        kind,
        description: description.trim(),
        totalMinor: toMinor(Number(total), currency),
        months: Number(months),
        startPeriod,
        currency,
        // Generated once per submit (stable across TanStack retries; fresh on the next submit).
        idempotencyKey: crypto.randomUUID(),
      })
      setDialogOpen(false)
    } catch (e) {
      setError(t(assetErrorKey(e)))
    }
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('deferrals.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('deferrals.subtitle')}</p>
        </div>
        <Button type="button" onClick={openDialog}>
          <Plus className="size-[15px]" aria-hidden />
          {t('deferrals.new')}
        </Button>
      </div>

      {deferralsQuery.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('deferrals.error')}
        </Card>
      ) : deferralsQuery.isLoading ? (
        <ListSkeleton rows={6} />
      ) : deferrals.length === 0 ? (
        <EmptyState title={t('deferrals.empty')} hint={t('deferrals.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('deferrals.colDescription')}</th>
                <th className="px-4 py-3">{t('deferrals.colKind')}</th>
                <th className="px-4 py-3">{t('deferrals.colStart')}</th>
                <th className="px-4 py-3 text-right">{t('deferrals.colMonths')}</th>
                <th className="px-4 py-3 text-right">{t('deferrals.colTotal')}</th>
                <th className="px-4 py-3 text-right">{t('deferrals.colRemaining')}</th>
              </tr>
            </thead>
            <tbody>
              {deferrals.map((d) => (
                <tr key={d.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">{d.description}</td>
                  <td className="px-4 py-3">
                    <Badge tone={d.kind === 'DEFERRED_REVENUE' ? 'info' : 'neutral'}>
                      {t(`deferrals.kind.${d.kind}` as Parameters<typeof t>[0])}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-ink-2">{formatPeriod(d.startPeriod, locale)}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">{d.months}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(d.totalMinor, d.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(d.remainingMinor, d.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <div>
        <Badge tone="amber">
          <TriangleAlert className="size-3" /> {t('assets.illustrativeNote')}
        </Badge>
      </div>

      {dialogOpen ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={(e) => {
            if (e.target === e.currentTarget) setDialogOpen(false)
          }}
        >
          <Card className="w-full max-w-md p-6">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="font-display text-xl font-semibold text-ink">{t('deferrals.new')}</h2>
              <button
                type="button"
                aria-label={t('common.close')}
                onClick={() => setDialogOpen(false)}
                className="text-ink-3 hover:text-ink"
              >
                <X className="size-5" />
              </button>
            </div>

            <div className="flex flex-col gap-3.5">
              <Field label={t('deferrals.kindLabel')} htmlFor="deferral-kind">
                <select
                  id="deferral-kind"
                  value={kind}
                  onChange={(e) => setKind(e.target.value as DeferralKind)}
                  className={SELECT_CLASSES}
                >
                  <option value="PREPAID_EXPENSE">{t('deferrals.kind.PREPAID_EXPENSE')}</option>
                  <option value="DEFERRED_REVENUE">{t('deferrals.kind.DEFERRED_REVENUE')}</option>
                </select>
              </Field>
              <Field label={t('deferrals.descriptionLabel')} htmlFor="deferral-desc">
                <TextInput
                  id="deferral-desc"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('deferrals.descriptionPlaceholder')}
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label={t('deferrals.totalLabel')} htmlFor="deferral-total">
                  <TextInput
                    id="deferral-total"
                    type="number"
                    min="0"
                    value={total}
                    onChange={(e) => setTotal(e.target.value)}
                  />
                </Field>
                <Field label={t('deferrals.monthsLabel')} htmlFor="deferral-months">
                  <TextInput
                    id="deferral-months"
                    type="number"
                    min="1"
                    max="600"
                    value={months}
                    onChange={(e) => setMonths(e.target.value)}
                  />
                </Field>
              </div>
              <Field label={t('deferrals.startLabel')} htmlFor="deferral-start">
                <TextInput
                  id="deferral-start"
                  type="month"
                  value={startPeriod}
                  onChange={(e) => setStartPeriod(e.target.value)}
                />
              </Field>

              {error ? (
                <div className="flex items-center gap-2 text-sm text-loss">
                  <TriangleAlert className="size-4 shrink-0" aria-hidden />
                  {error}
                </div>
              ) : null}

              <div className="mt-1 flex justify-end gap-2.5">
                <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
                  {t('common.cancel')}
                </Button>
                <Button type="button" onClick={submit} disabled={createDeferral.isPending}>
                  {t('deferrals.create')}
                </Button>
              </div>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  )
}
