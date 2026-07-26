/**
 * Public sign-up page — registers a new company + owner account.
 *
 * Reachable at /signup BEFORE any Keycloak redirect (the AuthProvider carves it out).
 * On success the user is directed to sign in with their new credentials; in OIDC mode the
 * redirect carries a `login_hint` so Keycloak pre-fills the email they just registered.
 *
 * Four steps (consolidated for conversion — each one is meaty): Company (name + first
 * business), Settings (the PERMANENT currency/language pair), Account (credentials + ToS
 * consent), Review (every row links back to its step). Each step is a real <form>, so
 * Enter advances; the Review step's submit creates the account.
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Check, Eye, EyeOff, Lock, Pencil } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Wordmark } from '@/components/Wordmark'
import { cn } from '@/lib/cn'
import { AUTH_MODE } from '@/lib/config'
import { useAuth } from '@/lib/authContext'
import { useSignup, type SignupRequest, type SignupResponse } from './api'

// Mirrors onboarding — the same supported currencies and languages. The server enforces the
// same whitelists authoritatively (SignupRequest @Pattern) — these lists are the UI copy of them.
const CURRENCIES = ['IDR', 'USD'] as const
const LANGS = ['en', 'id'] as const
const BUSINESS_TYPES = ['business_unit', 'branch', 'outlet'] as const

const PASSWORD_MIN_LENGTH = 8

// Step indexes — the Review rows link back to these.
const STEP_COMPANY = 0
const STEP_SETTINGS = 1
const STEP_ACCOUNT = 2
const STEP_REVIEW = 3

// ── Validation helpers ────────────────────────────────────────────────────────

function isValidEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())
}

/**
 * Coarse 0–4 password strength: length past the minimum and character-class variety.
 * Deliberately dependency-free (zxcvbn is ~800 KB); the authoritative policy is the
 * server minimum + the Keycloak realm password policy.
 */
function passwordStrength(pw: string): 0 | 1 | 2 | 3 | 4 {
  if (!pw) return 0
  let classes = 0
  if (/[a-z]/.test(pw)) classes++
  if (/[A-Z]/.test(pw)) classes++
  if (/\d/.test(pw)) classes++
  if (/[^A-Za-z0-9]/.test(pw)) classes++
  let score = 0
  if (pw.length >= PASSWORD_MIN_LENGTH) score++
  if (pw.length >= 12) score++
  if (classes >= 2) score++
  if (classes >= 3 && pw.length >= 10) score++
  return Math.min(score, 4) as 0 | 1 | 2 | 3 | 4
}

interface FormErrors {
  companyName?: string
  firstBusinessName?: string
  ownerEmail?: string
  ownerPassword?: string
  ownerPasswordConfirm?: string
  terms?: string
}

// ── Stepper ───────────────────────────────────────────────────────────────────

function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex items-center justify-center gap-0">
      {steps.map((label, i) => {
        const state = i < current ? 'done' : i === current ? 'active' : 'todo'
        return (
          <li
            key={label}
            className="flex items-center"
            aria-current={state === 'active' ? 'step' : undefined}
          >
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
  editLabel,
  onEdit,
}: {
  rows: { label: string; value: string; step: number; fixed?: boolean; secret?: boolean }[]
  hint: string
  fixedLabel: string
  editLabel: string
  onEdit: (step: number) => void
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
              <button
                type="button"
                aria-label={`${editLabel}: ${row.label}`}
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

function SuccessPanel({
  result,
  onSignIn,
}: {
  result: SignupResponse
  onSignIn: () => void
}) {
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
          {result.emailVerificationRequired
            ? t('signup.successVerifyBody')
            : t('signup.successBody')}
        </p>
        <div className="mx-auto mt-5 max-w-xs rounded-xl border border-line bg-paper px-4 py-3">
          <div className="font-mono text-xs text-ink-3">{result.ownerEmail}</div>
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

// ── Password strength meter ───────────────────────────────────────────────────

function StrengthMeter({ password }: { password: string }) {
  const { t } = useTranslation()
  const score = passwordStrength(password)
  if (!password) return null
  const labels = [
    t('signup.strengthWeak'),
    t('signup.strengthWeak'),
    t('signup.strengthFair'),
    t('signup.strengthGood'),
    t('signup.strengthStrong'),
  ]
  const tones = ['bg-loss', 'bg-loss', 'bg-amber', 'bg-brand-500', 'bg-brand-600']
  return (
    <div className="mt-2" aria-live="polite">
      <div className="flex gap-1">
        {[1, 2, 3, 4].map((seg) => (
          <span
            key={seg}
            className={cn(
              'h-1 flex-1 rounded-full transition-colors',
              score >= seg ? tones[score] : 'bg-ink-50',
            )}
          />
        ))}
      </div>
      <p className="mt-1 text-xs text-ink-3">{labels[score]}</p>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

export function Signup() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const auth = useAuth()
  const mutation = useSignup()

  // Form state
  const [step, setStep] = useState(STEP_COMPANY)
  const [companyName, setCompanyName] = useState('')
  const [baseCurrency, setBaseCurrency] = useState<string>('IDR')
  const [defaultLanguage, setDefaultLanguage] = useState<string>(
    i18n.language === 'id' ? 'id' : 'en',
  )
  const [firstBusinessName, setFirstBusinessName] = useState('')
  const [firstBusinessType, setFirstBusinessType] = useState<string>('business_unit')
  const [ownerEmail, setOwnerEmail] = useState('')
  const [ownerPassword, setOwnerPassword] = useState('')
  const [ownerPasswordConfirm, setOwnerPasswordConfirm] = useState('')
  const [termsAccepted, setTermsAccepted] = useState(false)
  const [errors, setErrors] = useState<FormErrors>({})
  const [success, setSuccess] = useState<SignupResponse | null>(null)

  const steps = [
    t('signup.stepCompany'),
    t('signup.stepSettings'),
    t('signup.stepAccount'),
    t('signup.stepReview'),
  ]

  // ── Validate the current step before advancing ─────────────────────────────

  function validateStep(s: number): FormErrors {
    const errs: FormErrors = {}
    if (s === STEP_COMPANY) {
      if (!companyName.trim()) errs.companyName = t('signup.fieldRequired')
      if (!firstBusinessName.trim()) errs.firstBusinessName = t('signup.fieldRequired')
    }
    if (s === STEP_ACCOUNT) {
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
      if (ownerPassword && ownerPasswordConfirm !== ownerPassword) {
        errs.ownerPasswordConfirm = t('signup.passwordMismatch')
      }
      if (!termsAccepted) {
        errs.terms = t('signup.termsRequired')
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
      termsAccepted,
    }
    mutation.mutate(body, {
      onSuccess: (res) => setSuccess(res),
    })
  }

  // ── Navigate to sign-in ────────────────────────────────────────────────────

  function goSignIn(email: string) {
    if (AUTH_MODE === 'oidc') {
      // Straight to Keycloak with the just-registered email pre-filled (login_hint) — the
      // user should never have to re-type the address they entered a moment ago.
      auth.login(email)
      return
    }
    // dev: App renders immediately since DevAuthProvider is always authenticated.
    navigate('/')
  }

  // ── Map API error type → i18n key ─────────────────────────────────────────
  // Every branch resolves to an i18n key — raw server strings (English-only RFC-7807
  // details) are never shown (rule 9).

  function apiErrorMessage(): string {
    if (!mutation.isError) return ''
    const err = mutation.error
    const type = err?.problem?.type ?? ''
    if (type.includes('email-already-exists')) return t('signup.errorEmailExists')
    if (type.includes('keycloak-admin-unavailable')) return t('signup.errorIdpUnavailable')
    if (err?.status === 429) return t('signup.errorRateLimited')
    if (err?.status === 400) return t('signup.errorValidation')
    return t('signup.errorGeneric')
  }

  // ── Success state ──────────────────────────────────────────────────────────

  if (success) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-10">
        <SuccessPanel result={success} onSignIn={() => goSignIn(success.ownerEmail)} />
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

        {/* Step card — a real form so Enter advances (and submits on Review) */}
        <Card className="mt-6 rounded-[20px] p-7" key={step}>
          <form
            noValidate
            onSubmit={(e) => {
              e.preventDefault()
              if (mutation.isPending) return
              if (step < STEP_REVIEW) advance()
              else submit()
            }}
          >
            <div className="reveal">
              {/* Step 0 — Company + first business */}
              {step === STEP_COMPANY && (
                <div className="space-y-6">
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
                  <Field
                    label={t('signup.firstBusinessName')}
                    htmlFor="bizName"
                    error={errors.firstBusinessName}
                  >
                    <TextInput
                      id="bizName"
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

              {/* Step 1 — Currency + language (permanent) */}
              {step === STEP_SETTINGS && (
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

              {/* Step 2 — Owner account credentials + consent */}
              {step === STEP_ACCOUNT && (
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
                    hint={errors.ownerPassword ? undefined : t('signup.passwordHint', { min: PASSWORD_MIN_LENGTH })}
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
                    <StrengthMeter password={ownerPassword} />
                  </Field>
                  <Field
                    label={t('signup.confirmPassword')}
                    htmlFor="ownerPasswordConfirm"
                    error={errors.ownerPasswordConfirm}
                  >
                    <PasswordInput
                      id="ownerPasswordConfirm"
                      value={ownerPasswordConfirm}
                      onChange={(v) => {
                        setOwnerPasswordConfirm(v)
                        if (errors.ownerPasswordConfirm)
                          setErrors((p) => ({ ...p, ownerPasswordConfirm: undefined }))
                      }}
                      placeholder={t('signup.confirmPasswordPlaceholder')}
                      autoComplete="new-password"
                    />
                  </Field>

                  {/* ToS consent — validated here AND server-side (@AssertTrue) */}
                  <div>
                    <label className="flex cursor-pointer items-start gap-2.5">
                      <input
                        type="checkbox"
                        checked={termsAccepted}
                        onChange={(e) => {
                          setTermsAccepted(e.target.checked)
                          if (errors.terms) setErrors((p) => ({ ...p, terms: undefined }))
                        }}
                        className="mt-0.5 size-4 shrink-0 accent-brand-500"
                      />
                      <span className="text-sm leading-relaxed text-ink">
                        {t('signup.termsLabel')}
                      </span>
                    </label>
                    {errors.terms ? <p className="mt-1 text-xs text-rose">{errors.terms}</p> : null}
                  </div>
                </div>
              )}

              {/* Step 3 — Review */}
              {step === STEP_REVIEW && (
                <ReviewPanel
                  fixedLabel={t('signup.fixedNote')}
                  editLabel={t('signup.edit')}
                  hint={t('signup.reviewHint')}
                  onEdit={(s) => {
                    setErrors({})
                    setStep(s)
                  }}
                  rows={[
                    { label: t('signup.companyName'), value: companyName, step: STEP_COMPANY },
                    {
                      label: t('signup.firstBusinessName'),
                      value: firstBusinessName,
                      step: STEP_COMPANY,
                    },
                    {
                      label: t('signup.businessType'),
                      value: t(`businessType.${firstBusinessType}`),
                      step: STEP_COMPANY,
                    },
                    {
                      label: t('signup.baseCurrency'),
                      value: `${baseCurrency} · ${t(`currency.${baseCurrency}`)}`,
                      step: STEP_SETTINGS,
                      fixed: true,
                    },
                    {
                      label: t('signup.defaultLanguage'),
                      value: t(`lang.${defaultLanguage}`),
                      step: STEP_SETTINGS,
                      fixed: true,
                    },
                    { label: t('signup.ownerEmail'), value: ownerEmail, step: STEP_ACCOUNT },
                    {
                      label: t('signup.ownerPassword'),
                      value: '',
                      step: STEP_ACCOUNT,
                      secret: true,
                    },
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
                type="button"
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
              {step < STEP_REVIEW ? (
                <Button type="submit">
                  {t('common.continue')} <ArrowRight className="size-4" />
                </Button>
              ) : (
                <Button type="submit" disabled={mutation.isPending}>
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
          </form>
        </Card>
      </main>
    </div>
  )
}
