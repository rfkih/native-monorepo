import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Check, Lock } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { DEV_ACTOR } from '@/lib/devIdentity'
import { cn } from '@/lib/cn'
import { createCompany, type CompanyResponse } from './api'

const CURRENCIES = ['IDR', 'USD'] as const
const LANGS = ['en', 'id'] as const
const BUSINESS_TYPES = ['business_unit', 'branch', 'outlet'] as const

export function OnboardingWizard() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { setCompany } = useSession()

  const [step, setStep] = useState(0)
  const [name, setName] = useState('')
  const [baseCurrency, setBaseCurrency] = useState<string>('IDR')
  const [defaultLanguage, setDefaultLanguage] = useState<string>(
    i18n.language === 'id' ? 'id' : 'en',
  )
  const [bizName, setBizName] = useState('')
  const [bizType, setBizType] = useState<string>('business_unit')
  const [created, setCreated] = useState<CompanyResponse | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      createCompany(
        {
          name: name.trim(),
          baseCurrency,
          defaultLanguage,
          firstBusiness: { name: bizName.trim(), type: bizType },
        },
        DEV_ACTOR,
      ),
    onSuccess: (res) => {
      setCreated(res)
      setCompany({
        companyId: res.id,
        name: res.name,
        baseCurrency: res.baseCurrency,
        defaultLanguage: res.defaultLanguage,
        businessId: res.firstBusinessId,
        actor: DEV_ACTOR,
      })
    },
  })

  const steps = [
    t('onboarding.stepCompany'),
    t('onboarding.stepSettings'),
    t('onboarding.stepBusiness'),
    t('onboarding.stepReview'),
  ]
  const canAdvance =
    step === 0 ? name.trim().length > 0 : step === 2 ? bizName.trim().length > 0 : true

  if (created) {
    return <SuccessPanel company={created} onContinue={() => navigate('/')} />
  }

  return (
    <div className="mx-auto max-w-xl">
      <header className="mb-8 text-center">
        <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
          {t('onboarding.title')}
        </h1>
        <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-3">
          {t('onboarding.subtitle')}
        </p>
      </header>

      <Stepper steps={steps} current={step} />

      <Card className="mt-6 p-7" key={step}>
        <div className="reveal">
          {step === 0 && (
            <Field label={t('onboarding.companyName')} htmlFor="companyName">
              <TextInput
                id="companyName"
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t('onboarding.companyNamePlaceholder')}
              />
            </Field>
          )}

          {step === 1 && (
            <div className="space-y-6">
              <Field label={t('onboarding.baseCurrency')} hint={t('onboarding.baseCurrencyHint')}>
                <ChoiceCards
                  name="currency"
                  value={baseCurrency}
                  onChange={setBaseCurrency}
                  options={CURRENCIES.map((c) => ({
                    value: c,
                    title: c,
                    subtitle: t(`currency.${c}`),
                    aside: <span className="font-mono text-xs text-ink-3">{symbolOf(c, i18n.language)}</span>,
                  }))}
                />
              </Field>
              <Field
                label={t('onboarding.defaultLanguage')}
                hint={t('onboarding.defaultLanguageHint')}
              >
                <ChoiceCards
                  name="lang"
                  value={defaultLanguage}
                  onChange={setDefaultLanguage}
                  options={LANGS.map((l) => ({
                    value: l,
                    title: t(`lang.${l}`),
                    subtitle: l.toUpperCase(),
                  }))}
                />
              </Field>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-6">
              <Field label={t('onboarding.firstBusinessName')} htmlFor="bizName">
                <TextInput
                  id="bizName"
                  autoFocus
                  value={bizName}
                  onChange={(e) => setBizName(e.target.value)}
                  placeholder={t('onboarding.firstBusinessNamePlaceholder')}
                />
              </Field>
              <Field label={t('onboarding.businessType')}>
                <ChoiceCards
                  name="bizType"
                  columns={1}
                  value={bizType}
                  onChange={setBizType}
                  options={BUSINESS_TYPES.map((b) => ({ value: b, title: t(`businessType.${b}`) }))}
                />
              </Field>
            </div>
          )}

          {step === 3 && (
            <ReviewPanel
              fixedLabel={t('onboarding.fixedNote')}
              hint={t('onboarding.reviewHint')}
              rows={[
                { label: t('onboarding.companyName'), value: name },
                {
                  label: t('onboarding.baseCurrency'),
                  value: `${baseCurrency} · ${t(`currency.${baseCurrency}`)}`,
                  fixed: true,
                },
                {
                  label: t('onboarding.defaultLanguage'),
                  value: t(`lang.${defaultLanguage}`),
                  fixed: true,
                },
                { label: t('onboarding.firstBusinessName'), value: bizName },
                { label: t('onboarding.businessType'), value: t(`businessType.${bizType}`) },
              ]}
            />
          )}

          {mutation.isError && (
            <p className="mt-4 rounded-lg border border-rose/30 bg-rose-tint px-3.5 py-2.5 text-sm text-rose">
              {(mutation.error as Error).message}
            </p>
          )}
        </div>

        <div className="mt-7 flex items-center justify-between gap-3">
          <Button
            variant="ghost"
            onClick={() => setStep((s) => Math.max(0, s - 1))}
            disabled={step === 0 || mutation.isPending}
          >
            <ArrowLeft className="size-4" /> {t('common.back')}
          </Button>
          {step < 3 ? (
            <Button onClick={() => setStep((s) => s + 1)} disabled={!canAdvance}>
              {t('common.continue')} <ArrowRight className="size-4" />
            </Button>
          ) : (
            <Button onClick={() => mutation.mutate()} disabled={mutation.isPending}>
              {mutation.isPending ? (
                <>
                  <Spinner /> {t('onboarding.creating')}
                </>
              ) : (
                <>
                  <Check className="size-4" /> {t('onboarding.create')}
                </>
              )}
            </Button>
          )}
        </div>
      </Card>
    </div>
  )
}

function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex items-center justify-center">
      {steps.map((label, i) => {
        const state = i < current ? 'done' : i === current ? 'active' : 'todo'
        return (
          <li key={label} className="flex items-center">
            <span className="flex items-center gap-2">
              <span
                className={cn(
                  'tnum grid size-6 place-items-center rounded-full border text-xs font-medium',
                  state === 'done'
                    ? 'border-emerald bg-emerald text-white'
                    : state === 'active'
                      ? 'border-emerald text-emerald-2'
                      : 'border-line-strong text-ink-3',
                )}
              >
                {state === 'done' ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span
                className={cn(
                  'hidden text-xs font-medium sm:block',
                  state === 'todo' ? 'text-ink-3' : 'text-ink',
                )}
              >
                {label}
              </span>
            </span>
            {i < steps.length - 1 ? <span className="mx-3 h-px w-5 bg-line-strong sm:w-8" /> : null}
          </li>
        )
      })}
    </ol>
  )
}

function ReviewPanel({
  rows,
  hint,
  fixedLabel,
}: {
  rows: { label: string; value: string; fixed?: boolean }[]
  hint: string
  fixedLabel: string
}) {
  return (
    <div>
      <dl className="divide-y divide-line">
        {rows.map((row) => (
          <div key={row.label} className="flex items-center justify-between gap-4 py-3">
            <dt className="text-sm text-ink-3">{row.label}</dt>
            <dd className="flex items-center gap-2 text-right text-sm font-medium text-ink">
              {row.value || '—'}
              {row.fixed ? (
                <Badge tone="amber">
                  <Lock className="size-3" /> {fixedLabel}
                </Badge>
              ) : null}
            </dd>
          </div>
        ))}
      </dl>
      <p className="mt-4 text-xs leading-relaxed text-ink-3">{hint}</p>
    </div>
  )
}

function SuccessPanel({
  company,
  onContinue,
}: {
  company: CompanyResponse
  onContinue: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="reveal mx-auto max-w-md">
      <Card className="p-8 text-center">
        <div className="mx-auto grid size-12 place-items-center rounded-full bg-emerald-tint text-emerald">
          <Check className="size-6" />
        </div>
        <h2 className="mt-4 font-display text-2xl font-semibold text-ink">
          {t('onboarding.createdTitle')}
        </h2>
        <p className="mt-1.5 text-sm text-ink-3">{t('onboarding.createdBody')}</p>
        <div className="mt-5 rounded-lg border border-line bg-paper px-4 py-3 text-left">
          <div className="text-sm font-medium text-ink">{company.name}</div>
          <div className="mt-0.5 font-mono text-xs text-ink-3">
            {company.baseCurrency} · {company.id.slice(0, 8)}…
          </div>
        </div>
        <Button className="mt-6 w-full" onClick={onContinue}>
          {t('onboarding.goToDashboard')} <ArrowRight className="size-4" />
        </Button>
      </Card>
    </div>
  )
}

function symbolOf(currency: string, locale: string): string {
  try {
    const parts = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      currencyDisplay: 'narrowSymbol',
    }).formatToParts(0)
    return parts.find((p) => p.type === 'currency')?.value ?? currency
  } catch {
    return currency
  }
}
