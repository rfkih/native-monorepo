/**
 * Public sign-up page — registers a new company + owner account.
 *
 * Reachable at /signup BEFORE any Keycloak redirect (the AuthProvider carves it out).
 * On success the user is directed to sign in with their new credentials (triggers the normal
 * OIDC login flow from the "/" root so AuthProvider handles the redirect).
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Check, Eye, EyeOff, Lock } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Wordmark } from '@/components/Wordmark'
import { cn } from '@/lib/cn'
import { useSignup, type SignupRequest } from './api'

// Mirrors onboarding — the same supported currencies and languages.
const CURRENCIES = ['IDR', 'USD'] as const
const LANGS = ['en', 'id'] as const
const BUSINESS_TYPES = ['business_unit', 'branch', 'outlet'] as const

const PASSWORD_MIN_LENGTH = 8

// ── Validation helpers ────────────────────────────────────────────────────────

function isValidEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())
}

interface FormErrors {
  companyName?: string
  firstBusinessName?: string
  ownerEmail?: string
  ownerPassword?: string
}

// ── Stepper ───────────────────────────────────────────────────────────────────

function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex items-center justify-center gap-0">
      {steps.map((label, i) => {
        const state = i < current ? 'done' : i === current ? 'active' : 'todo'
        return (
          <li key={label} className="flex items-center">
            <span className="flex items-center gap-2">
              <span
                className={cn(
                  'tnum grid size-7 place-items-center rounded-full text-[13px] font-bold',
                  state === 'done' || state === 'active'
                    ? 'bg-brand-500 text-white'
                    : 'bg-ink-50 text-ink-3',
                )}
              >
                {state === 'done' ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span
                className={cn(
                  'hidden text-[12.5px] font-semibold sm:block',
                  state === 'active' ? 'text-ink' : 'text-ink-3',
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

// ── Review panel ──────────────────────────────────────────────────────────────

function ReviewPanel({
  rows,
  hint,
  fixedLabel,
}: {
  rows: { label: string; value: string; fixed?: boolean; secret?: boolean }[]
  hint: string
  fixedLabel: string
}) {
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

// ── Currency symbol helper (mirrors onboarding) ───────────────────────────────

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

// ── Success panel ─────────────────────────────────────────────────────────────

function SuccessPanel({ email, onSignIn }: { email: string; onSignIn: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="reveal mx-auto max-w-[540px]">
      <Card className="rounded-[24px] p-10 text-center">
        <div className="mx-auto grid size-16 place-items-center rounded-full bg-brand-50 text-brand-600">
          <Check className="size-7" />
        </div>
        <h2 className="mt-5 font-display text-[26px] font-bold tracking-[-0.02em] text-ink">
          {t('signup.successTitle')}
        </h2>
        <p className="mt-1.5 text-sm leading-relaxed text-ink-3">
          {t('signup.successBody')}
        </p>
        <div className="mx-auto mt-5 max-w-xs rounded-xl border border-line bg-paper px-4 py-3">
          <div className="font-mono text-xs text-ink-3">{email}</div>
        </div>
        <Button className="mt-7 w-full max-w-xs" onClick={onSignIn}>
          {t('signup.signIn')} <ArrowRight className="size-4" />
        </Button>
      </Card>
    </div>
  )
}

// ── Password field with show/hide toggle ──────────────────────────────────────

function PasswordInput({
  id,
  value,
  onChange,
  placeholder,
  autoComplete,
}: {
  id: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoComplete?: string
}) {
  const [visible, setVisible] = useState(false)
  const { t } = useTranslation()
  return (
    <div className="relative">
      <TextInput
        id={id}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        className="pr-10"
      />
      <button
        type="button"
        aria-label={t(visible ? 'signup.hidePassword' : 'signup.showPassword')}
        onClick={() => setVisible((v) => !v)}
        className={cn(
          'absolute right-3 top-1/2 -translate-y-1/2 rounded p-0.5 text-ink-3',
          'hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500',
        )}
      >
        {visible ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

export function Signup() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const mutation = useSignup()

  // Form state
  const [step, setStep] = useState(0)
  const [companyName, setCompanyName] = useState('')
  const [baseCurrency, setBaseCurrency] = useState<string>('IDR')
  const [defaultLanguage, setDefaultLanguage] = useState<string>(
    i18n.language === 'id' ? 'id' : 'en',
  )
  const [firstBusinessName, setFirstBusinessName] = useState('')
  const [firstBusinessType, setFirstBusinessType] = useState<string>('business_unit')
  const [ownerEmail, setOwnerEmail] = useState('')
  const [ownerPassword, setOwnerPassword] = useState('')
  const [errors, setErrors] = useState<FormErrors>({})
  const [successEmail, setSuccessEmail] = useState<string | null>(null)

  const steps = [
    t('signup.stepCompany'),
    t('signup.stepSettings'),
    t('signup.stepBusiness'),
    t('signup.stepAccount'),
    t('signup.stepReview'),
  ]

  // ── Validate the current step before advancing ─────────────────────────────

  function validateStep(s: number): FormErrors {
    const errs: FormErrors = {}
    if (s === 0 && !companyName.trim()) {
      errs.companyName = t('signup.fieldRequired')
    }
    if (s === 2 && !firstBusinessName.trim()) {
      errs.firstBusinessName = t('signup.fieldRequired')
    }
    if (s === 3) {
      if (!ownerEmail.trim()) {
        errs.ownerEmail = t('signup.fieldRequired')
      } else if (!isValidEmail(ownerEmail)) {
        errs.ownerEmail = t('signup.emailInvalid')
      }
      if (!ownerPassword) {
        errs.ownerPassword = t('signup.fieldRequired')
      } else if (ownerPassword.length < PASSWORD_MIN_LENGTH) {
        errs.ownerPassword = t('signup.passwordTooShort', { min: PASSWORD_MIN_LENGTH })
      }
    }
    return errs
  }

  function advance() {
    const errs = validateStep(step)
    if (Object.keys(errs).length > 0) {
      setErrors(errs)
      return
    }
    setErrors({})
    setStep((s) => s + 1)
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  function submit() {
    const body: SignupRequest = {
      companyName: companyName.trim(),
      baseCurrency,
      defaultLanguage,
      firstBusinessName: firstBusinessName.trim(),
      firstBusinessType,
      ownerEmail: ownerEmail.trim(),
      ownerPassword,
    }
    mutation.mutate(body, {
      onSuccess: (res) => {
        setSuccessEmail(res.ownerEmail)
      },
    })
  }

  // ── Navigate to sign-in ────────────────────────────────────────────────────

  function goSignIn() {
    // Navigate to root in both modes:
    // - OIDC: OidcAuthProvider detects no valid session and calls manager.signinRedirect().
    // - dev: App renders immediately since DevAuthProvider is always authenticated.
    navigate('/')
  }

  // ── Map API error type → i18n key ─────────────────────────────────────────

  function apiErrorMessage(): string {
    if (!mutation.isError) return ''
    const err = mutation.error
    const type = err?.problem?.type ?? ''
    if (type.includes('email-already-exists')) {
      return t('signup.errorEmailExists')
    }
    return err?.message ?? t('signup.errorGeneric')
  }

  // ── Success state ──────────────────────────────────────────────────────────

  if (successEmail) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-10">
        <SuccessPanel email={successEmail} onSignIn={goSignIn} />
      </div>
    )
  }

  // ── Form ───────────────────────────────────────────────────────────────────

  return (
    <div className="flex min-h-screen flex-col bg-canvas">
      {/* Top bar */}
      <header className="flex h-14 items-center border-b border-line px-6">
        <Wordmark />
        <span className="ml-auto text-sm text-ink-3">
          {t('signup.alreadyHaveAccount')}{' '}
          <Link
            to="/"
            className="font-semibold text-brand-600 hover:underline focus-visible:outline-2 focus-visible:outline-brand-500"
          >
            {t('signup.signIn')}
          </Link>
        </span>
      </header>

      {/* Content */}
      <main className="mx-auto w-full max-w-[680px] flex-1 px-4 pb-16 pt-10">
        {/* Page header */}
        <header className="mb-8 text-center">
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('signup.title')}
          </h1>
          <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-ink-3">
            {t('signup.subtitle')}
          </p>
        </header>

        {/* Stepper */}
        <Stepper steps={steps} current={step} />

        {/* Step card */}
        <Card className="mt-6 rounded-[20px] p-7" key={step}>
          <div className="reveal">

            {/* Step 0 — Company name */}
            {step === 0 && (
              <Field
                label={t('signup.companyName')}
                htmlFor="companyName"
                hint={t('signup.companyNameHint')}
                error={errors.companyName}
              >
                <TextInput
                  id="companyName"
                  autoFocus
                  autoComplete="organization"
                  value={companyName}
                  onChange={(e) => {
                    setCompanyName(e.target.value)
                    if (errors.companyName) setErrors((p) => ({ ...p, companyName: undefined }))
                  }}
                  placeholder={t('signup.companyNamePlaceholder')}
                />
              </Field>
            )}

            {/* Step 1 — Currency + language (permanent) */}
            {step === 1 && (
              <div className="space-y-6">
                <Field
                  label={t('signup.baseCurrency')}
                  hint={t('signup.baseCurrencyHint')}
                >
                  <ChoiceCards
                    name="currency"
                    value={baseCurrency}
                    onChange={setBaseCurrency}
                    options={CURRENCIES.map((c) => ({
                      value: c,
                      title: c,
                      subtitle: t(`currency.${c}`),
                      aside: (
                        <span className="font-mono text-xs text-ink-3">
                          {symbolOf(c, i18n.language)}
                        </span>
                      ),
                    }))}
                  />
                </Field>
                <Field
                  label={t('signup.defaultLanguage')}
                  hint={t('signup.defaultLanguageHint')}
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
                {/* Permanent-settings notice */}
                <p className="rounded-xl border border-amber/30 bg-amber-tint px-3.5 py-2.5 text-xs leading-relaxed text-amber">
                  {t('signup.permanentNote')}
                </p>
              </div>
            )}

            {/* Step 2 — First business */}
            {step === 2 && (
              <div className="space-y-6">
                <Field
                  label={t('signup.firstBusinessName')}
                  htmlFor="bizName"
                  error={errors.firstBusinessName}
                >
                  <TextInput
                    id="bizName"
                    autoFocus
                    autoComplete="organization"
                    value={firstBusinessName}
                    onChange={(e) => {
                      setFirstBusinessName(e.target.value)
                      if (errors.firstBusinessName)
                        setErrors((p) => ({ ...p, firstBusinessName: undefined }))
                    }}
                    placeholder={t('signup.firstBusinessNamePlaceholder')}
                  />
                </Field>
                <Field label={t('signup.businessType')}>
                  <ChoiceCards
                    name="bizType"
                    columns={1}
                    value={firstBusinessType}
                    onChange={setFirstBusinessType}
                    options={BUSINESS_TYPES.map((b) => ({
                      value: b,
                      title: t(`businessType.${b}`),
                    }))}
                  />
                </Field>
              </div>
            )}

            {/* Step 3 — Owner account credentials */}
            {step === 3 && (
              <div className="space-y-5">
                <Field
                  label={t('signup.ownerEmail')}
                  htmlFor="ownerEmail"
                  error={errors.ownerEmail}
                >
                  <TextInput
                    id="ownerEmail"
                    type="email"
                    autoFocus
                    autoComplete="email"
                    value={ownerEmail}
                    onChange={(e) => {
                      setOwnerEmail(e.target.value)
                      if (errors.ownerEmail) setErrors((p) => ({ ...p, ownerEmail: undefined }))
                    }}
                    placeholder={t('signup.ownerEmailPlaceholder')}
                  />
                </Field>
                <Field
                  label={t('signup.ownerPassword')}
                  htmlFor="ownerPassword"
                  hint={t('signup.passwordHint', { min: PASSWORD_MIN_LENGTH })}
                  error={errors.ownerPassword}
                >
                  <PasswordInput
                    id="ownerPassword"
                    value={ownerPassword}
                    onChange={(v) => {
                      setOwnerPassword(v)
                      if (errors.ownerPassword)
                        setErrors((p) => ({ ...p, ownerPassword: undefined }))
                    }}
                    placeholder={t('signup.ownerPasswordPlaceholder', { min: PASSWORD_MIN_LENGTH })}
                    autoComplete="new-password"
                  />
                </Field>
              </div>
            )}

            {/* Step 4 — Review */}
            {step === 4 && (
              <ReviewPanel
                fixedLabel={t('signup.fixedNote')}
                hint={t('signup.reviewHint')}
                rows={[
                  { label: t('signup.companyName'), value: companyName },
                  {
                    label: t('signup.baseCurrency'),
                    value: `${baseCurrency} · ${t(`currency.${baseCurrency}`)}`,
                    fixed: true,
                  },
                  {
                    label: t('signup.defaultLanguage'),
                    value: t(`lang.${defaultLanguage}`),
                    fixed: true,
                  },
                  { label: t('signup.firstBusinessName'), value: firstBusinessName },
                  {
                    label: t('signup.businessType'),
                    value: t(`businessType.${firstBusinessType}`),
                  },
                  { label: t('signup.ownerEmail'), value: ownerEmail },
                  { label: t('signup.ownerPassword'), value: '', secret: true },
                ]}
              />
            )}

            {/* API error */}
            {mutation.isError && (
              <p className="mt-4 rounded-xl border border-loss/30 bg-tint-loss px-3.5 py-2.5 text-sm text-loss">
                {apiErrorMessage()}
              </p>
            )}
          </div>

          {/* Footer navigation */}
          <div className="mt-6 flex items-center gap-2.5">
            <Button
              variant="outline"
              onClick={() => {
                setErrors({})
                setStep((s) => Math.max(0, s - 1))
              }}
              disabled={step === 0 || mutation.isPending}
              className={cn(step === 0 && 'invisible')}
              aria-hidden={step === 0}
            >
              <ArrowLeft className="size-4" /> {t('common.back')}
            </Button>
            <span className="flex-1" />
            {step < 4 ? (
              <Button onClick={advance}>
                {t('common.continue')} <ArrowRight className="size-4" />
              </Button>
            ) : (
              <Button onClick={submit} disabled={mutation.isPending}>
                {mutation.isPending ? (
                  <>
                    <Spinner /> {t('signup.creating')}
                  </>
                ) : (
                  <>
                    <Check className="size-4" /> {t('signup.create')}
                  </>
                )}
              </Button>
            )}
          </div>
        </Card>
      </main>
    </div>
  )
}
