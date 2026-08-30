/**
 * InventoryMethodSettings — `/settings/inventory`, OWNER-ONLY (ADR 0067 §5, Phase D4/D5). The
 * perpetual-inventory election & activation console: read-only status once active, or an
 * explanation + a deliberate two-step activation dialog while inactive.
 *
 * Copies the exact idiom of `features/settings/FeaturesSettings.tsx` / `features/payments/
 * PaymentSettings.tsx`: its own minimal topbar (this route renders OUTSIDE the dashboard Shell in
 * App.tsx, owner-gated), and the error-card/skeleton-while-pending gate every finance settings
 * surface uses for its query.
 *
 * SAFETY FRAMING (by design, do not weaken): activating perpetual inventory books a real opening
 * GL entry and is effectively irreversible (no deactivate/amend flow) — this is NEVER a casual
 * toggle. The action always requires (1) an explicit "Activate…" entry point out of a plain-
 * language explanation card, (2) a two-step dialog (enter cutover + counted value, THEN an
 * explicit confirm step with a permanence warning and a required acknowledgement checkbox), and
 * (3) a stable per-submit Idempotency-Key (mirrors `features/ap/api.ts`'s `useRecordPayment`) so a
 * retried submit can never double-activate / double-book the opening entry. Nothing here
 * auto-activates anything.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Boxes, Check, LogOut, TriangleAlert } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { FormSkeleton } from '@/components/ui/Skeleton'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import {
  activationErrorKey,
  canAdvanceActivationForm,
  defaultCutoverPeriod,
  parseOpeningInventoryValueInput,
} from './activationForm'
import { formatActivatedAt } from './format'
import {
  useActivateInventoryMethod,
  useInventoryMethod,
  type InventoryMethodStatus,
} from './inventoryMethodApi'

export function InventoryMethodSettings() {
  const { t } = useTranslation()
  const auth = useAuth()
  const { company } = useSession()

  if (!company) return null

  return (
    <div className="min-h-[100dvh] bg-paper">
      {/* This route renders OUTSIDE the dashboard Shell (owner-only, mirrors /settings/features'
          and /settings/payments' registration in App.tsx), so it carries its own minimal topbar —
          same idiom as /me, FeaturesSettings.tsx, and PaymentSettings.tsx. */}
      <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        <Link
          to="/"
          className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {t('me.toDashboard')}
        </Link>
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            className="flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink"
          >
            <LogOut className="size-4" />
            <span className="hidden sm:inline">{t('nav.logout')}</span>
          </button>
        ) : null}
      </header>

      <main className="mx-auto flex w-full max-w-[820px] flex-col gap-7 px-5 py-8 sm:px-8 sm:py-10">
        <div className="flex items-start gap-4">
          <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-brand-50 text-brand-600">
            <Boxes className="size-6" strokeWidth={1.8} />
          </span>
          <div>
            <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
              {t('settings.inventoryMethod.title')}
            </h1>
            <p className="mt-1.5 max-w-xl text-[15px] leading-relaxed text-ink-3">
              {t('settings.inventoryMethod.subtitle')}
            </p>
          </div>
        </div>

        <InventoryMethodContent session={company} />
      </main>
    </div>
  )
}

function InventoryMethodContent({ session }: { session: CompanySession }) {
  const { t } = useTranslation()
  const query = useInventoryMethod(session)

  if (query.isError) {
    return (
      <Card className="flex items-center gap-2 p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto size-5 shrink-0" />
        {t('settings.inventoryMethod.loadError')}
      </Card>
    )
  }

  if (!query.data) {
    return (
      <Card className="p-6">
        <FormSkeleton fields={3} />
      </Card>
    )
  }

  return query.data.active ? (
    <ActiveStatusCard status={query.data} session={session} />
  ) : (
    <InactiveCard session={session} />
  )
}

// ---------------------------------------------------------------------------
// Active — read-only status + the D5 negative-inventory monitor
// ---------------------------------------------------------------------------

function ActiveStatusCard({
  status,
  session,
}: {
  status: InventoryMethodStatus
  session: CompanySession
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = status.currency ?? session.baseCurrency

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="emerald">{t('settings.inventoryMethod.status.activeBadge')}</Badge>
        {status.inventoryAssetNegative ? (
          <Badge tone="loss">
            <TriangleAlert className="size-3.5" aria-hidden />
            {t('settings.inventoryMethod.status.negativeWarning')}
          </Badge>
        ) : null}
      </div>

      <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <StatusRow
          label={t('settings.inventoryMethod.status.cutoverLabel')}
          value={status.cutoverPeriod ? formatPeriod(status.cutoverPeriod, locale) : '—'}
        />
        <StatusRow
          label={t('settings.inventoryMethod.status.activatedLabel')}
          value={formatActivatedAt(status.activatedAt, locale)}
        />
        <StatusRow
          label={t('settings.inventoryMethod.status.assetLabel')}
          value={formatMoney(status.inventoryAssetMinor, currency, locale)}
          valueClassName={status.inventoryAssetNegative ? 'text-loss' : undefined}
        />
      </dl>

      {status.inventoryAssetNegative ? (
        <p className="flex items-start gap-2.5 rounded-2xl bg-tint-loss px-4 py-3.5 text-sm leading-relaxed text-loss">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t('settings.inventoryMethod.status.negativeHint')}
        </p>
      ) : null}
    </Card>
  )
}

function StatusRow({
  label,
  value,
  valueClassName,
}: {
  label: string
  value: string
  valueClassName?: string
}) {
  return (
    <div>
      <dt className="text-xs font-bold uppercase tracking-[0.06em] text-ink-3">{label}</dt>
      <dd className={`tnum mt-1 font-mono text-lg font-semibold text-ink ${valueClassName ?? ''}`}>
        {value}
      </dd>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inactive — explanation + the activation entry point
// ---------------------------------------------------------------------------

function InactiveCard({ session }: { session: CompanySession }) {
  const { t } = useTranslation()
  const [dialogOpen, setDialogOpen] = useState(false)

  return (
    <>
      <Card className="flex flex-col gap-5 p-6">
        <div>
          <h2 className="font-display text-lg font-bold text-ink">
            {t('settings.inventoryMethod.inactive.heading')}
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-ink-3">
            {t('settings.inventoryMethod.inactive.body')}
          </p>
        </div>

        <ul className="flex flex-col gap-2.5">
          {(['bullet1', 'bullet2', 'bullet3'] as const).map((key) => (
            <li key={key} className="flex items-start gap-2.5 text-sm text-ink-2">
              <span
                className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full bg-amber-tint text-amber-2"
                aria-hidden
              >
                <Check className="size-3" strokeWidth={2.5} />
              </span>
              {t(`settings.inventoryMethod.inactive.${key}`)}
            </li>
          ))}
        </ul>

        <div>
          <Button type="button" onClick={() => setDialogOpen(true)}>
            {t('settings.inventoryMethod.inactive.action')}
          </Button>
        </div>
      </Card>

      {dialogOpen ? (
        <ActivationDialog session={session} onClose={() => setDialogOpen(false)} />
      ) : null}
    </>
  )
}

// ---------------------------------------------------------------------------
// Activation dialog — a two-step confirm, never a one-tap toggle
// ---------------------------------------------------------------------------

/** Simple modal overlay — closes on backdrop click or Escape (mirrors features/ap/parts.tsx's
 *  DialogOverlay; each feature keeps its own copy — see org/hr precedent). */
function DialogOverlay({
  children,
  onClose,
}: {
  children: React.ReactNode
  onClose: () => void
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="max-h-full w-full max-w-md overflow-y-auto p-6">{children}</Card>
    </div>
  )
}

type ActivationStep = 'form' | 'confirm'

function ActivationDialog({
  session,
  onClose,
}: {
  session: CompanySession
  onClose: () => void
}) {
  useBackDismiss(onClose)
  useScrollLock()
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = session.baseCurrency
  const activate = useActivateInventoryMethod(session)

  const [step, setStep] = useState<ActivationStep>('form')
  const [cutoverPeriod, setCutoverPeriod] = useState(defaultCutoverPeriod())
  // Default to '0' so a zero-inventory owner can advance immediately (matches the "leave at 0" copy).
  const [openingValueInput, setOpeningValueInput] = useState('0')
  const [acknowledged, setAcknowledged] = useState(false)

  const openingValueMinor = parseOpeningInventoryValueInput(openingValueInput, currency)
  const formReady = canAdvanceActivationForm(cutoverPeriod, openingValueInput, currency)
  const errorKey = activate.isError ? activationErrorKey(activate.error) : null

  function handleContinue(e: React.FormEvent) {
    e.preventDefault()
    if (!formReady) return
    setStep('confirm')
  }

  function handleActivate() {
    if (openingValueMinor == null || !acknowledged) return
    // One key per submit attempt: minted here (not inside the mutation fn), so TanStack Query's
    // automatic retries of THIS call reuse it, while a fresh dialog open (after Back → edit →
    // Continue again, or a later retry) gets a new one — features/ap/api.ts's useRecordPayment
    // idiom exactly.
    activate.mutate(
      {
        cutoverPeriod,
        openingInventoryValueMinor: openingValueMinor,
        currency,
        idempotencyKey: crypto.randomUUID(),
      },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      {step === 'form' ? (
        <form onSubmit={handleContinue} className="flex flex-col gap-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('settings.inventoryMethod.activate.formTitle')}
          </h2>
          <p className="text-sm leading-relaxed text-ink-3">
            {t('settings.inventoryMethod.activate.formIntro')}
          </p>

          <Field
            label={t('settings.inventoryMethod.activate.cutoverLabel')}
            htmlFor="im-cutover"
            hint={t('settings.inventoryMethod.activate.cutoverHint')}
          >
            <TextInput
              id="im-cutover"
              type="month"
              value={cutoverPeriod}
              onChange={(e) => setCutoverPeriod(e.target.value)}
              required
            />
          </Field>

          <Field
            label={t('settings.inventoryMethod.activate.openingValueLabel', { currency })}
            htmlFor="im-opening-value"
            hint={
              openingValueMinor != null
                ? formatMoney(openingValueMinor, currency, locale)
                : t('settings.inventoryMethod.activate.openingValueHint')
            }
          >
            <TextInput
              id="im-opening-value"
              type="number"
              min="0"
              inputMode="decimal"
              value={openingValueInput}
              onChange={(e) => setOpeningValueInput(e.target.value)}
              placeholder="0"
            />
          </Field>

          <div className="flex gap-2">
            <Button type="button" variant="outline" className="flex-1" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" className="flex-1" disabled={!formReady}>
              {t('common.continue')}
            </Button>
          </div>
        </form>
      ) : (
        <div className="flex flex-col gap-4">
          <h2 className="font-display text-lg font-semibold text-ink">
            {t('settings.inventoryMethod.activate.confirmTitle')}
          </h2>

          <p className="flex items-start gap-2.5 rounded-2xl bg-amber-tint px-4 py-3.5 text-sm leading-relaxed text-amber-2">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            {t('settings.inventoryMethod.activate.confirmWarning')}
          </p>

          <dl className="flex flex-col gap-2 rounded-2xl border border-line bg-paper p-4 text-sm">
            <div className="flex items-baseline justify-between gap-3">
              <dt className="text-ink-3">{t('settings.inventoryMethod.activate.confirmCutover')}</dt>
              <dd className="font-semibold text-ink">{formatPeriod(cutoverPeriod, locale)}</dd>
            </div>
            <div className="flex items-baseline justify-between gap-3">
              <dt className="text-ink-3">
                {t('settings.inventoryMethod.activate.confirmOpeningValue')}
              </dt>
              <dd className="tnum font-mono font-semibold text-ink">
                {formatMoney(openingValueMinor ?? 0, currency, locale)}
              </dd>
            </div>
          </dl>

          <p className="text-xs leading-relaxed text-ink-3">
            {t('settings.inventoryMethod.activate.confirmNote', {
              period: formatPeriod(cutoverPeriod, locale),
            })}
          </p>

          <label className="flex cursor-pointer items-start gap-3 rounded-2xl border border-line bg-surface p-4">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => setAcknowledged(e.target.checked)}
              className="mt-0.5 size-4 accent-emerald"
            />
            <span className="text-sm text-ink-2">
              {t('settings.inventoryMethod.activate.acknowledge')}
            </span>
          </label>

          {errorKey ? <p className="text-sm text-loss">{t(errorKey)}</p> : null}

          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              className="flex-1"
              disabled={activate.isPending}
              onClick={() => setStep('form')}
            >
              {t('common.back')}
            </Button>
            <Button
              type="button"
              className="flex-1"
              disabled={!acknowledged || activate.isPending}
              onClick={handleActivate}
            >
              {activate.isPending ? <Spinner /> : t('settings.inventoryMethod.activate.confirmAction')}
            </Button>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}
