/**
 * EarnRulesPage — the loyalty back-office surface (Phase 4, ADR 0027): the company-wide
 * points-earning configuration (earn rules, owner/manager write-gated — mirrors /promotions'
 * routing: `canDashboard` in App.tsx), PLUS two simple read-only back-office queries a manager
 * needs day-to-day — a member lookup by phone and a gift-card lookup by code. Routed at /loyalty.
 *
 * Earn rules are CREATE-ONLY (loyalty-service's EarnRuleController has no PATCH — a new rule is a
 * new, higher `rule_version`; the highest-version-wins resolver picks it up automatically), so
 * there is no edit dialog, only "New rule".
 *
 * The create dialog's rate input is a PERCENTAGE (mirrors features/promotions/RuleDialog.tsx's
 * `rateBp = Math.round(Number(ratePercent) * 100)` pattern exactly) — semantically correct here
 * too, since the v1 convention is 1 point = 1 minor unit of the sale currency: "pointsPerMinorBp
 * basis points per minor unit" IS "the percentage of every sale returned as points".
 *
 * Money rule (rule 8): formatMoney for every amount (including a points balance — v1 convention).
 * Strings rule (rule 9): every label is an i18n key, en+id.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CreditCard, Percent, Plus, Search, TriangleAlert, User } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Segmented } from '@/components/ui/Segmented'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatPercent, isoMinorExponent } from '@/lib/money'
import {
  isEarnRuleInvalid,
  isEarnRuleWriteForbidden,
  isGiftCardNotFound,
  isMemberNotFound,
  useCreateEarnRule,
  useEarnRules,
  useLookupGiftCard,
  useLookupMember,
  type EarnRuleCreateInput,
  type EarnRuleProvenance,
  type EarnRuleResponse,
  type GiftCardResponse,
  type MemberResponse,
} from './api'

export function EarnRulesPage() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('loyalty.noCompany')} hint={t('loyalty.noCompanyHint')} />
  }
  return <EarnRulesPageInner company={company} />
}

function EarnRulesPageInner({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = company.baseCurrency

  const rulesQuery = useEarnRules(company)
  const rules = rulesQuery.data ?? []
  const [showCreate, setShowCreate] = useState(false)

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">{t('loyalty.title')}</h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('loyalty.subtitle')}</p>
      </div>

      {/* ---- Earn rules ---- */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <Percent className="size-[18px] text-emerald-2" aria-hidden />
            {t('loyalty.earnRules.title')}
          </h2>
          <Button type="button" size="sm" onClick={() => setShowCreate(true)}>
            <Plus className="size-[14px]" aria-hidden />
            {t('loyalty.earnRules.newRule')}
          </Button>
        </div>

        {rulesQuery.isError ? (
          <Card className="p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('loyalty.earnRules.errors.load')}
          </Card>
        ) : rulesQuery.isLoading ? (
          <ListSkeleton rows={3} />
        ) : rules.length === 0 ? (
          <EmptyState title={t('loyalty.earnRules.empty')} hint={t('loyalty.earnRules.emptyHint')} />
        ) : (
          <div className="flex flex-col gap-2.5">
            {rules.map((rule) => (
              <EarnRuleRow key={rule.id} rule={rule} currency={currency} locale={locale} />
            ))}
          </div>
        )}
      </section>

      {/* ---- Back-office queries ---- */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <MemberLookupSection session={company} currency={currency} locale={locale} />
        <GiftCardLookupSection session={company} locale={locale} />
      </div>

      {showCreate ? (
        <EarnRuleDialog session={company} currency={currency} onClose={() => setShowCreate(false)} />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// EarnRuleRow
// ---------------------------------------------------------------------------

function EarnRuleRow({
  rule,
  currency,
  locale,
}: {
  rule: EarnRuleResponse
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const ratePercent = formatPercent(rule.pointsPerMinorBp / 10000, locale)
  const isOfficial = rule.provenance === 'OFFICIAL'

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-xs font-semibold text-ink-3">{rule.ruleVersion}</span>
            <Badge tone={rule.active ? 'profit' : 'neutral'}>
              {rule.active ? t('loyalty.earnRules.statusActive') : t('loyalty.earnRules.statusInactive')}
            </Badge>
            <Badge tone={isOfficial ? 'profit' : 'amber'}>
              {t(`loyalty.earnRules.provenance.${rule.provenance}`)}
            </Badge>
          </div>
          <p className="mt-1.5 text-sm font-semibold text-ink">
            {t('loyalty.earnRules.rateSummary', { pct: ratePercent })}
          </p>
          {rule.minSaleMinor ? (
            <p className="mt-0.5 text-xs text-ink-3">
              {t('loyalty.earnRules.minSaleLabel')}: {formatMoney(rule.minSaleMinor, currency, locale)}
            </p>
          ) : null}
          <p className="mt-0.5 text-xs text-ink-3">{rule.sourceNote}</p>
          <p className="mt-1 text-xs text-ink-3">
            {t('loyalty.earnRules.effectiveRange', { from: rule.effectiveFrom, to: rule.effectiveTo })}
          </p>
        </div>
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// EarnRuleDialog — create only (no PATCH endpoint; see class doc)
// ---------------------------------------------------------------------------

function EarnRuleDialog({
  session,
  currency,
  onClose,
}: {
  session: CompanySession
  currency: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const createRule = useCreateEarnRule(session)

  const [ratePercent, setRatePercent] = useState('')
  const [minSaleInput, setMinSaleInput] = useState('')
  const [provenance, setProvenance] = useState<EarnRuleProvenance>('ILLUSTRATIVE_PLACEHOLDER')
  const [sourceNote, setSourceNote] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [effectiveTo, setEffectiveTo] = useState('')
  const [error, setError] = useState<string | null>(null)

  const provenanceOptions: { value: EarnRuleProvenance; label: string }[] = [
    { value: 'ILLUSTRATIVE_PLACEHOLDER', label: t('loyalty.earnRules.provenance.ILLUSTRATIVE_PLACEHOLDER') },
    { value: 'OFFICIAL', label: t('loyalty.earnRules.provenance.OFFICIAL') },
  ]

  function toMinor(major: string): number | null {
    if (major.trim() === '') return null
    const n = Number(major)
    if (isNaN(n)) return null
    return Math.round(n * 10 ** isoMinorExponent(currency))
  }

  function validate(): string | null {
    if (ratePercent.trim() === '' || isNaN(Number(ratePercent)) || Number(ratePercent) < 0) {
      return t('loyalty.earnRules.errors.rateRequired')
    }
    if (!sourceNote.trim()) return t('loyalty.earnRules.errors.sourceNoteRequired')
    return null
  }

  async function submit() {
    setError(null)
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    const input: EarnRuleCreateInput = {
      pointsPerMinorBp: Math.round(Number(ratePercent) * 100),
      minSaleMinor: toMinor(minSaleInput),
      provenance,
      sourceNote: sourceNote.trim(),
      effectiveFrom: effectiveFrom || null,
      effectiveTo: effectiveTo || null,
    }
    try {
      await createRule.mutateAsync(input)
      onClose()
    } catch (e) {
      if (isEarnRuleWriteForbidden(e)) {
        setError(t('loyalty.earnRules.errors.forbidden'))
      } else if (isEarnRuleInvalid(e)) {
        setError(t('loyalty.earnRules.errors.invalid'))
      } else {
        setError(t('loyalty.earnRules.errors.generic'))
      }
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('loyalty.earnRules.newRule')}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <Card className="max-h-[90vh] w-full max-w-lg overflow-y-auto p-6">
        <h2 className="mb-4 font-display text-xl font-semibold text-ink">{t('loyalty.earnRules.newRule')}</h2>

        <div className="flex flex-col gap-3.5">
          <Field
            label={t('loyalty.earnRules.fields.rate')}
            htmlFor="earnrule-rate"
            hint={t('loyalty.earnRules.fields.rateHint')}
          >
            <div className="relative">
              <TextInput
                id="earnrule-rate"
                type="number"
                min="0"
                step="0.01"
                value={ratePercent}
                onChange={(e) => setRatePercent(e.target.value)}
                className="pr-9"
              />
              <span className="pointer-events-none absolute right-4 top-1/2 -translate-y-1/2 text-sm text-ink-3">%</span>
            </div>
          </Field>

          <Field
            label={t('loyalty.earnRules.fields.minSale', { currency })}
            htmlFor="earnrule-min-sale"
            hint={t('loyalty.earnRules.fields.minSaleHint')}
          >
            <TextInput
              id="earnrule-min-sale"
              type="number"
              min="0"
              step="any"
              value={minSaleInput}
              onChange={(e) => setMinSaleInput(e.target.value)}
            />
          </Field>

          <div>
            <span className="mb-1.5 block text-sm font-medium text-ink">{t('loyalty.earnRules.fields.provenance')}</span>
            <Segmented
              options={provenanceOptions}
              value={provenance}
              onChange={setProvenance}
              ariaLabel={t('loyalty.earnRules.fields.provenance')}
            />
          </div>

          <Field label={t('loyalty.earnRules.fields.sourceNote')} htmlFor="earnrule-source-note" hint={t('loyalty.earnRules.fields.sourceNoteHint')}>
            <TextInput
              id="earnrule-source-note"
              value={sourceNote}
              onChange={(e) => setSourceNote(e.target.value)}
              placeholder={t('loyalty.earnRules.fields.sourceNotePlaceholder')}
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={t('loyalty.earnRules.fields.effectiveFrom')} htmlFor="earnrule-effective-from">
              <TextInput
                id="earnrule-effective-from"
                type="date"
                value={effectiveFrom}
                onChange={(e) => setEffectiveFrom(e.target.value)}
              />
            </Field>
            <Field label={t('loyalty.earnRules.fields.effectiveTo')} htmlFor="earnrule-effective-to">
              <TextInput
                id="earnrule-effective-to"
                type="date"
                value={effectiveTo}
                onChange={(e) => setEffectiveTo(e.target.value)}
              />
            </Field>
          </div>

          {error ? (
            <div className="flex items-center gap-2 text-sm text-loss" role="alert">
              <TriangleAlert className="size-4 shrink-0" aria-hidden />
              {error}
            </div>
          ) : null}

          <div className="mt-1 flex justify-end gap-2.5">
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="button" onClick={submit} disabled={createRule.isPending}>
              {createRule.isPending ? <Spinner /> : t('loyalty.earnRules.create')}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// MemberLookupSection — a simple back-office phone → balance query
// ---------------------------------------------------------------------------

function MemberLookupSection({
  session,
  currency,
  locale,
}: {
  session: CompanySession
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const lookup = useLookupMember(session)
  const [phone, setPhone] = useState('')
  const [result, setResult] = useState<MemberResponse | null>(null)
  const [notFound, setNotFound] = useState(false)

  function submit() {
    const trimmed = phone.trim()
    if (!trimmed) return
    setResult(null)
    setNotFound(false)
    lookup.mutate(trimmed, {
      onSuccess: (found) => setResult(found),
      onError: (err) => setNotFound(isMemberNotFound(err)),
    })
  }

  return (
    <Card className="p-5">
      <h2 className="mb-1 flex items-center gap-2 font-display text-base font-semibold text-ink">
        <User className="size-[16px] text-emerald-2" aria-hidden />
        {t('loyalty.memberLookup.title')}
      </h2>
      <p className="mb-3 text-xs text-ink-3">{t('loyalty.memberLookup.subtitle')}</p>
      <div className="flex items-center gap-2">
        <input
          type="tel"
          value={phone}
          onChange={(e) => {
            setPhone(e.target.value)
            setResult(null)
            setNotFound(false)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t('pos.loyalty.member.phonePlaceholder')}
          aria-label={t('pos.loyalty.member.phoneLabel')}
          className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 font-mono text-sm text-ink placeholder:font-sans placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
        <Button type="button" onClick={submit} disabled={phone.trim() === '' || lookup.isPending}>
          {lookup.isPending ? <Spinner /> : <Search className="size-4" />}
        </Button>
      </div>

      {result ? (
        <div className="mt-4 flex items-center justify-between rounded-xl border border-line bg-paper px-4 py-3">
          <div>
            <p className="text-sm font-semibold text-ink">{result.displayName}</p>
            <p className="font-mono text-xs text-ink-3">{result.phoneTail}</p>
          </div>
          <p className="tnum font-mono text-sm font-bold text-emerald-2">
            {formatMoney(result.pointsBalance, currency, locale)}
          </p>
        </div>
      ) : notFound ? (
        <p className="mt-3 text-xs text-ink-3">{t('pos.loyalty.member.notFound')}</p>
      ) : null}
    </Card>
  )
}

// ---------------------------------------------------------------------------
// GiftCardLookupSection — a simple back-office code → state/balance query
// ---------------------------------------------------------------------------

function GiftCardLookupSection({ session, locale }: { session: CompanySession; locale: string }) {
  const { t } = useTranslation()
  const lookup = useLookupGiftCard(session)
  const [code, setCode] = useState('')
  const [result, setResult] = useState<GiftCardResponse | null>(null)
  const [notFound, setNotFound] = useState(false)

  function submit() {
    const trimmed = code.trim()
    if (!trimmed) return
    setResult(null)
    setNotFound(false)
    lookup.mutate(trimmed, {
      onSuccess: (found) => setResult(found),
      onError: (err) => setNotFound(isGiftCardNotFound(err)),
    })
  }

  return (
    <Card className="p-5">
      <h2 className="mb-1 flex items-center gap-2 font-display text-base font-semibold text-ink">
        <CreditCard className="size-[16px] text-emerald-2" aria-hidden />
        {t('loyalty.giftCardLookup.title')}
      </h2>
      <p className="mb-3 text-xs text-ink-3">{t('loyalty.giftCardLookup.subtitle')}</p>
      <div className="flex items-center gap-2">
        <input
          type="text"
          value={code}
          onChange={(e) => {
            setCode(e.target.value.toUpperCase())
            setResult(null)
            setNotFound(false)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t('pos.loyalty.giftCard.codePlaceholder')}
          aria-label={t('pos.loyalty.giftCard.codeLabel')}
          className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 font-mono text-sm uppercase tracking-wide text-ink placeholder:font-sans placeholder:normal-case placeholder:tracking-normal placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
        <Button type="button" onClick={submit} disabled={code.trim() === '' || lookup.isPending}>
          {lookup.isPending ? <Spinner /> : <Search className="size-4" />}
        </Button>
      </div>

      {result ? (
        <div className="mt-4 flex items-center justify-between rounded-xl border border-line bg-paper px-4 py-3">
          <div>
            <p className="font-mono text-sm font-semibold text-ink">{result.code}</p>
            <Badge tone={result.state === 'ACTIVE' ? 'profit' : 'neutral'} className="mt-1">
              {t(`pos.loyalty.giftCard.state.${result.state}`)}
            </Badge>
          </div>
          <p className="tnum font-mono text-sm font-bold text-emerald-2">
            {formatMoney(result.balanceMinor, result.currency, locale)}
          </p>
        </div>
      ) : notFound ? (
        <p className="mt-3 text-xs text-ink-3">{t('pos.loyalty.giftCard.notFound')}</p>
      ) : null}
    </Card>
  )
}
