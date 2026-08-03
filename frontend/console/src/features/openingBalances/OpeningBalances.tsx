/**
 * Opening balances & business migration (ADR 0037) — a standalone, once-only page: enter an
 * existing (or new) business's starting balance sheet so it can be onboarded onto Native. Owner/
 * manager only, routed exactly like /assets and /platform-settlements (App.tsx `canDashboard`).
 *
 * On mount, GETs the recorded opening balance sheet: 200 → a read-only summary (the form is never
 * shown again — recording is once-only per company); 404 → the form. Submit posts, separately, one
 * `POST /api/v1/assets/opening` per fixed-asset row FIRST (each posts its OWN self-balancing entry —
 * no cash), and ONLY once every asset has landed, ONE balanced opening entry (the fixed balance-sheet
 * lines below) LAST. That ordering matters: the main entry's success invalidates the opening-balance
 * GET, which flips this page straight to the read-only summary and unmounts the form — posting it
 * last means that swap only happens once the whole migration has actually landed, so an asset
 * failure mid-batch is always caught on a form that is still mounted, with a retry available (see
 * the `submit` function's docs). A single as-of date drives every posting. The "Opening Balance
 * Equity (balancing plug)" readout is a LIVE client-side estimate (see {@link computePlugMinor});
 * the server computes and returns the authoritative `plugMinor` once the sheet is actually recorded.
 *
 * Strings rule (rule 9): every label is an i18n key, en+id. Money rule 8: minor units on the wire,
 * formatted via `formatMoney` (Intl, locale-aware) and parsed exponent-aware for input — never a
 * currency toggle (the company's base currency, read from session, is the only currency this form
 * ever uses).
 */
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ArrowRight, Check, Plus, Trash2, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { assetErrorKey } from '@/features/assets/format'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import { cn } from '@/lib/cn'
import { openingBalanceErrorKey } from './format'
import {
  isOpeningBalanceAlreadyRecorded,
  isOpeningBalanceNotRecorded,
  openingBalanceKey,
  useOpeningBalance,
  useRecordOpeningBalances,
  useRegisterBroughtForwardAsset,
  type OpeningBalanceResponse,
} from './api'
import {
  LINE_FIELDS,
  LINE_SECTIONS,
  assetRowError,
  assetRowNetBookValueMinor,
  assetRowStarted,
  buildAssetRegistrationBody,
  buildOpeningBalanceLines,
  computePlugMinor,
  newAssetRow,
  type AssetRowInput,
} from './lines'

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function formatDate(iso: string, locale: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d)
}

function formatInstant(iso: string, locale: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}

export function OpeningBalances() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('openingBalances.noCompany')} hint={t('openingBalances.noCompanyHint')} />
  }
  return <OpeningBalancesInner company={company} />
}

function OpeningBalancesInner({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [justRecorded, setJustRecorded] = useState(false)

  const query = useOpeningBalance({
    companyId: company.companyId,
    actor: company.actor,
    enabled: true,
  })
  const notRecorded = isOpeningBalanceNotRecorded(query.error)

  return (
    <div className="mx-auto flex max-w-[820px] flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('openingBalances.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('openingBalances.subtitle')}</p>
      </div>

      {query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : query.data ? (
        <RecordedSummary data={query.data} locale={locale} justRecorded={justRecorded} />
      ) : notRecorded ? (
        <OpeningBalanceForm
          company={company}
          locale={locale}
          onRecorded={() => setJustRecorded(true)}
        />
      ) : (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('openingBalances.error')}
        </Card>
      )}
    </div>
  )
}

function Row({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-line py-2.5 last:border-0">
      <dt className="text-sm text-ink-3">{label}</dt>
      <dd className={cn('tnum text-right text-sm font-mono font-semibold', tone ?? 'text-ink')}>
        {value}
      </dd>
    </div>
  )
}

function RecordedSummary({
  data,
  locale,
  justRecorded,
}: {
  data: OpeningBalanceResponse
  locale: string
  justRecorded: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-[18px]">
      {justRecorded ? (
        <Card className="flex items-start gap-3 border-profit/25 bg-tint-profit p-4">
          <Check className="mt-0.5 size-4 shrink-0 text-profit-ink" />
          <div>
            <p className="text-sm font-semibold text-ink">{t('openingBalances.success.title')}</p>
            <p className="text-sm text-ink-2">{t('openingBalances.success.body')}</p>
          </div>
        </Card>
      ) : null}

      <Card className="p-6">
        <div className="mb-4 flex items-center justify-between gap-3">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('openingBalances.recorded.title')}
          </h2>
          <Badge tone="profit">{t('openingBalances.recorded.badge')}</Badge>
        </div>

        <dl>
          <Row label={t('openingBalances.recorded.asOfDate')} value={formatDate(data.asOfDate, locale)} />
          <Row label={t('openingBalances.recorded.period')} value={formatPeriod(data.period, locale)} />
          <Row
            label={t('openingBalances.recorded.total')}
            value={formatMoney(data.totalMinor, data.currency, locale)}
          />
          <Row
            label={t('openingBalances.recorded.plug')}
            value={formatMoney(data.plugMinor, data.currency, locale)}
            tone={data.plugMinor < 0 ? 'text-loss' : 'text-ink'}
          />
          <Row
            label={t('openingBalances.recorded.recordedAt')}
            value={formatInstant(data.recordedAt, locale)}
          />
        </dl>

        <p className="mt-4 text-xs leading-relaxed text-ink-3">{t('openingBalances.recorded.hint')}</p>

        <div className="mt-5">
          <Link to="/statements/balance-sheet">
            <Button type="button" variant="outline">
              {t('openingBalances.recorded.viewBalanceSheet')}
              <ArrowRight className="size-4" aria-hidden />
            </Button>
          </Link>
        </div>
      </Card>
    </div>
  )
}

function MoneyLineField({
  code,
  label,
  step,
  value,
  onChange,
}: {
  code: string
  label: string
  step: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Field label={label} htmlFor={`ob-line-${code}`}>
      <TextInput
        id={`ob-line-${code}`}
        type="number"
        min="0"
        step={step}
        placeholder="0"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </Field>
  )
}

function AssetRowFields({
  row,
  index,
  step,
  currency,
  locale,
  removable,
  onChange,
  onRemove,
}: {
  row: AssetRowInput
  index: number
  step: string
  currency: string
  locale: string
  removable: boolean
  onChange: (patch: Partial<AssetRowInput>) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const error = assetRowStarted(row) ? assetRowError(row, currency) : null
  const netBookValueMinor = assetRowNetBookValueMinor(row, currency)

  return (
    <div className="rounded-xl border border-line p-3">
      <div className="grid grid-cols-1 items-end gap-2.5 sm:grid-cols-[1.3fr_1fr_1fr_0.8fr_1fr_auto]">
        <Field label={t('openingBalances.form.assetNameLabel')} htmlFor={`ob-asset-name-${row.key}`}>
          <TextInput
            id={`ob-asset-name-${row.key}`}
            value={row.name}
            onChange={(e) => onChange({ name: e.target.value })}
            placeholder={t('openingBalances.form.assetNamePlaceholder')}
          />
        </Field>
        <Field label={t('openingBalances.form.assetCostLabel')} htmlFor={`ob-asset-cost-${row.key}`}>
          <TextInput
            id={`ob-asset-cost-${row.key}`}
            type="number"
            min="0"
            step={step}
            value={row.cost}
            onChange={(e) => onChange({ cost: e.target.value })}
          />
        </Field>
        <Field
          label={t('openingBalances.form.assetAccumulatedLabel')}
          htmlFor={`ob-asset-accumulated-${row.key}`}
        >
          <TextInput
            id={`ob-asset-accumulated-${row.key}`}
            type="number"
            min="0"
            step={step}
            value={row.accumulated}
            onChange={(e) => onChange({ accumulated: e.target.value })}
          />
        </Field>
        <Field label={t('openingBalances.form.assetLifeLabel')} htmlFor={`ob-asset-months-${row.key}`}>
          <TextInput
            id={`ob-asset-months-${row.key}`}
            type="number"
            min="1"
            max="600"
            step="1"
            value={row.months}
            onChange={(e) => onChange({ months: e.target.value })}
          />
        </Field>
        <Field label={t('openingBalances.form.assetNetBookValue')}>
          <p className="tnum flex h-[52px] items-center rounded-xl border border-line bg-paper px-3.5 font-mono text-sm text-ink">
            {formatMoney(netBookValueMinor, currency, locale)}
          </p>
        </Field>
        <button
          type="button"
          aria-label={t('openingBalances.form.removeAsset', { n: index + 1 })}
          onClick={onRemove}
          disabled={!removable}
          className="grid size-10 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-tint-loss hover:text-loss disabled:cursor-not-allowed disabled:opacity-40"
        >
          <Trash2 className="size-4" />
        </button>
      </div>
      {error ? (
        <p className="mt-2 text-xs text-loss" role="alert">
          {t(`openingBalances.form.assetErrors.${error}` as Parameters<typeof t>[0])}
        </p>
      ) : null}
    </div>
  )
}

function OpeningBalanceForm({
  company,
  locale,
  onRecorded,
}: {
  company: CompanySession
  locale: string
  onRecorded: () => void
}) {
  const { t } = useTranslation()
  const currency = company.baseCurrency
  const exponent = isoMinorExponent(currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const step = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()

  const [asOfDate, setAsOfDate] = useState(todayIso())
  const [lineValues, setLineValues] = useState<Record<string, string>>({})
  const [assetRows, setAssetRows] = useState<AssetRowInput[]>([newAssetRow()])
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [progress, setProgress] = useState<string | null>(null)
  // ONE per-submit id, minted once for the life of this mounted form and REUSED across a retry of
  // the SAME submit (see api.ts's RecordOpeningBalancesVariables docs) — the main entry's key is the
  // id itself; each fixed-asset row's key is the id suffixed by the row's OWN stable `key` (never
  // its array index — index-keying would collide across a retry after the user adds/removes/
  // reorders rows, silently replaying the wrong row's key instead of registering the one that
  // failed). A retry after a partial failure replays every already-succeeded post instead of
  // double-posting it. A fresh id is only ever needed on a fresh mount (this form unmounts for good
  // once recording succeeds).
  const [submitId] = useState(() => crypto.randomUUID())

  const queryClient = useQueryClient()
  const recordOpeningBalances = useRecordOpeningBalances(company)
  const registerAsset = useRegisterBroughtForwardAsset(company)
  const submitting = recordOpeningBalances.isPending || registerAsset.isPending

  const lines = buildOpeningBalanceLines(lineValues, currency)
  const assetRowErrors = assetRows.map((row) =>
    assetRowStarted(row) ? assetRowError(row, currency) : null,
  )
  const hasInvalidAssetRow = assetRowErrors.some((e) => e != null)
  const plugMinor = computePlugMinor(lineValues, assetRows, currency)
  const canSubmit = lines.length > 0 && !hasInvalidAssetRow && !submitting

  function setLineValue(code: string, value: string) {
    setLineValues((prev) => ({ ...prev, [code]: value }))
  }
  function updateAssetRow(key: string, patch: Partial<AssetRowInput>) {
    setAssetRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  }
  function addAssetRow() {
    setAssetRows((prev) => [...prev, newAssetRow()])
  }
  function removeAssetRow(key: string) {
    setAssetRows((prev) => (prev.length > 1 ? prev.filter((r) => r.key !== key) : prev))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setSubmitError(null)

    // Brought-forward assets are registered FIRST, the main opening-balance entry LAST. The main
    // POST's success invalidates the opening-balance GET, which flips the page to the read-only
    // RecordedSummary and unmounts this form (once-only per company — there is no going back to add
    // a missed asset afterwards). Posting it last means that swap only happens once every asset has
    // already landed, so a failure mid-batch is always caught here, on a form that is STILL mounted
    // (the GET is still 404), with a retry available.
    for (const row of assetRows) {
      const body = buildAssetRegistrationBody(row, asOfDate, currency)
      if (!body) continue // a blank row — nothing to register
      try {
        setProgress(t('openingBalances.form.postingAsset', { name: row.name.trim() }))
        // The idempotency suffix is the row's STABLE `key` (crypto.randomUUID(), set once when the
        // row was added — see lines.ts's newAssetRow), never its array index: the user can add,
        // remove, or reorder rows between a failed attempt and a retry, which would shift indices
        // and make an index-keyed retry replay the WRONG row's key — silently re-confirming an
        // already-registered asset while never registering the one that actually failed.
        await registerAsset.mutateAsync({
          ...body,
          idempotencyKey: `${submitId}-asset-${row.key}`,
        })
      } catch (err) {
        setProgress(null)
        setSubmitError(t(assetErrorKey(err)))
        return
      }
    }

    try {
      setProgress(t('openingBalances.form.postingBalances'))
      await recordOpeningBalances.mutateAsync({
        asOfDate,
        currency,
        lines,
        idempotencyKey: submitId,
      })
    } catch (err) {
      setProgress(null)
      if (isOpeningBalanceAlreadyRecorded(err)) {
        // A race: another request recorded the sheet between this page's GET and this submit.
        // Refetch instead of leaving the user on a form that can never succeed — the parent swaps
        // to the read-only "already recorded" state once the GET resolves 200.
        void queryClient.invalidateQueries({ queryKey: openingBalanceKey(company.companyId) })
        return
      }
      setSubmitError(t(openingBalanceErrorKey(err)))
      return
    }

    setProgress(null)
    onRecorded()
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <Card className="p-6">
        <Field
          label={t('openingBalances.form.asOfDateLabel')}
          htmlFor="ob-asof"
          hint={t('openingBalances.form.asOfDateHint')}
        >
          <TextInput
            id="ob-asof"
            type="date"
            max={todayIso()}
            value={asOfDate}
            onChange={(e) => setAsOfDate(e.target.value)}
          />
        </Field>
      </Card>

      {LINE_SECTIONS.map((section) => (
        <Card key={section.titleKey} className="p-6">
          <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t(`openingBalances.form.${section.titleKey}` as Parameters<typeof t>[0])}
          </h2>
          <div className="grid gap-4 sm:grid-cols-2">
            {section.codes.map((code) => {
              const field = LINE_FIELDS.find((f) => f.code === code)
              if (!field) return null
              return (
                <MoneyLineField
                  key={code}
                  code={code}
                  label={t(`openingBalances.form.${field.labelKey}` as Parameters<typeof t>[0])}
                  step={step}
                  value={lineValues[code] ?? ''}
                  onChange={(value) => setLineValue(code, value)}
                />
              )
            })}
          </div>
        </Card>
      ))}

      <Card className="p-6">
        <div className="mb-3 flex items-center justify-between">
          <div>
            <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('openingBalances.form.sectionAssets')}
            </h2>
            <p className="mt-1 text-xs text-ink-3">{t('openingBalances.form.sectionAssetsHint')}</p>
          </div>
          <Button type="button" variant="outline" size="sm" onClick={addAssetRow}>
            <Plus className="size-4" />
            {t('openingBalances.form.addAsset')}
          </Button>
        </div>

        <div className="flex flex-col gap-3">
          {assetRows.map((row, index) => (
            <AssetRowFields
              key={row.key}
              row={row}
              index={index}
              step={step}
              currency={currency}
              locale={locale}
              removable={assetRows.length > 1}
              onChange={(patch) => updateAssetRow(row.key, patch)}
              onRemove={() => removeAssetRow(row.key)}
            />
          ))}
        </div>
      </Card>

      <Card className="p-6">
        <Field
          label={t('openingBalances.form.plugLabel')}
          htmlFor="ob-plug"
          hint={t('openingBalances.form.plugHint')}
        >
          <div
            id="ob-plug"
            className={cn(
              'tnum flex h-[52px] items-center rounded-xl border border-line bg-paper px-4 font-mono text-[15px] font-semibold',
              plugMinor < 0 ? 'text-loss' : 'text-ink',
            )}
          >
            {formatMoney(plugMinor, currency, locale)}
          </div>
        </Field>
      </Card>

      {lines.length === 0 ? (
        <p className="text-sm text-ink-3">{t('openingBalances.form.emptyWarning')}</p>
      ) : null}

      {submitError ? (
        <p className="text-sm text-loss" role="alert">
          {submitError}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center justify-end gap-3">
        {progress ? <span className="text-sm text-ink-3">{progress}</span> : null}
        <Button type="submit" disabled={!canSubmit}>
          {submitting ? <Spinner /> : null}
          {submitting ? t('openingBalances.form.submitting') : t('openingBalances.form.submit')}
        </Button>
      </div>
    </form>
  )
}
