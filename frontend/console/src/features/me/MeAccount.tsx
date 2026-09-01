/**
 * MeAccount (/me/account) — the employee self-service "Account settings" screen (two-app +
 * outlet-terminal program, Phase 3): a login changes their OWN operator PIN and password here.
 * Reachable from both apps (console + the dedicated Employee app, via the `@` → ../console/src
 * alias) and from every business role — same open gate as `/me` itself (see app/App.tsx).
 *
 * Two independent cards:
 *  - Change PIN: a local-state 4-6 digit + confirm form → `useSetMyOperatorPin` (write-only; the
 *    PIN is never read back — rule 6). Never logged.
 *  - Change password: Keycloak owns the credential entirely — the button just opens Keycloak's
 *    own secure change-password page via `auth.changePassword()` (no form, no password field ever
 *    touches this app).
 *  - Privacy & account deletion: Google Play requires an app that lets users create an account to
 *    expose a deletion route from INSIDE the app as well as on the public web. This screen is that
 *    route for both shells. Both targets are STATIC files served by the CONSOLE image, so they
 *    are plain <a> (not react-router <Link>) pointed at the absolute PRIVACY_URL /
 *    DELETE_ACCOUNT_URL — see lib/config.ts for why relative would break in the Employee app.
 */
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { KeyRound, Lock, LogOut, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, TextInput } from '@/components/ui/Field'
import { Wordmark } from '@/components/Wordmark'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE, DELETE_ACCOUNT_URL, PRIVACY_URL } from '@/lib/config'
import { useSetMyOperatorPin } from './api'

const PIN_PATTERN = /^[0-9]{4,6}$/

export function MeAccount() {
  const { t } = useTranslation()
  const auth = useAuth()
  const companyId = auth.companyId ?? 'me'
  const actor = auth.actor

  return (
    <div className="min-h-[100dvh] bg-paper">
      {/* Phone chrome (Native Console Android): back to /me. */}
      <ScreenHeader className="sm:hidden" title={t('me.account.title')} backTo="/me" />
      {/* Topbar — mirrors features/expenses/MyExpenses.tsx's chrome */}
      <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur max-sm:hidden lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        <Link
          to="/me"
          className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
        >
          {t('me.expenses.back')}
        </Link>
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            className="flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
          >
            <LogOut className="size-4" />
            <span className="hidden sm:inline">{t('nav.logout')}</span>
          </button>
        ) : null}
      </header>

      <main className="mx-auto w-full max-w-[640px] px-5 py-8 max-sm:px-4 max-sm:py-4 lg:px-8">
        <div className="max-sm:hidden">
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-ink">
            {t('me.account.title')}
          </h1>
          <p className="text-sm text-ink-3">{t('me.account.subtitle')}</p>
        </div>

        <div className="mt-6 flex flex-col gap-5 max-sm:mt-4">
          <ChangePinCard companyId={companyId} actor={actor} />
          <ChangePasswordCard />
          <PrivacyCard />
        </div>
      </main>
    </div>
  )
}

function ChangePinCard({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [pin, setPin] = useState('')
  const [confirmPin, setConfirmPin] = useState('')
  const [saved, setSaved] = useState(false)
  const mutation = useSetMyOperatorPin({ companyId, actor })

  const valid = PIN_PATTERN.test(pin)
  const mismatch = confirmPin.length > 0 && pin !== confirmPin

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!valid || pin !== confirmPin) return
    mutation.mutate(
      { newPin: pin },
      {
        onSuccess: () => {
          setSaved(true)
          setPin('')
          setConfirmPin('')
        },
      },
    )
  }

  return (
    <Card className="p-5 sm:p-6">
      <div className="flex items-center gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          <KeyRound className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-ink">
            {t('me.account.pin.title')}
          </h2>
          <p className="text-xs text-ink-3">{t('me.account.pin.body')}</p>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="mt-4 space-y-4">
        <Field label={t('me.account.pin.newLabel')} htmlFor="my-operator-pin">
          <TextInput
            id="my-operator-pin"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            maxLength={6}
            value={pin}
            onChange={(e) => {
              setPin(e.target.value.replace(/\D/g, ''))
              setSaved(false)
            }}
            required
          />
        </Field>

        <Field
          label={t('me.account.pin.confirmLabel')}
          htmlFor="my-operator-pin-confirm"
          error={mismatch ? t('me.account.pin.mismatch') : undefined}
        >
          <TextInput
            id="my-operator-pin-confirm"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            maxLength={6}
            value={confirmPin}
            onChange={(e) => {
              setConfirmPin(e.target.value.replace(/\D/g, ''))
              setSaved(false)
            }}
            required
          />
        </Field>

        {pin.length > 0 && !valid ? (
          <p className="text-sm text-loss">{t('me.account.pin.invalid')}</p>
        ) : mutation.isError ? (
          <p className="text-sm text-loss">{t('me.account.pin.error')}</p>
        ) : saved ? (
          <p className="text-sm text-profit-ink">{t('me.account.pin.saved')}</p>
        ) : null}

        <div className="flex justify-end">
          <Button type="submit" disabled={mutation.isPending || !valid || pin !== confirmPin}>
            {mutation.isPending ? t('me.account.pin.submitting') : t('me.account.pin.submit')}
          </Button>
        </div>
      </form>
    </Card>
  )
}

function ChangePasswordCard() {
  const { t } = useTranslation()
  const auth = useAuth()

  return (
    <Card className="p-5 sm:p-6">
      <div className="flex items-center gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          <Lock className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-ink">
            {t('me.account.password.title')}
          </h2>
          <p className="text-xs text-ink-3">{t('me.account.password.body')}</p>
        </div>
      </div>

      <div className="mt-4">
        <Button type="button" variant="outline" onClick={auth.changePassword}>
          {t('me.account.password.button')}
        </Button>
        <p className="mt-2.5 text-xs leading-relaxed text-ink-3">
          {t('me.account.password.hint')}
        </p>
      </div>
    </Card>
  )
}

function PrivacyCard() {
  const { t } = useTranslation()
  const link =
    'rounded-xl px-3 py-2 text-sm font-semibold text-emerald-2 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-emerald'

  return (
    <Card className="p-5 sm:p-6">
      <div className="flex items-center gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-emerald-tint text-emerald-2">
          <ShieldCheck className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-ink">
            {t('me.account.privacy.title')}
          </h2>
          <p className="text-xs text-ink-3">{t('me.account.privacy.body')}</p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-1">
        <a className={link} href={PRIVACY_URL}>
          {t('me.account.privacy.policy')}
        </a>
        <a className={link} href={DELETE_ACCOUNT_URL}>
          {t('me.account.privacy.delete')}
        </a>
      </div>
      <p className="mt-2.5 text-xs leading-relaxed text-ink-3">{t('me.account.privacy.hint')}</p>
    </Card>
  )
}
