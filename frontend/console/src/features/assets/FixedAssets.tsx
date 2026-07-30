import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Play, Plus, TriangleAlert, X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import { useAcquireAsset, useAssets, useDisposeAsset, useRunAmortization, useRuns, type Asset } from './api'
import { assetErrorKey } from './format'

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7)
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function toMinor(major: number, currency: string): number {
  return Math.round(major * 10 ** isoMinorExponent(currency))
}

/**
 * Fixed assets — the register (cost, accumulated depreciation, book value), an Acquire dialog
 * (posts the capex entry), and the monthly Run-depreciation control (idempotent per month) with the
 * run history. Owner/manager only. ILLUSTRATIVE / SME-gated — see ADR 0020.
 */
export function FixedAssets() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const enabled = !!company
  const currency = company?.baseCurrency ?? 'IDR'

  const assetsQuery = useAssets({ companyId, actor, enabled })
  const runsQuery = useRuns({ companyId, actor, enabled })
  const acquireAsset = useAcquireAsset({ companyId, actor })
  const disposeAsset = useDisposeAsset({ companyId, actor })
  const runAmortization = useRunAmortization({ companyId, actor })

  const [dialogOpen, setDialogOpen] = useState(false)
  const [name, setName] = useState('')
  const [acquisitionDate, setAcquisitionDate] = useState(todayIso())
  const [cost, setCost] = useState('')
  const [salvage, setSalvage] = useState('0')
  const [months, setMonths] = useState('60')
  const [runPeriod, setRunPeriod] = useState(currentMonth())
  const [error, setError] = useState<string | null>(null)
  const [runError, setRunError] = useState<string | null>(null)
  // Dispose dialog (ADR 0022): the asset being sold/scrapped, or null when closed.
  const [disposeTarget, setDisposeTarget] = useState<Asset | null>(null)
  const [disposeDate, setDisposeDate] = useState(todayIso())
  const [proceeds, setProceeds] = useState('0')
  const [disposeError, setDisposeError] = useState<string | null>(null)

  if (!company) {
    return <EmptyState title={t('assets.noCompany')} hint={t('assets.noCompanyHint')} />
  }

  const assets = assetsQuery.data ?? []
  const runs = runsQuery.data ?? []
  const periodAlreadyRun = runs.some((r) => r.period === runPeriod)

  function openDialog() {
    setName('')
    setAcquisitionDate(todayIso())
    setCost('')
    setSalvage('0')
    setMonths('60')
    setError(null)
    setDialogOpen(true)
  }

  async function submitAcquire() {
    setError(null)
    if (!name.trim() || !cost.trim() || Number(cost) <= 0 || Number(months) < 1) {
      setError(t('assets.errors.invalid'))
      return
    }
    try {
      await acquireAsset.mutateAsync({
        name: name.trim(),
        acquisitionDate,
        costMinor: toMinor(Number(cost), currency),
        salvageMinor: toMinor(Number(salvage || '0'), currency),
        usefulLifeMonths: Number(months),
        currency,
        // Generated once per submit (stable across TanStack retries; fresh on the next submit).
        idempotencyKey: crypto.randomUUID(),
      })
      setDialogOpen(false)
    } catch (e) {
      setError(t(assetErrorKey(e)))
    }
  }

  async function submitRun() {
    setRunError(null)
    try {
      await runAmortization.mutateAsync(runPeriod)
    } catch (e) {
      setRunError(t(assetErrorKey(e)))
    }
  }

  function openDispose(asset: Asset) {
    setDisposeDate(todayIso())
    setProceeds('0')
    setDisposeError(null)
    setDisposeTarget(asset)
  }

  async function submitDispose() {
    if (!disposeTarget) return
    setDisposeError(null)
    if (Number(proceeds) < 0) {
      setDisposeError(t('assets.errors.invalid'))
      return
    }
    try {
      await disposeAsset.mutateAsync({
        assetId: disposeTarget.id,
        disposalDate: disposeDate,
        proceedsMinor: toMinor(Number(proceeds || '0'), disposeTarget.currency),
        currency: disposeTarget.currency,
        // Generated once per submit (stable across TanStack retries; fresh on the next submit).
        idempotencyKey: crypto.randomUUID(),
      })
      setDisposeTarget(null)
    } catch (e) {
      setDisposeError(t(assetErrorKey(e)))
    }
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('assets.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('assets.subtitle')}</p>
        </div>
        <Button type="button" onClick={openDialog}>
          <Plus className="size-[15px]" aria-hidden />
          {t('assets.acquire')}
        </Button>
      </div>

      <Card className="flex flex-wrap items-end justify-between gap-3 p-4">
        <div className="flex flex-wrap items-end gap-2.5">
          <Field label={t('assets.runPeriodLabel')} htmlFor="run-period">
            <TextInput
              id="run-period"
              type="month"
              value={runPeriod}
              onChange={(e) => setRunPeriod(e.target.value)}
              className="h-11"
            />
          </Field>
          <Button
            type="button"
            onClick={submitRun}
            disabled={runAmortization.isPending || periodAlreadyRun}
          >
            <Play className="size-[15px]" aria-hidden />
            {periodAlreadyRun ? t('assets.alreadyRun') : t('assets.runDepreciation')}
          </Button>
        </div>
        <span className="text-xs text-ink-3">{t('assets.runHint')}</span>
      </Card>

      {runError ? (
        <Card className="flex items-center gap-2 p-3.5 text-sm text-loss">
          <TriangleAlert className="size-[15px] shrink-0" aria-hidden />
          {runError}
        </Card>
      ) : null}

      {assetsQuery.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('assets.error')}
        </Card>
      ) : assetsQuery.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : assets.length === 0 ? (
        <EmptyState title={t('assets.empty')} hint={t('assets.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('assets.colName')}</th>
                <th className="px-4 py-3">{t('assets.colAcquired')}</th>
                <th className="px-4 py-3 text-right">{t('assets.colLife')}</th>
                <th className="px-4 py-3 text-right">{t('assets.colCost')}</th>
                <th className="px-4 py-3 text-right">{t('assets.colAccumulated')}</th>
                <th className="px-4 py-3 text-right">{t('assets.colBookValue')}</th>
                <th className="px-4 py-3">{t('assets.colStatus')}</th>
                <th className="px-4 py-3">
                  <span className="sr-only">{t('assets.dispose')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {assets.map((a) => (
                <tr key={a.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">{a.name}</td>
                  <td className="px-4 py-3 text-ink-2">
                    {new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(
                      new Date(a.acquisitionDate),
                    )}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {t('assets.lifeMonths', { count: a.usefulLifeMonths })}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(a.costMinor, a.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                    {formatMoney(a.accumulatedMinor, a.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                    {formatMoney(a.bookValueMinor, a.currency, locale)}
                  </td>
                  <td className="px-4 py-3">
                    {a.status === 'DISPOSED' ? (
                      <Badge tone="amber">{t('assets.statusDisposed')}</Badge>
                    ) : (
                      <Badge tone="neutral">{t('assets.statusActive')}</Badge>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {a.status === 'ACTIVE' ? (
                      <Button type="button" variant="outline" onClick={() => openDispose(a)}>
                        {t('assets.dispose')}
                      </Button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <div>
        <h2 className="mb-2.5 mt-2 font-display text-lg font-semibold text-ink">
          {t('assets.runsTitle')}
        </h2>
        {runs.length === 0 ? (
          <EmptyState title={t('assets.runsEmpty')} hint={t('assets.runsEmptyHint')} />
        ) : (
          <Card className="overflow-x-auto rounded-[20px]">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('assets.colPeriod')}</th>
                  <th className="px-4 py-3 text-right">{t('assets.colItems')}</th>
                  <th className="px-4 py-3 text-right">{t('assets.colPosted')}</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                    <td className="px-4 py-3 font-semibold text-ink">
                      {formatPeriod(r.period, locale)}
                    </td>
                    <td className="tnum px-4 py-3 text-right font-mono text-ink-2">{r.lineCount}</td>
                    <td className="tnum px-4 py-3 text-right font-mono text-ink">
                      {formatMoney(r.totalMinor, currency, locale)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )}
      </div>

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
              <h2 className="font-display text-xl font-semibold text-ink">{t('assets.acquire')}</h2>
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
              <Field label={t('assets.nameLabel')} htmlFor="asset-name">
                <TextInput
                  id="asset-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('assets.namePlaceholder')}
                />
              </Field>
              <Field label={t('assets.dateLabel')} htmlFor="asset-date">
                <TextInput
                  id="asset-date"
                  type="date"
                  value={acquisitionDate}
                  onChange={(e) => setAcquisitionDate(e.target.value)}
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label={t('assets.costLabel')} htmlFor="asset-cost">
                  <TextInput
                    id="asset-cost"
                    type="number"
                    min="0"
                    value={cost}
                    onChange={(e) => setCost(e.target.value)}
                  />
                </Field>
                <Field label={t('assets.salvageLabel')} htmlFor="asset-salvage">
                  <TextInput
                    id="asset-salvage"
                    type="number"
                    min="0"
                    value={salvage}
                    onChange={(e) => setSalvage(e.target.value)}
                  />
                </Field>
              </div>
              <Field label={t('assets.lifeLabel')} htmlFor="asset-months">
                <TextInput
                  id="asset-months"
                  type="number"
                  min="1"
                  max="600"
                  value={months}
                  onChange={(e) => setMonths(e.target.value)}
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
                <Button type="button" onClick={submitAcquire} disabled={acquireAsset.isPending}>
                  {t('assets.acquireConfirm')}
                </Button>
              </div>
            </div>
          </Card>
        </div>
      ) : null}

      {disposeTarget ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={(e) => {
            if (e.target === e.currentTarget) setDisposeTarget(null)
          }}
        >
          <Card className="w-full max-w-md p-6">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="font-display text-xl font-semibold text-ink">
                {t('assets.disposeTitle', { name: disposeTarget.name })}
              </h2>
              <button
                type="button"
                aria-label={t('common.close')}
                onClick={() => setDisposeTarget(null)}
                className="text-ink-3 hover:text-ink"
              >
                <X className="size-5" />
              </button>
            </div>

            <p className="mb-4 text-sm leading-relaxed text-ink-3">
              {t('assets.disposeHint', {
                bookValue: formatMoney(
                  disposeTarget.bookValueMinor,
                  disposeTarget.currency,
                  locale,
                ),
              })}
            </p>

            <div className="flex flex-col gap-3.5">
              <Field label={t('assets.disposeDateLabel')} htmlFor="dispose-date">
                <TextInput
                  id="dispose-date"
                  type="date"
                  value={disposeDate}
                  onChange={(e) => setDisposeDate(e.target.value)}
                />
              </Field>
              <Field label={t('assets.proceedsLabel')} htmlFor="dispose-proceeds">
                <TextInput
                  id="dispose-proceeds"
                  type="number"
                  min="0"
                  value={proceeds}
                  onChange={(e) => setProceeds(e.target.value)}
                />
              </Field>

              {disposeError ? (
                <div className="flex items-center gap-2 text-sm text-loss">
                  <TriangleAlert className="size-4 shrink-0" aria-hidden />
                  {disposeError}
                </div>
              ) : null}

              <div className="mt-1 flex justify-end gap-2.5">
                <Button type="button" variant="outline" onClick={() => setDisposeTarget(null)}>
                  {t('common.cancel')}
                </Button>
                <Button type="button" onClick={submitDispose} disabled={disposeAsset.isPending}>
                  {disposeAsset.isPending ? <Spinner /> : null}
                  {t('assets.disposeConfirm')}
                </Button>
              </div>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  )
}
