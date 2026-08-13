import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, BookOpen, Check } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Spinner } from '@/components/ui/Spinner'
import { ErrorDetails } from '@/components/ErrorDetails'
import { CompanyFields, CompanyReview, RegionFields } from '@/features/companyForm/fields'
import { COMPANY_STEP, REGION_STEP, invalidCompanyFields } from '@/features/companyForm/companyForm'
import { useSession } from '@/lib/session'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { DEV_ACTOR } from '@/lib/devIdentity'
import { toPlanTier } from '@/lib/featureTier'
import { cn } from '@/lib/cn'
import { type Lang } from '@/i18n'
import { isIndonesiaByTimeZone, langsForCountry } from '@/lib/geo'
import { derivedCurrency } from '@/lib/countries'
import { createCompany, type CompanyResponse } from './api'

// The in-app "add a company" wizard walks the SAME Company → Region → Review steps as the public
// sign-up (shared components in features/companyForm), so creating your first company and creating
// your next one are the same flow — minus the owner-account steps (you are already signed in). It
// keeps the in-console frame (centered card + Stepper), not sign-up's standalone brand page.
const REVIEW_STEP = REGION_STEP + 1

type StepErrors = { companyName?: string; firstBusinessName?: string }

export function OnboardingWizard() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { company, setCompany, companies } = useSession()
  const auth = useAuth()
  // With ≥1 existing company this wizard is the "Add another company" flow (ADR 0021).
  const isAdditional = companies.length > 0

  // Company vs division gate (shown only when adding to an existing portfolio): a COMPANY is a
  // separate legal entity with its own NPWP, taxes, and books; a DIVISION shares the current
  // company's books and tax filing and is created on the Organization page instead. Asking here —
  // the single entry point for "add" — stops legally-separate businesses being modeled as
  // divisions and vice versa.
  const [entityConfirmed, setEntityConfirmed] = useState(false)

  // Seed the country from the browser location (ADR 0059) — Indonesia when the time zone says so,
  // else an English-market default; the language follows (Indonesian only for an ID company).
  const detectedCountry = useMemo(() => (isIndonesiaByTimeZone() ? 'ID' : 'US'), [])
  const [step, setStep] = useState(COMPANY_STEP)
  const [name, setName] = useState('')
  const [bizName, setBizName] = useState('')
  const [vertical, setVertical] = useState<string>('restaurant')
  const [country, setCountry] = useState<string>(detectedCountry)
  const [defaultLanguage, setDefaultLanguage] = useState<string>(
    detectedCountry === 'ID' && i18n.language === 'id' ? 'id' : 'en',
  )
  const [errors, setErrors] = useState<StepErrors>({})
  const [created, setCreated] = useState<CompanyResponse | null>(null)

  // Derived, never chosen (ADR 0025): the country decides the base currency; the server re-derives
  // it authoritatively (an API caller cannot pick a currency either), so this is a preview only.
  const baseCurrency = derivedCurrency(country)

  const mutation = useMutation({
    mutationFn: () =>
      createCompany(
        {
          name: name.trim(),
          country,
          baseCurrency,
          defaultLanguage,
          firstBusiness: { name: bizName.trim(), vertical },
        },
        DEV_ACTOR,
      ),
    onSuccess: async (res) => {
      setCreated(res)
      // In oidc mode the create BOUND this login to the new company (its Keycloak membership grew,
      // ADR 0021) — silently renew the token so the enlarged company_id claim arrives and the
      // /mine list includes it. Then activate the new company (setCompany holds it as the manual
      // session until the list catches up; in dev it upserts into the persisted list). One retry
      // on a failed renew; if both fail the activation still proceeds — tenant calls 403 with
      // invalid-company-selection until the next automatic renew self-heals the claim (documented
      // residual, ADR 0021).
      if (AUTH_MODE === 'oidc') {
        const renewed = await auth.refresh()
        if (!renewed) await auth.refresh()
      }
      setCompany({
        companyId: res.id,
        name: res.name,
        baseCurrency: res.baseCurrency,
        defaultLanguage: res.defaultLanguage,
        businessId: res.firstBusinessId,
        actor: AUTH_MODE === 'oidc' ? auth.actor : DEV_ACTOR,
        planTier: toPlanTier(res.planTier),
        companyCode: res.companyCode ?? '',
      })
    },
  })

  // Step names mirror sign-up's so the two flows read identically.
  const steps = [t('signup.stepCompany'), t('signup.stepRegion'), t('signup.stepReview')]

  // Validate the Company step (the same required rule as sign-up) before advancing.
  function advance() {
    if (step === COMPANY_STEP) {
      const missing = invalidCompanyFields({ companyName: name, firstBusinessName: bizName })
      if (missing.length > 0) {
        const next: StepErrors = {}
        for (const field of missing) next[field] = t('signup.fieldRequired')
        setErrors(next)
        return
      }
    }
    setErrors({})
    setStep((s) => s + 1)
  }

  if (created) {
    return <SuccessPanel company={created} onContinue={() => navigate('/')} />
  }

  // The company-vs-division gate: only when a portfolio already exists (a first-ever login has
  // nothing to add a division TO).
  if (isAdditional && !entityConfirmed) {
    return (
      <div className="mx-auto max-w-[680px]">
        <header className="mb-8 text-center">
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('onboarding.chooser.title')}
          </h1>
          <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-ink-3">
            {t('onboarding.chooser.subtitle')}
          </p>
        </header>
        <Card className="rounded-[20px] p-7">
          <ChoiceCards
            name="entityKind"
            value=""
            onChange={(v) => {
              if (v === 'company') setEntityConfirmed(true)
              else navigate('/org')
            }}
            options={[
              {
                value: 'company',
                title: t('onboarding.chooser.companyOption'),
                subtitle: t('onboarding.chooser.companyOptionHint'),
              },
              {
                value: 'division',
                title: t('onboarding.chooser.divisionOption', {
                  company: company?.name ?? '',
                }),
                subtitle: t('onboarding.chooser.divisionOptionHint'),
              },
            ]}
          />
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[680px]">
      {/* Centered header */}
      <header className="mb-8 text-center">
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t(isAdditional ? 'onboarding.addTitle' : 'onboarding.title')}
        </h1>
        <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-ink-3">
          {t('onboarding.subtitle')}
        </p>
      </header>

      {/* Stepper */}
      <Stepper steps={steps} current={step} />

      {/* Step card */}
      <Card className="mt-6 rounded-[20px] p-7" key={step}>
        <div className="reveal">
          {step === COMPANY_STEP && (
            <CompanyFields
              companyName={name}
              firstBusinessName={bizName}
              vertical={vertical}
              onCompanyName={(v) => {
                setName(v)
                if (errors.companyName) setErrors((p) => ({ ...p, companyName: undefined }))
              }}
              onFirstBusinessName={(v) => {
                setBizName(v)
                if (errors.firstBusinessName)
                  setErrors((p) => ({ ...p, firstBusinessName: undefined }))
              }}
              onVertical={setVertical}
              companyNameError={errors.companyName}
              firstBusinessNameError={errors.firstBusinessName}
            />
          )}

          {step === REGION_STEP && (
            <RegionFields
              country={country}
              defaultLanguage={defaultLanguage}
              onCountry={(c) => {
                setCountry(c)
                setDefaultLanguage((prev) =>
                  langsForCountry(c).includes(prev as Lang) ? prev : c === 'ID' ? 'id' : 'en',
                )
              }}
              onDefaultLanguage={setDefaultLanguage}
            />
          )}

          {step === REVIEW_STEP && (
            <CompanyReview
              basics={{ companyName: name, firstBusinessName: bizName, vertical, country, defaultLanguage }}
              hint={t('onboarding.reviewHint')}
              onEdit={(s) => {
                setErrors({})
                setStep(s)
              }}
            />
          )}

          {mutation.isError && (
            <div className="mt-4 space-y-2">
              <p className="rounded-xl border border-loss/30 bg-tint-loss px-3.5 py-2.5 text-sm text-loss">
                {(mutation.error as Error).message}
              </p>
              {/* Root cause (endpoint, status, problem type, traceId) + copy-for-bug-report. */}
              <ErrorDetails error={mutation.error} />
            </div>
          )}
        </div>

        {/* Footer nav */}
        <div className="mt-6 flex items-center gap-2.5">
          <Button
            variant="outline"
            onClick={() => setStep((s) => Math.max(0, s - 1))}
            disabled={step === COMPANY_STEP || mutation.isPending}
            className={cn(step === COMPANY_STEP && 'invisible')}
            aria-hidden={step === COMPANY_STEP}
          >
            <ArrowLeft className="size-4" /> {t('common.back')}
          </Button>
          <span className="flex-1" />
          {step < REVIEW_STEP ? (
            <Button onClick={advance}>
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
    <ol className="flex items-center justify-center gap-0">
      {steps.map((label, i) => {
        const state = i < current ? 'done' : i === current ? 'active' : 'todo'
        return (
          <li key={label} className="flex items-center">
            <span className="flex items-center gap-2">
              {/* Step circle */}
              <span
                className={cn(
                  'tnum grid size-7 place-items-center rounded-full text-[13px] font-bold',
                  state === 'done' || state === 'active'
                    ? 'bg-emerald text-on-emerald'
                    : 'bg-ink-50 text-ink-3',
                )}
              >
                {state === 'done' ? <Check className="size-3.5" /> : i + 1}
              </span>
              {/* Step label */}
              <span
                className={cn(
                  'hidden text-[12.5px] font-semibold sm:block',
                  state === 'active' ? 'text-ink' : 'text-ink-3',
                )}
              >
                {label}
              </span>
            </span>
            {/* Connector */}
            {i < steps.length - 1 ? (
              <span className="mx-3 h-px w-5 bg-line-strong sm:w-8" />
            ) : null}
          </li>
        )
      })}
    </ol>
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
    <div className="reveal mx-auto max-w-[680px]">
      <Card className="rounded-[24px] p-12 text-center">
        {/* Check circle */}
        <div className="mx-auto grid size-16 place-items-center rounded-full bg-brand-50 text-brand-600">
          <Check className="size-7" />
        </div>

        <h2 className="mt-5 font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('onboarding.createdTitle')}
        </h2>
        <p className="mt-1.5 text-sm text-ink-3">{t('onboarding.createdBody')}</p>

        {/* Company summary pill */}
        <div className="mx-auto mt-6 max-w-xs rounded-xl border border-line bg-paper px-4 py-3 text-left">
          <div className="text-sm font-semibold text-ink">{company.name}</div>
          <div className="mt-0.5 font-mono text-xs text-ink-3">
            {company.baseCurrency} · {company.id.slice(0, 8)}…
          </div>
        </div>

        <Button className="mt-7 w-full max-w-xs" onClick={onContinue}>
          {t('onboarding.goToDashboard')} <ArrowRight className="size-4" />
        </Button>

        {/* Non-blocking prompt (ADR 0037): a suggestion, never a gate — "Go to dashboard" above
            already completes the wizard regardless of whether this is followed. */}
        <div className="mx-auto mt-6 flex max-w-xs items-start gap-3 rounded-xl border border-line bg-paper p-4 text-left">
          <BookOpen className="mt-0.5 size-4 shrink-0 text-brand-600" aria-hidden />
          <div>
            <p className="text-sm font-semibold text-ink">
              {t('onboarding.openingBalancesCta.title')}
            </p>
            <p className="mt-1 text-xs leading-relaxed text-ink-3">
              {t('onboarding.openingBalancesCta.body')}
            </p>
            <Link
              to="/opening-balances"
              className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              {t('onboarding.openingBalancesCta.action')} <ArrowRight className="size-3.5" />
            </Link>
          </div>
        </div>
      </Card>
    </div>
  )
}
