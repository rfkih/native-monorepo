/**
 * Statutory-rule administration (Track P phase P2, ADR 0031): the rules table, the one-click
 * "Activate official dataset" action, and a per-rule detail drawer with the human-verification
 * override dialog (the TER activation path). Provenance badges are the honest signal — amber for
 * ILLUSTRATIVE_PLACEHOLDER, green (profit tone) for OFFICIAL — never hidden, mirroring the
 * persistent illustrative banner in the parent PayrollTab.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronRight, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import {
  useOverrideStatutoryRule,
  useSeedOfficial,
  useStatutoryRuleDetail,
  useStatutoryRules,
  type StatutoryRuleRow,
} from './api'

/** The one dataset this build ships (ID-2026.1, ADR 0031) — activated by rule_key/version pair. */
const OFFICIAL_DATASET_VERSION = 'ID-2026.1'

export function PayrollSetupTab({
  companyId,
  actor,
  locale,
}: {
  companyId: string
  actor: string
  locale: string
}) {
  const { t } = useTranslation()
  const [selectedRuleKey, setSelectedRuleKey] = useState<string | null>(null)
  const [confirmActivate, setConfirmActivate] = useState(false)

  const rulesQuery = useStatutoryRules({ companyId, actor, enabled: true })
  const seedOfficial = useSeedOfficial({ companyId, actor })

  const rules = rulesQuery.data ?? []

  function activate() {
    seedOfficial.mutate(OFFICIAL_DATASET_VERSION, { onSuccess: () => setConfirmActivate(false) })
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-[15px] font-semibold text-ink">{t('payrollSetup.title')}</p>
          <p className="mt-0.5 text-sm text-ink-3">{t('payrollSetup.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setConfirmActivate(true)}>
          <ShieldCheck className="size-4" aria-hidden="true" />
          {t('payrollSetup.activate.button', { version: OFFICIAL_DATASET_VERSION })}
        </Button>
      </div>

      {seedOfficial.isSuccess && seedOfficial.data ? (
        <Card className="flex items-start gap-3 border-emerald/30 bg-emerald-tint/40 p-4">
          <ShieldCheck className="mt-0.5 size-5 shrink-0 text-emerald-2" aria-hidden="true" />
          <p className="text-sm text-emerald-2">
            {t('payrollSetup.activate.success', {
              version: seedOfficial.data.datasetVersion,
              inserted: seedOfficial.data.rulesInserted,
              closed: seedOfficial.data.rulesClosed,
              updated: seedOfficial.data.componentsUpdated,
            })}
          </p>
        </Card>
      ) : null}
      {seedOfficial.isError ? (
        <p className="text-sm text-loss">{t('payrollSetup.activate.error')}</p>
      ) : null}

      {rulesQuery.isLoading ? (
        <ListSkeleton rows={5} />
      ) : rules.length === 0 ? (
        <EmptyState
          title={t('payrollSetup.table.empty')}
          hint={t('payrollSetup.table.emptyHint')}
        />
      ) : (
        <Card className="overflow-x-auto rounded-[20px] p-2.5">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-[11px] uppercase tracking-wider text-ink-3">
                <th className="px-3 py-2 font-semibold">{t('payrollSetup.table.ruleKey')}</th>
                <th className="px-3 py-2 font-semibold">{t('payrollSetup.table.version')}</th>
                <th className="px-3 py-2 font-semibold">{t('payrollSetup.table.calcType')}</th>
                <th className="px-3 py-2 font-semibold">{t('payrollSetup.table.provenance')}</th>
                <th className="px-3 py-2 font-semibold">{t('payrollSetup.table.effective')}</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => (
                <RuleRow
                  key={`${rule.ruleKey}-${rule.ruleVersion}-${rule.effectiveFrom}`}
                  rule={rule}
                  locale={locale}
                  onOpen={() => setSelectedRuleKey(rule.ruleKey)}
                />
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {confirmActivate ? (
        <DialogOverlay onClose={() => setConfirmActivate(false)}>
          <div className="space-y-4">
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('payrollSetup.activate.confirmTitle')}
            </h2>
            <p className="text-sm text-ink-2">
              {t('payrollSetup.activate.confirmBody', { version: OFFICIAL_DATASET_VERSION })}
            </p>
            {seedOfficial.isError ? (
              <p className="text-sm text-loss">{t('payrollSetup.activate.error')}</p>
            ) : null}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setConfirmActivate(false)}>
                {t('common.cancel')}
              </Button>
              <Button type="button" onClick={activate} disabled={seedOfficial.isPending}>
                {seedOfficial.isPending
                  ? t('payrollSetup.activate.activating')
                  : t('payrollSetup.activate.button', { version: OFFICIAL_DATASET_VERSION })}
              </Button>
            </div>
          </div>
        </DialogOverlay>
      ) : null}

      {selectedRuleKey ? (
        <RuleDetailDrawer
          companyId={companyId}
          actor={actor}
          locale={locale}
          ruleKey={selectedRuleKey}
          onClose={() => setSelectedRuleKey(null)}
        />
      ) : null}
    </div>
  )
}

function RuleRow({
  rule,
  locale,
  onOpen,
}: {
  rule: StatutoryRuleRow
  locale: string
  onOpen: () => void
}) {
  const { t } = useTranslation()
  return (
    <tr
      className={cn(
        'cursor-pointer border-t border-line/60 transition-colors hover:bg-hover',
        !rule.active && 'opacity-50',
      )}
      onClick={onOpen}
    >
      <td className="px-3 py-2.5 font-mono text-xs font-semibold text-ink">{rule.ruleKey}</td>
      <td className="px-3 py-2.5 font-mono text-xs text-ink-2">{rule.ruleVersion}</td>
      <td className="px-3 py-2.5 text-xs text-ink-2">{rule.calcType}</td>
      <td className="px-3 py-2.5">
        <ProvenanceBadge provenance={rule.provenance} />
        {!rule.active ? (
          <span className="ml-1.5 text-[11px] text-ink-3">
            {t('payrollSetup.table.superseded')}
          </span>
        ) : null}
      </td>
      <td className="px-3 py-2.5 text-xs text-ink-2">
        {formatEffectiveRange(rule.effectiveFrom, rule.effectiveTo, locale, t)}
      </td>
      <td className="px-3 py-2.5 text-right">
        <ChevronRight className="ml-auto size-4 text-ink-3" aria-hidden="true" />
      </td>
    </tr>
  )
}

function ProvenanceBadge({
  provenance,
}: {
  provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'
}) {
  const { t } = useTranslation()
  return provenance === 'OFFICIAL' ? (
    <Badge tone="profit">{t('payrollSetup.provenance.official')}</Badge>
  ) : (
    <Badge tone="amber">{t('payrollSetup.provenance.illustrative')}</Badge>
  )
}

function formatEffectiveRange(
  from: string,
  to: string,
  locale: string,
  t: (key: string) => string,
): string {
  const formatter = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
  const fromLabel = formatter.format(new Date(from))
  const toLabel = to === '9999-12-31' ? t('payrollSetup.table.openEnded') : formatter.format(new Date(to))
  return `${fromLabel} → ${toLabel}`
}

// ---------------------------------------------------------------------------
// Detail drawer + override dialog
// ---------------------------------------------------------------------------

function RuleDetailDrawer({
  companyId,
  actor,
  locale,
  ruleKey,
  onClose,
}: {
  companyId: string
  actor: string
  locale: string
  ruleKey: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [overrideOpen, setOverrideOpen] = useState(false)
  const detailQuery = useStatutoryRuleDetail({ companyId, actor, ruleKey, enabled: true })
  const detail = detailQuery.data

  return (
    <DialogOverlay onClose={onClose}>
      <div className="max-h-[80vh] space-y-4 overflow-y-auto">
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-display text-lg font-semibold text-ink">{ruleKey}</h2>
          {detail ? <ProvenanceBadge provenance={detail.provenance} /> : null}
        </div>

        {detailQuery.isLoading ? (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-x-4 gap-y-2">
              <Skeleton className="h-3.5 w-16" />
              <Skeleton className="h-3.5 w-20" />
              <Skeleton className="h-3.5 w-20" />
              <Skeleton className="h-3.5 w-24" />
              <Skeleton className="h-3.5 w-16" />
              <Skeleton className="h-3.5 w-28" />
            </div>
            <div>
              <Skeleton className="h-3 w-24" />
              <Skeleton className="mt-1.5 h-4 w-full" />
            </div>
            <Skeleton className="h-20 rounded-xl" />
          </div>
        ) : !detail ? (
          <p className="text-sm text-loss">{t('payrollSetup.detail.notFound')}</p>
        ) : (
          <>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="text-ink-3">{t('payrollSetup.detail.calcType')}</dt>
              <dd className="text-ink">{detail.calcType}</dd>
              <dt className="text-ink-3">{t('payrollSetup.detail.ruleVersion')}</dt>
              <dd className="font-mono text-ink">{detail.ruleVersion}</dd>
              <dt className="text-ink-3">{t('payrollSetup.detail.effective')}</dt>
              <dd className="text-ink">
                {formatEffectiveRange(detail.effectiveFrom, detail.effectiveTo, locale, t)}
              </dd>
            </dl>

            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-ink-3">
                {t('payrollSetup.detail.sourceNote')}
              </p>
              <p className="mt-1 text-sm leading-relaxed text-ink-2">{detail.sourceNote}</p>
            </div>

            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-ink-3">
                {t('payrollSetup.detail.params')}
              </p>
              <pre className="mt-1 max-h-64 overflow-auto rounded-xl border border-line bg-paper p-3 font-mono text-xs text-ink-2">
                {formatJson(detail.paramsJson)}
              </pre>
            </div>

            {detail.provenance === 'ILLUSTRATIVE_PLACEHOLDER' ? (
              <p className="flex items-start gap-2 text-xs leading-relaxed text-amber">
                <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
                {t('payrollSetup.detail.illustrativeHint')}
              </p>
            ) : null}
          </>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          {detail ? (
            <Button type="button" onClick={() => setOverrideOpen(true)}>
              {t('payrollSetup.override.open')}
            </Button>
          ) : null}
        </div>
      </div>

      {overrideOpen && detail ? (
        <OverrideDialog
          companyId={companyId}
          actor={actor}
          ruleKey={ruleKey}
          current={detail}
          onClose={() => setOverrideOpen(false)}
        />
      ) : null}
    </DialogOverlay>
  )
}

function formatJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function OverrideDialog({
  companyId,
  actor,
  ruleKey,
  current,
  onClose,
}: {
  companyId: string
  actor: string
  ruleKey: string
  current: { paramsJson: string; sourceNote: string; provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL' }
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [paramsJson, setParamsJson] = useState(() => formatJson(current.paramsJson))
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [sourceNote, setSourceNote] = useState('')
  const [provenance, setProvenance] = useState<'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'>(
    current.provenance,
  )
  const override = useOverrideStatutoryRule({ companyId, actor })

  function submit() {
    override.mutate(
      { ruleKey, body: { paramsJson, effectiveFrom, sourceNote, provenance } },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="max-h-[80vh] space-y-4 overflow-y-auto">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('payrollSetup.override.title', { ruleKey })}
        </h2>

        <Field label={t('payrollSetup.override.paramsLabel')} hint={t('payrollSetup.override.paramsHint')}>
          <textarea
            value={paramsJson}
            onChange={(e) => setParamsJson(e.target.value)}
            rows={10}
            spellCheck={false}
            className="w-full rounded-xl border border-line bg-surface p-3 font-mono text-xs text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
        </Field>

        <Field label={t('payrollSetup.override.effectiveFromLabel')} htmlFor="override-effective-from">
          <TextInput
            id="override-effective-from"
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
          />
        </Field>

        <Field
          label={t('payrollSetup.override.sourceNoteLabel')}
          hint={t('payrollSetup.override.sourceNoteHint')}
          htmlFor="override-source-note"
        >
          <TextInput
            id="override-source-note"
            value={sourceNote}
            onChange={(e) => setSourceNote(e.target.value)}
          />
        </Field>

        <Field label={t('payrollSetup.override.provenanceLabel')} htmlFor="override-provenance">
          <Select
            id="override-provenance"
            value={provenance}
            onChange={(e) =>
              setProvenance(e.target.value as 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL')
            }
          >
            <option value="ILLUSTRATIVE_PLACEHOLDER">
              {t('payrollSetup.provenance.illustrative')}
            </option>
            <option value="OFFICIAL">{t('payrollSetup.provenance.official')}</option>
          </Select>
        </Field>

        {override.isError ? (
          <p className="text-sm text-loss">{t('payrollSetup.override.error')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            onClick={submit}
            disabled={override.isPending || !effectiveFrom || !sourceNote || !paramsJson}
          >
            {override.isPending ? t('payrollSetup.override.saving') : t('payrollSetup.override.submit')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}
