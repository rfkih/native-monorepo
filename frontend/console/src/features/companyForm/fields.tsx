/**
 * The React step components shared by the public sign-up and the in-app add-company wizard.
 * Keeping the Company step, the Region step, and the review here means both flows render the same
 * fields, wording, inputs, and immutable-currency treatment — they cannot drift. See
 * `./companyForm` for the framework-free pieces. All copy comes from the `signup.*` namespace.
 */
import { useTranslation } from 'react-i18next'
import { Lock, Pencil } from 'lucide-react'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Select } from '@/components/ui/Select'
import { Badge } from '@/components/ui/Badge'
import { cn } from '@/lib/cn'
import { countryName, countryOptions, derivedCurrency } from '@/lib/countries'
import {
  COMPANY_STEP,
  LANGS,
  REGION_STEP,
  VERTICALS,
  symbolOf,
  type CompanyBasics,
  type ReviewRow,
} from './companyForm'

/** Company step — company name, first business name, and the vertical. */
export function CompanyFields({
  companyName,
  firstBusinessName,
  vertical,
  onCompanyName,
  onFirstBusinessName,
  onVertical,
  companyNameError,
  firstBusinessNameError,
  autoFocus = true,
}: {
  companyName: string
  firstBusinessName: string
  vertical: string
  onCompanyName: (value: string) => void
  onFirstBusinessName: (value: string) => void
  onVertical: (value: string) => void
  companyNameError?: string
  firstBusinessNameError?: string
  autoFocus?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="space-y-6">
      <Field
        label={t('signup.companyName')}
        htmlFor="companyName"
        hint={t('signup.companyNameHint')}
        error={companyNameError}
      >
        <TextInput
          id="companyName"
          autoFocus={autoFocus}
          autoComplete="organization"
          value={companyName}
          onChange={(e) => onCompanyName(e.target.value)}
          placeholder={t('signup.companyNamePlaceholder')}
        />
      </Field>
      <Field
        label={t('signup.firstBusinessName')}
        htmlFor="bizName"
        error={firstBusinessNameError}
      >
        <TextInput
          id="bizName"
          value={firstBusinessName}
          onChange={(e) => onFirstBusinessName(e.target.value)}
          placeholder={t('signup.firstBusinessNamePlaceholder')}
        />
      </Field>
      <Field label={t('signup.vertical')} hint={t('signup.verticalHint')}>
        <ChoiceCards
          name="vertical"
          columns={1}
          value={vertical}
          onChange={onVertical}
          options={VERTICALS.map((v) => ({
            value: v,
            title: t(`vertical.${v}` as Parameters<typeof t>[0]),
          }))}
        />
      </Field>
    </div>
  )
}

/** Region step — the country decides the (locked, derived) base currency; plus default language. */
export function RegionFields({
  country,
  defaultLanguage,
  onCountry,
  onDefaultLanguage,
  autoFocus = true,
}: {
  country: string
  defaultLanguage: string
  onCountry: (value: string) => void
  onDefaultLanguage: (value: string) => void
  autoFocus?: boolean
}) {
  const { t, i18n } = useTranslation()
  const baseCurrency = derivedCurrency(country)
  return (
    <div className="space-y-6">
      <Field label={t('signup.country')} htmlFor="country" hint={t('signup.countryHint')}>
        <Select
          id="country"
          autoFocus={autoFocus}
          value={country}
          onChange={(e) => onCountry(e.target.value)}
        >
          {countryOptions(i18n.language).map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
      </Field>
      {/* The derived, immutable currency — shown, never chosen (ADR 0025). */}
      <div className="flex items-start gap-2.5 rounded-xl border border-line bg-paper px-3.5 py-3">
        <Lock className="mt-0.5 size-3.5 shrink-0 text-ink-3" aria-hidden />
        <p className="text-xs leading-relaxed text-ink-2">
          {t('signup.derivedCurrencyNote', {
            currency: `${baseCurrency} (${symbolOf(baseCurrency, i18n.language)})`,
          })}
        </p>
      </div>
      {/* Language is English-first and gated to Indonesia (ADR 0059): the chooser only appears when
          the selected country is Indonesia; every other country keeps its interface in English —
          shown as a locked note, mirroring the derived-currency treatment above. */}
      {country === 'ID' ? (
        <Field label={t('signup.defaultLanguage')} hint={t('signup.defaultLanguageHint')}>
          <ChoiceCards
            name="lang"
            value={defaultLanguage}
            onChange={onDefaultLanguage}
            options={LANGS.map((l) => ({
              value: l,
              title: t(`lang.${l}`),
              subtitle: l.toUpperCase(),
            }))}
          />
        </Field>
      ) : (
        <div className="flex items-start gap-2.5 rounded-xl border border-line bg-paper px-3.5 py-3">
          <Lock className="mt-0.5 size-3.5 shrink-0 text-ink-3" aria-hidden />
          <p className="text-xs leading-relaxed text-ink-2">{t('signup.languageEnglishOnlyNote')}</p>
        </div>
      )}
      {/* Permanent-settings notice */}
      <p className="rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5 text-xs leading-relaxed text-amber">
        {t('signup.permanentNote')}
      </p>
    </div>
  )
}

/** The review list with a per-row "edit" pencil that jumps back to the owning step. */
export function ReviewPanel({
  rows,
  hint,
  onEdit,
}: {
  rows: ReviewRow[]
  hint: string
  onEdit: (step: number) => void
}) {
  const { t } = useTranslation()
  return (
    <div>
      <dl>
        {rows.map((row) => (
          <div
            key={row.label}
            className="flex items-center justify-between gap-4 border-b border-line py-2.5"
          >
            <dt className="text-sm text-ink-3">{row.label}</dt>
            <dd className="flex items-center gap-2 text-right text-sm font-medium text-ink">
              {row.secret ? (
                <span className="font-mono tracking-widest text-ink-3">{'•'.repeat(8)}</span>
              ) : (
                row.value || '—'
              )}
              {row.fixed ? (
                <Badge tone="amber">
                  <Lock className="size-3" /> {t('signup.fixedNote')}
                </Badge>
              ) : null}
              <button
                type="button"
                aria-label={`${t('signup.edit')}: ${row.label}`}
                onClick={() => onEdit(row.step)}
                className={cn(
                  'rounded p-1 text-ink-3 transition-colors',
                  'hover:text-brand-600 focus-visible:outline-2 focus-visible:outline-brand-500',
                )}
              >
                <Pencil className="size-3.5" />
              </button>
            </dd>
          </div>
        ))}
      </dl>
      <p className="mt-4 text-xs leading-relaxed text-ink-3">{hint}</p>
    </div>
  )
}

/**
 * The review for the shared Company + Region steps. Renders the six company/region rows (identical
 * for both flows) followed by any `extraRows` a flow layers on — signup passes its owner-account
 * rows; the add-company wizard passes none.
 */
export function CompanyReview({
  basics,
  extraRows = [],
  hint,
  onEdit,
}: {
  basics: CompanyBasics
  extraRows?: ReviewRow[]
  hint: string
  onEdit: (step: number) => void
}) {
  const { t, i18n } = useTranslation()
  const baseCurrency = derivedCurrency(basics.country)
  const rows: ReviewRow[] = [
    { label: t('signup.companyName'), value: basics.companyName, step: COMPANY_STEP },
    { label: t('signup.firstBusinessName'), value: basics.firstBusinessName, step: COMPANY_STEP },
    {
      label: t('signup.vertical'),
      value: t(`vertical.${basics.vertical}` as Parameters<typeof t>[0]),
      step: COMPANY_STEP,
    },
    {
      // The country is immutable (ADR 0025); its edit pencil jumps to Region.
      label: t('signup.country'),
      value: countryName(basics.country, i18n.language),
      step: REGION_STEP,
      fixed: true,
    },
    {
      // Derived from the country — the pencil jumps to Region, because changing the country IS how
      // the currency changes (ADR 0025).
      label: t('signup.baseCurrency'),
      value: `${baseCurrency} · ${t(`currency.${baseCurrency}` as Parameters<typeof t>[0])}`,
      step: REGION_STEP,
      fixed: true,
    },
    {
      label: t('signup.defaultLanguage'),
      value: t(`lang.${basics.defaultLanguage}`),
      step: REGION_STEP,
      fixed: true,
    },
    ...extraRows,
  ]
  return <ReviewPanel rows={rows} hint={hint} onEdit={onEdit} />
}
