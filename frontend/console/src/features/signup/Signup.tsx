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
import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Check, Eye, EyeOff } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { Segmented } from '@/components/ui/Segmented'
import { CompanyFields, CompanyReview, RegionFields } from '@/features/companyForm/fields'
import { COMPANY_STEP, REGION_STEP, invalidCompanyFields } from '@/features/companyForm/companyForm'
import { Spinner } from '@/components/ui/Spinner'
import { BrandMark } from '@/components/Wordmark'
import { ErrorDetails } from '@/components/ErrorDetails'
import { cn } from '@/lib/cn'
import { AUTH_MODE } from '@/lib/config'
import { type Lang } from '@/i18n'
import { isIndonesiaByTimeZone, langsForCountry } from '@/lib/geo'
import { useAuth } from '@/lib/authContext'
import { useSignup, type SignupRequest, type SignupResponse } from './api'
// Barbershop scene for the brand panel (licensed — see src/assets/landing/SOURCES.md).
import brandPanelPhoto from '@/assets/landing/signup-w1024.webp'

// The owner-funnel whitelists (UI copy of the server's SignupRequest @Pattern). The company/
// region whitelists (languages, verticals) live with the shared steps in features/companyForm.
const COMPANY_SIZES = ['1-5', '6-20', '21-50', '51-250', '250+'] as const
const INTERESTS = ['own-company', 'client-services', 'student', 'teacher'] as const

// Wire token → i18n key maps (rule 9: option labels are localized copy, tokens are contract).
const SIZE_KEYS: Record<(typeof COMPANY_SIZES)[number], string> = {
  '1-5': 'signup.companySizeOpt1to5',
  '6-20': 'signup.companySizeOpt6to20',
  '21-50': 'signup.companySizeOpt21to50',
  '51-250': 'signup.companySizeOpt51to250',
  '250+': 'signup.companySizeOpt250plus',
}
const INTEREST_KEYS: Record<(typeof INTERESTS)[number], string> = {
  'own-company': 'signup.interestOwnCompany',
  'client-services': 'signup.interestClientServices',
  student: 'signup.interestStudent',
  teacher: 'signup.interestTeacher',
}

// Must match the server's owner-credential floor (SignupRequest @Size(min = 10), raised by ADR 0054
// to mirror the realm's length(10) policy). Keep in sync — a lower value here lets an 8–9-char
// password pass the client and then fail server validation with a confusing generic 400.
const PASSWORD_MIN_LENGTH = 10
// UI copy of the server's lenient phone check (SignupRequest @Pattern) — advisory only.
// Digit-terminated and ≤ 32 chars total (the company.phone column width).
const PHONE_RE = /^\+?[0-9][0-9 ()-]{3,29}[0-9]$/

// Step indexes — the Review rows link back to these.
// Company and Region are the SAME step indexes as the in-app add-company wizard (shared source),
// so the review's edit pencils and CompanyReview's hardcoded row steps stay in sync across flows.
const STEP_COMPANY = COMPANY_STEP
const STEP_REGION = REGION_STEP
const STEP_YOU = 2
const STEP_SECURITY = 3
const STEP_REVIEW = 4

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
  ownerFirstName?: string
  ownerEmail?: string
  phone?: string
  ownerPassword?: string
  ownerPasswordConfirm?: string
  terms?: string
}

// ── Step progress (design 5a) — a mono "Step n of N" label + segment bar ──────

function StepProgress({
  current,
  total,
  label,
  stepName,
}: {
  current: number
  total: number
  label: string
  stepName: string
}) {
  return (
    <div aria-label={`${label} · ${stepName}`} aria-current="step">
      <div className="flex items-center gap-3">
        <span className="font-mono text-xs font-semibold text-ink-3">{label}</span>
        <span className="flex flex-1 gap-[5px]" aria-hidden>
          {Array.from({ length: total }, (_, i) => (
            <span
              key={i}
              className={cn(
                'h-1 flex-1 rounded-full transition-colors',
                i <= current ? 'bg-emerald' : 'bg-ink-100',
              )}
            />
          ))}
        </span>
      </div>
    </div>
  )
}

// ── Brand panel (design 5a) — one cyan surface carries all the brand weight ───

function BrandPanel() {
  const { t } = useTranslation()
  const proofs = [t('signup.proof1'), t('signup.proof2'), t('signup.proof3')]
  return (
    <aside className="relative hidden w-[512px] shrink-0 flex-col overflow-hidden bg-brand-700 p-12 lg:flex">
      {/* Photography: barbershop scene as a CSS background (a background inside a display:none
          ancestor is never fetched, so mobile pays zero bytes), pulled on-brand by a cyan multiply
          layer plus a bottom-weighted gradient that keeps the copy zone ≥ AA on white text. */}
      <div
        aria-hidden
        className="absolute inset-0 bg-cover bg-center"
        style={{ backgroundImage: `url(${brandPanelPhoto})` }}
      />
      <div aria-hidden className="absolute inset-0 bg-brand-800/70 mix-blend-multiply" />
      <div
        aria-hidden
        className="absolute inset-0 bg-gradient-to-t from-brand-900/85 via-brand-800/40 to-brand-700/25"
      />
      {/* Soft background shapes */}
      <div
        aria-hidden
        className="absolute -top-40 -right-56 size-[560px] rounded-full bg-white/[0.04]"
      />
      <div
        aria-hidden
        className="absolute -bottom-32 -left-36 size-[360px] rounded-full bg-white/[0.03]"
      />

      {/* Mark — on dark the bright end takes over and the glyph goes dark */}
      <div className="relative flex items-center gap-[11px]">
        <span className="grid size-9 place-items-center rounded-xl bg-brand-300">
          <BrandMark size={20} stroke="var(--color-brand-900, #04303a)" strokeWidth={2.6} />
        </span>
        <span className="font-display text-lg font-extrabold tracking-[-0.02em] text-white">
          {t('app.name')}
        </span>
      </div>

      <div className="flex-1" />

      <div className="relative">
        <h1 className="max-w-[14ch] font-display text-[40px] font-extrabold leading-[1.1] tracking-[-0.03em] text-white [text-wrap:pretty]">
          {t('signup.heroTitle')}
        </h1>
        <p className="mt-[18px] max-w-[38ch] text-[17px] leading-relaxed text-white/[0.78] [text-wrap:pretty]">
          {t('signup.heroBody')}
        </p>
        <ul className="mt-9 flex flex-col gap-3.5">
          {proofs.map((proof) => (
            <li key={proof} className="flex items-start gap-3">
              <span className="mt-px grid size-[22px] shrink-0 place-items-center rounded-full bg-white/[0.16]">
                <Check className="size-[13px] text-brand-200" strokeWidth={3} aria-hidden />
              </span>
              <span className="text-[15px] leading-normal text-white/90">{proof}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="flex-1" />

      <p className="relative border-t border-white/[0.16] pt-7 text-[13px] leading-relaxed text-white/[0.65]">
        {t('signup.heroFootnote')}
      </p>
    </aside>
  )
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
      <Card className="rounded-[28px] p-10 text-center">
        {/* Green means done — the one non-profit place it is allowed */}
        <div className="mx-auto grid size-16 place-items-center rounded-full bg-tint-profit text-profit">
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

  // Form state. The country seeds from the browser location (ADR 0059): Indonesia when the time
  // zone says so, otherwise a sensible English-market default the owner can change. Language follows
  // — Indonesian only when the detected country is Indonesia (else English-first).
  const detectedCountry = useMemo(() => (isIndonesiaByTimeZone() ? 'ID' : 'US'), [])
  const [step, setStep] = useState(STEP_COMPANY)
  const [companyName, setCompanyName] = useState('')
  const [country, setCountry] = useState<string>(detectedCountry)
  const [defaultLanguage, setDefaultLanguage] = useState<string>(
    detectedCountry === 'ID' && i18n.language === 'id' ? 'id' : 'en',
  )
  const [vertical, setVertical] = useState<string>('restaurant')
  const [ownerFirstName, setOwnerFirstName] = useState('')
  const [ownerLastName, setOwnerLastName] = useState('')
  const [phone, setPhone] = useState('')
  const [companySize, setCompanySize] = useState<string>('1-5')
  const [primaryInterest, setPrimaryInterest] = useState<string>('own-company')
  const [ownerEmail, setOwnerEmail] = useState('')
  const [ownerPassword, setOwnerPassword] = useState('')
  const [ownerPasswordConfirm, setOwnerPasswordConfirm] = useState('')
  const [termsAccepted, setTermsAccepted] = useState(false)
  const [errors, setErrors] = useState<FormErrors>({})
  const [success, setSuccess] = useState<SignupResponse | null>(null)

  const steps = [
    t('signup.stepCompany'),
    t('signup.stepRegion'),
    t('signup.stepYou'),
    t('signup.stepSecurity'),
    t('signup.stepReview'),
  ]

  // ── Validate the current step before advancing ─────────────────────────────

  function validateStep(s: number): FormErrors {
    const errs: FormErrors = {}
    if (s === STEP_COMPANY) {
      // The shared required-rule (same as the in-app add-company wizard).
      for (const field of invalidCompanyFields({ companyName })) {
        errs[field] = t('signup.fieldRequired')
      }
    }
    if (s === STEP_YOU) {
      if (!ownerFirstName.trim()) errs.ownerFirstName = t('signup.fieldRequired')
      if (!ownerEmail.trim()) {
        errs.ownerEmail = t('signup.fieldRequired')
      } else if (!isValidEmail(ownerEmail)) {
        errs.ownerEmail = t('signup.emailInvalid')
      }
      if (phone.trim() && !PHONE_RE.test(phone.trim())) {
        errs.phone = t('signup.phoneInvalid')
      }
    }
    if (s === STEP_SECURITY) {
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

  // Map a server RFC-7807 field-validation error (problem.errors[].field) back onto the specific
  // form field + the step it lives on, so a rejected value shows WHAT is wrong exactly where the
  // user typed it — not just a generic banner. Server messages are English (rule 9), so each known
  // field resolves to a LOCALIZED message here; the password length reuses PASSWORD_MIN_LENGTH so it
  // can never drift from the client hint again (the `passwordDistinctFromEmail` cross-field rule and
  // `ownerPassword` @Size both land on the password field). Unknown fields fall through to the banner.
  function serverFieldError(
    field: string,
  ): { key: keyof FormErrors; step: number; message: string } | null {
    switch (field) {
      case 'companyName':
        return { key: 'companyName', step: STEP_COMPANY, message: t('signup.fieldRequired') }
      case 'ownerFirstName':
        return { key: 'ownerFirstName', step: STEP_YOU, message: t('signup.fieldRequired') }
      case 'ownerEmail':
        return { key: 'ownerEmail', step: STEP_YOU, message: t('signup.emailInvalid') }
      case 'phone':
        return { key: 'phone', step: STEP_YOU, message: t('signup.phoneInvalid') }
      case 'ownerPassword':
        return {
          key: 'ownerPassword',
          step: STEP_SECURITY,
          message: t('signup.passwordTooShort', { min: PASSWORD_MIN_LENGTH }),
        }
      case 'passwordDistinctFromEmail':
        return { key: 'ownerPassword', step: STEP_SECURITY, message: t('signup.passwordSameAsEmail') }
      case 'termsAccepted':
        return { key: 'terms', step: STEP_SECURITY, message: t('signup.termsRequired') }
      default:
        return null
    }
  }

  function submit() {
    const body: SignupRequest = {
      companyName: companyName.trim(),
      country,
      defaultLanguage,
      vertical,
      ownerFirstName: ownerFirstName.trim(),
      ownerLastName: ownerLastName.trim() || undefined,
      phone: phone.trim() || undefined,
      companySize,
      primaryInterest,
      ownerEmail: ownerEmail.trim(),
      ownerPassword,
      termsAccepted,
    }
    mutation.mutate(body, {
      onSuccess: (res) => setSuccess(res),
      onError: (err) => {
        // Surface server-side field validation (400) ON the fields themselves and jump to the
        // earliest step that has one, so nothing is hidden behind the generic banner (a password
        // rejected on the Security step must not stay invisible while the user sits on Review).
        const serverErrors = err.problem?.errors
        if (err.status !== 400 || !serverErrors?.length) return
        const mapped: FormErrors = {}
        let firstStep: number | null = null
        for (const { field } of serverErrors) {
          const m = serverFieldError(field)
          if (!m) continue
          mapped[m.key] = m.message
          firstStep = firstStep === null ? m.step : Math.min(firstStep, m.step)
        }
        if (Object.keys(mapped).length > 0) {
          setErrors(mapped)
          if (firstStep !== null) setStep(firstStep)
        }
      },
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

  // ── Form — design 5a split canvas: the cyan does the persuading, the form stays quiet ──

  return (
    <div className="flex min-h-screen bg-canvas">
      <BrandPanel />

      <div className="flex min-h-screen min-w-0 flex-1 flex-col">
        {/* Phone: the panel folds into a short cyan band that keeps the mark and headline */}
        <div className="relative overflow-hidden bg-brand-700 px-6 pb-7 pt-5 lg:hidden">
          <div
            aria-hidden
            className="absolute -top-28 -right-36 size-80 rounded-full bg-white/[0.055]"
          />
          <div className="relative flex items-center gap-2.5">
            <span className="grid size-8 place-items-center rounded-xl bg-brand-300">
              <BrandMark size={18} stroke="var(--color-brand-900, #04303a)" strokeWidth={2.6} />
            </span>
            <span className="font-display text-[17px] font-extrabold tracking-[-0.02em] text-white">
              {t('app.name')}
            </span>
          </div>
          <h1 className="relative mt-5 max-w-[15ch] font-display text-[26px] font-extrabold leading-[1.15] tracking-[-0.025em] text-white [text-wrap:pretty]">
            {t('signup.heroTitle')}
          </h1>
        </div>

        {/* Sign-in escape hatch */}
        <header className="flex h-16 shrink-0 items-center justify-end gap-2 px-6 lg:px-12">
          <span className="text-sm text-ink-3">{t('signup.alreadyHaveAccount')}</span>
          <Link
            to="/"
            className="text-sm font-bold text-emerald-2 hover:underline focus-visible:outline-2 focus-visible:outline-emerald"
          >
            {t('signup.signIn')}
          </Link>
        </header>

        {/* Step form — a real form so Enter advances (and submits on Review) */}
        <main
          className="mx-auto flex w-full max-w-[464px] flex-1 flex-col justify-center px-6 pb-14 lg:px-6"
          key={step}
        >
          <StepProgress
            current={step}
            total={steps.length}
            label={t('signup.stepOf', { n: step + 1, total: steps.length })}
            stepName={steps[step]}
          />

          <h2 className="mt-[22px] font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
            {t('signup.title')}
          </h2>
          <p className="mt-2 text-[15px] leading-relaxed text-ink-3">{t('signup.subtitle')}</p>

          <div className="mt-7">
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
                <CompanyFields
                  companyName={companyName}
                  vertical={vertical}
                  onCompanyName={(v) => {
                    setCompanyName(v)
                    if (errors.companyName) setErrors((p) => ({ ...p, companyName: undefined }))
                  }}
                  onVertical={setVertical}
                  companyNameError={errors.companyName}
                />
              )}

              {/* Step 1 — Region: country decides the (locked) base currency AND which languages
                  are offered (ADR 0059 — Indonesian only in Indonesia); changing it re-clamps the
                  language to what the new country allows. */}
              {step === STEP_REGION && (
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

              {/* Step 2 — About you: name, contact and the Odoo-style funnel questions */}
              {step === STEP_YOU && (
                <div className="space-y-5">
                  <div className="grid grid-cols-2 gap-3">
                    <Field
                      label={t('signup.ownerFirstName')}
                      htmlFor="ownerFirstName"
                      error={errors.ownerFirstName}
                    >
                      <TextInput
                        id="ownerFirstName"
                        autoFocus
                        autoComplete="given-name"
                        value={ownerFirstName}
                        onChange={(e) => {
                          setOwnerFirstName(e.target.value)
                          if (errors.ownerFirstName)
                            setErrors((p) => ({ ...p, ownerFirstName: undefined }))
                        }}
                        placeholder={t('signup.ownerFirstNamePlaceholder')}
                      />
                    </Field>
                    <Field
                      label={t('signup.ownerLastName')}
                      htmlFor="ownerLastName"
                      hint={t('signup.ownerLastNameHint')}
                    >
                      <TextInput
                        id="ownerLastName"
                        autoComplete="family-name"
                        value={ownerLastName}
                        onChange={(e) => setOwnerLastName(e.target.value)}
                        placeholder={t('signup.ownerLastNamePlaceholder')}
                      />
                    </Field>
                  </div>
                  <Field
                    label={t('signup.ownerEmail')}
                    htmlFor="ownerEmail"
                    error={errors.ownerEmail}
                  >
                    <TextInput
                      id="ownerEmail"
                      type="email"
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
                    label={t('signup.phone')}
                    htmlFor="phone"
                    hint={errors.phone ? undefined : t('signup.phoneHint')}
                    error={errors.phone}
                  >
                    <TextInput
                      id="phone"
                      type="tel"
                      autoComplete="tel"
                      value={phone}
                      onChange={(e) => {
                        setPhone(e.target.value)
                        if (errors.phone) setErrors((p) => ({ ...p, phone: undefined }))
                      }}
                      placeholder={t('signup.phonePlaceholder')}
                    />
                  </Field>
                  <Field label={t('signup.companySize')}>
                    <Segmented
                      fluid
                      ariaLabel={t('signup.companySize')}
                      value={companySize}
                      onChange={setCompanySize}
                      options={COMPANY_SIZES.map((s) => ({
                        value: s as string,
                        label: t(SIZE_KEYS[s] as Parameters<typeof t>[0]),
                      }))}
                    />
                  </Field>
                  <Field label={t('signup.primaryInterest')}>
                    <ChoiceCards
                      name="interest"
                      columns={1}
                      value={primaryInterest}
                      onChange={setPrimaryInterest}
                      options={INTERESTS.map((v) => ({
                        value: v,
                        title: t(INTEREST_KEYS[v] as Parameters<typeof t>[0]),
                      }))}
                    />
                  </Field>
                </div>
              )}

              {/* Step 3 — Security: credentials + consent */}
              {step === STEP_SECURITY && (
                <div className="space-y-5">
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
                        className="mt-0.5 size-4 shrink-0 accent-emerald"
                      />
                      <span className="text-sm leading-relaxed text-ink">
                        {t('signup.termsLabel')}
                      </span>
                    </label>
                    {errors.terms ? <p className="mt-1 text-xs text-rose">{errors.terms}</p> : null}
                  </div>
                </div>
              )}

              {/* Step 4 — Review */}
              {step === STEP_REVIEW && (
                <CompanyReview
                  basics={{ companyName, vertical, country, defaultLanguage }}
                  hint={t('signup.reviewHint')}
                  onEdit={(s) => {
                    setErrors({})
                    setStep(s)
                  }}
                  extraRows={[
                    {
                      label: t('signup.ownerName'),
                      value: [ownerFirstName, ownerLastName].filter(Boolean).join(' '),
                      step: STEP_YOU,
                    },
                    { label: t('signup.ownerEmail'), value: ownerEmail, step: STEP_YOU },
                    // Optional row — omitted entirely when no phone was given.
                    ...(phone.trim()
                      ? [{ label: t('signup.phone'), value: phone, step: STEP_YOU }]
                      : []),
                    {
                      label: t('signup.companySize'),
                      value: t(SIZE_KEYS[companySize as (typeof COMPANY_SIZES)[number]] as Parameters<typeof t>[0]),
                      step: STEP_YOU,
                    },
                    {
                      label: t('signup.primaryInterest'),
                      value: t(INTEREST_KEYS[primaryInterest as (typeof INTERESTS)[number]] as Parameters<typeof t>[0]),
                      step: STEP_YOU,
                    },
                    {
                      label: t('signup.ownerPassword'),
                      value: '',
                      step: STEP_SECURITY,
                      secret: true,
                    },
                  ]}
                />
              )}

              {/* API error — localized headline + the technical root cause (copyable) */}
              {mutation.isError && (
                <div className="mt-4 space-y-2">
                  <p className="rounded-xl border border-loss/30 bg-tint-loss px-3.5 py-2.5 text-sm text-loss">
                    {apiErrorMessage()}
                  </p>
                  <ErrorDetails error={mutation.error} />
                </div>
              )}
            </div>

            {/* Footer navigation — one full-width primary leads (design 5a) */}
            <div className="mt-[26px] flex items-center gap-2.5">
              {step > 0 ? (
                <Button
                  type="button"
                  variant="outline"
                  size="xl"
                  onClick={() => {
                    setErrors({})
                    setStep((s) => Math.max(0, s - 1))
                  }}
                  disabled={mutation.isPending}
                >
                  <ArrowLeft className="size-4" /> {t('common.back')}
                </Button>
              ) : null}
              {step < STEP_REVIEW ? (
                <Button type="submit" size="xl" className="flex-1">
                  {t('common.continue')} <ArrowRight className="size-[17px]" />
                </Button>
              ) : (
                <Button type="submit" size="xl" className="flex-1" disabled={mutation.isPending}>
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
          </div>
        </main>
      </div>
    </div>
  )
}
