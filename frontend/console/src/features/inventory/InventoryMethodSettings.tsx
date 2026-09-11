/**
 * InventoryMethodSettings — `/settings/inventory`, OWNER-ONLY (ADR 0067 §5, Phase D4/D5). The
 * perpetual-inventory election & activation console: read-only status once active, or an
 * explanation + a deliberate two-step activation while inactive. Reached from the settings nav
 * and from the catalog's value hero ("Native Persediaan", ADR 0081), which hands over the outlet's
 * catalog value in `location.state` so it can be offered as the opening figure.
 *
 * This route renders OUTSIDE the dashboard Shell (owner-gated, App.tsx). On the phone it is a
 * screen with a `ScreenHeader` ("Metode persediaan · Hanya pemilik"); from `sm` up it keeps the
 * minimal Wordmark topbar the other owner settings pages use. The activation is INLINE STEPS on
 * the page (inactive → form → confirm), never a modal.
 *
 * SAFETY FRAMING (by design, do not weaken): activating perpetual inventory books a real opening
 * GL entry and is effectively irreversible (no deactivate/amend flow) — this is NEVER a casual
 * toggle. The action always requires (1) an explicit "Activate…" entry point out of a plain-
 * language explanation, (2) a form step (cutover + counted value) THEN an explicit confirm step
 * with a permanence warning and a required acknowledgement, and (3) a stable per-submit
 * Idempotency-Key (mirrors `features/ap/api.ts`'s `useRecordPayment`) so a retried submit can
 * never double-activate / double-book the opening entry. Nothing here auto-activates anything.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation } from 'react-router-dom'
import { Boxes, Check, LogOut, TriangleAlert } from 'lucide-react'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { TextInput } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { FormSkeleton } from '@/components/ui/Skeleton'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import { AUTH_MODE } from '@/lib/config'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import { minorToMajorInput } from '@/features/pos/lib/registerFloat'
import { MicroLabel } from './InventoryChrome'
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

/** What the catalog hands over when its value hero opens this page. */
interface CatalogHandoff {
  catalogValueMinor: number
  currency: string
  outletName: string
}

function readHandoff(state: unknown): CatalogHandoff | null {
  const s = state as Partial<CatalogHandoff> | null
  if (!s || typeof s.catalogValueMinor !== 'number' || typeof s.currency !== 'string') return null
  return {
    catalogValueMinor: s.catalogValueMinor,
    currency: s.currency,
    outletName: s.outletName ?? '',
  }
}

export function InventoryMethodSettings() {
  const { t } = useTranslation()
  const auth = useAuth()
  const { company } = useSession()
  const location = useLocation()
  const handoff = readHandoff(location.state)

  if (!company) return null

  return (
    <div className="min-h-[100dvh] bg-paper">
      <ScreenHeader
        className="sm:hidden"
        title={t('settings.inventoryMethod.title')}
        subtitle={t('settings.inventoryMethod.phoneSubtitle')}
        backFallback="/"
      />
      {/* From `sm` up: the same minimal topbar as /me, FeaturesSettings.tsx and PaymentSettings.tsx. */}
      <header className="sticky top-0 z-30 hidden h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur sm:flex lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        <Link
          to="/"
          className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
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

      <main className="mx-auto flex w-full max-w-[820px] flex-col gap-6 px-4 pb-10 pt-2 sm:gap-7 sm:px-8 sm:py-10">
        <div className="hidden items-start gap-4 sm:flex">
          <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-tint-profit text-profit-ink">
            <Boxes className="size-6" strokeWidth={1.8} />
          </span>
          <div>
            <h1 className="font-display text-2xl font-extrabold tracking-[-0.025em] text-ink">
              {t('settings.inventoryMethod.title')}
            </h1>
            <p className="mt-1.5 max-w-xl text-base leading-relaxed text-ink-3">
              {t('settings.inventoryMethod.subtitle')}
            </p>
          </div>
        </div>

        <InventoryMethodContent session={company} handoff={handoff} />
      </main>
    </div>
  )
}

function InventoryMethodContent({
  session,
  handoff,
}: {
  session: CompanySession
  handoff: CatalogHandoff | null
}) {
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
    <ActiveStatus status={query.data} session={session} handoff={handoff} />
  ) : (
    <Activation session={session} handoff={handoff} />
  )
}

// ---------------------------------------------------------------------------
// Active — read-only status + the D5 negative-inventory monitor
// ---------------------------------------------------------------------------

function ActiveStatus({
  status,
  session,
  handoff,
}: {
  status: InventoryMethodStatus
  session: CompanySession
  handoff: CatalogHandoff | null
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = status.currency ?? session.baseCurrency
  const negative = status.inventoryAssetNegative

  const facts = [
    {
      label: t('settings.inventoryMethod.status.cutoverLabel'),
      value: status.cutoverPeriod ? formatPeriod(status.cutoverPeriod, locale) : '—',
    },
    {
      label: t('settings.inventoryMethod.status.activatedLabel'),
      value: formatActivatedAt(status.activatedAt, locale),
    },
    {
      label: t('settings.inventoryMethod.status.assetLabel'),
      value: formatMoney(status.inventoryAssetMinor, currency, locale),
      loss: negative,
    },
  ]

  return (
    <Card className="flex flex-col gap-5 p-5 sm:p-6">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="profit">
          <Check className="size-3" strokeWidth={3} aria-hidden="true" />
          {t('settings.inventoryMethod.status.activeBadge')}
        </Badge>
        {negative ? (
          <Badge tone="loss">
            <TriangleAlert className="size-3.5" aria-hidden="true" />
            {t('settings.inventoryMethod.status.negativeWarning')}
          </Badge>
        ) : null}
      </div>

      <div>
        <div className="text-xs font-semibold text-ink-3">
          {t('settings.inventoryMethod.status.assetHero')}
        </div>
        <div
          className={cn(
            'tnum mt-[7px] font-mono text-2xl font-bold leading-none tracking-[-0.02em]',
            negative ? 'text-loss' : 'text-ink',
          )}
        >
          {formatMoney(status.inventoryAssetMinor, currency, locale)}
        </div>
        <p
          className={cn(
            'mt-2 text-xs font-semibold leading-relaxed text-pretty',
            negative ? 'text-loss' : 'text-ink-3',
          )}
        >
          {negative
            ? t('settings.inventoryMethod.status.negativeNote')
            : handoff && handoff.catalogValueMinor === status.inventoryAssetMinor
              ? t('settings.inventoryMethod.status.matchesCatalog', { outlet: handoff.outletName })
              : t('settings.inventoryMethod.status.assetNote')}
        </p>
      </div>

      {negative ? (
        <p className="flex items-start gap-2.5 rounded-2xl bg-tint-loss px-4 py-3.5 text-sm leading-relaxed text-loss-ink">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          {t('settings.inventoryMethod.status.negativeHint')}
        </p>
      ) : null}

      <div>
        <MicroLabel>{t('settings.inventoryMethod.status.factsLabel')}</MicroLabel>
        <dl className="mt-2.5 sm:grid sm:grid-cols-3 sm:gap-6">
          {facts.map((f) => (
            <div
              key={f.label}
              className="flex items-baseline gap-2.5 border-t border-line/60 py-[9px] sm:block sm:border-0 sm:py-0"
            >
              <dt className="min-w-0 flex-1 text-xs font-medium text-ink-3 sm:text-2xs sm:font-bold sm:uppercase sm:tracking-[0.06em] sm:text-ink-400">
                {f.label}
              </dt>
              <dd
                className={cn(
                  'tnum shrink-0 font-mono text-xs font-semibold sm:mt-1.5 sm:text-lg',
                  f.loss ? 'text-loss' : 'text-ink',
                )}
              >
                {f.value}
              </dd>
            </div>
          ))}
        </dl>
      </div>

      <p className="rounded-[14px] bg-paper px-3.5 py-3.5 text-xs leading-relaxed text-ink-2">
        {t('settings.inventoryMethod.status.ownerFootnote')}
      </p>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Inactive — explanation, then the two inline steps (form → confirm)
// ---------------------------------------------------------------------------

type ActivationStep = 'inactive' | 'form' | 'confirm'

function Activation({
  session,
  handoff,
}: {
  session: CompanySession
  handoff: CatalogHandoff | null
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const currency = session.baseCurrency
  const activate = useActivateInventoryMethod(session)

  const [step, setStep] = useState<ActivationStep>('inactive')
  const [cutoverPeriod, setCutoverPeriod] = useState(defaultCutoverPeriod())
  // Default to '0' so a zero-inventory owner can advance immediately ("Nol juga sah").
  const [openingValueInput, setOpeningValueInput] = useState('0')
  const [acknowledged, setAcknowledged] = useState(false)

  const openingValueMinor = parseOpeningInventoryValueInput(openingValueInput, currency)
  const formReady = canAdvanceActivationForm(cutoverPeriod, openingValueInput, currency)
  const errorKey = activate.isError ? activationErrorKey(activate.error) : null
  // The catalog's figure is only offered in the company's own currency — a mismatch is not a
  // suggestion, it is a bug upstream, and silently mixing them would book the wrong number.
  const catalogValue = handoff && handoff.currency === currency ? handoff : null
  const catalogValueText = catalogValue
    ? formatMoney(catalogValue.catalogValueMinor, currency, locale)
    : null

  function handleActivate() {
    if (openingValueMinor == null || !acknowledged) return
    // One key per submit attempt: minted here (not inside the mutation fn), so TanStack Query's
    // automatic retries of THIS call reuse it, while a fresh attempt (after Back → edit →
    // Continue again, or a later retry) gets a new one — features/ap/api.ts's idiom exactly.
    activate.mutate(
      {
        cutoverPeriod,
        openingInventoryValueMinor: openingValueMinor,
        currency,
        idempotencyKey: crypto.randomUUID(),
      },
      { onSuccess: () => setStep('inactive') },
    )
  }

  if (step === 'inactive') {
    return (
      <Card className="flex flex-col gap-5 p-5 sm:p-6">
        <div>
          <h2 className="font-display text-xl font-bold tracking-[-0.02em] text-ink">
            {t('settings.inventoryMethod.inactive.heading')}
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-ink-2 text-pretty">
            {t('settings.inventoryMethod.inactive.body')}
          </p>
        </div>

        <ul className="flex flex-col gap-3">
          {(['bullet1', 'bullet2', 'bullet3'] as const).map((key) => (
            <li
              key={key}
              className="flex items-start gap-2.5 text-xs leading-relaxed text-ink-2"
            >
              <span
                className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full bg-amber-tint text-amber"
                aria-hidden="true"
              >
                <Check className="size-2.5" strokeWidth={3.2} />
              </span>
              {t(`settings.inventoryMethod.inactive.${key}`)}
            </li>
          ))}
        </ul>

        {catalogValueText ? (
          <p className="rounded-[14px] bg-tint-profit/40 px-3.5 py-3.5 text-xs leading-relaxed text-ink-2 text-pretty">
            {t('settings.inventoryMethod.inactive.catalogValue', {
              outlet: catalogValue?.outletName,
              value: catalogValueText,
            })}
          </p>
        ) : null}

        <Button type="button" size="xl" onClick={() => setStep('form')}>
          {t('settings.inventoryMethod.inactive.action')}
        </Button>
      </Card>
    )
  }

  if (step === 'form') {
    return (
      <Card className="flex flex-col gap-5 p-5 sm:p-6">
        <p className="text-sm leading-relaxed text-ink-2 text-pretty">
          {t('settings.inventoryMethod.activate.formIntro')}
        </p>

        <div>
          <MicroLabel>{t('settings.inventoryMethod.activate.cutoverLabel')}</MicroLabel>
          <TextInput
            id="im-cutover"
            type="month"
            value={cutoverPeriod}
            onChange={(e) => setCutoverPeriod(e.target.value)}
            aria-label={t('settings.inventoryMethod.activate.cutoverLabel')}
            className="mt-[7px] rounded-[14px] font-semibold"
            required
          />
          <p className="mt-[7px] text-xs leading-relaxed text-ink-400">
            {t('settings.inventoryMethod.activate.cutoverHint')}
          </p>
        </div>

        <div>
          <MicroLabel>
            {t('settings.inventoryMethod.activate.openingValueLabel', { currency })}
          </MicroLabel>
          <input
            id="im-opening-value"
            type="text"
            inputMode="decimal"
            value={openingValueInput}
            onChange={(e) => setOpeningValueInput(e.target.value)}
            placeholder="0"
            aria-label={t('settings.inventoryMethod.activate.openingValueLabel', { currency })}
            className="tnum mt-[7px] h-[52px] w-full rounded-[14px] border-[1.5px] border-ink bg-surface px-4 text-right font-mono text-xl font-bold text-ink focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
          <p className="mt-[9px] text-xs font-medium leading-relaxed text-ink-3">
            {openingValueMinor != null && openingValueMinor > 0
              ? formatMoney(openingValueMinor, currency, locale)
              : t('settings.inventoryMethod.activate.openingValueHint')}
          </p>
          {catalogValue && catalogValueText ? (
            <button
              type="button"
              onClick={() =>
                setOpeningValueInput(minorToMajorInput(catalogValue.catalogValueMinor, currency))
              }
              className="mt-2.5 min-h-10 rounded-xl border border-profit-line bg-tint-profit px-3.5 text-xs font-bold text-profit-ink transition-transform active:scale-[.985] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              {t('settings.inventoryMethod.activate.useCatalogValue', { value: catalogValueText })}
            </button>
          ) : null}
          <p className="mt-2 text-xs leading-relaxed text-ink-400 text-pretty">
            {t('settings.inventoryMethod.activate.zeroOk')}
          </p>
        </div>

        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="xl"
            className="shrink-0 px-5"
            onClick={() => setStep('inactive')}
          >
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            size="xl"
            className="flex-1"
            disabled={!formReady}
            onClick={() => {
              setAcknowledged(false)
              setStep('confirm')
            }}
          >
            {t('common.continue')}
          </Button>
        </div>
      </Card>
    )
  }

  return (
    <Card className="flex flex-col gap-[18px] p-5 sm:p-6">
      <p className="flex items-start gap-2.5 rounded-2xl bg-amber-tint px-4 py-[15px] text-xs font-semibold leading-relaxed text-amber-2">
        <TriangleAlert className="mt-0.5 size-[17px] shrink-0 text-amber" aria-hidden="true" />
        {t('settings.inventoryMethod.activate.confirmWarning')}
      </p>

      <dl className="rounded-2xl border border-line px-4 py-[15px] text-xs">
        <div className="flex items-baseline justify-between gap-3 pb-2.5">
          <dt className="font-medium text-ink-3">
            {t('settings.inventoryMethod.activate.confirmCutover')}
          </dt>
          <dd className="font-bold text-ink">{formatPeriod(cutoverPeriod, locale)}</dd>
        </div>
        <div className="flex items-baseline justify-between gap-3 border-t border-line/60 pt-2.5">
          <dt className="font-medium text-ink-3">
            {t('settings.inventoryMethod.activate.confirmOpeningValue')}
          </dt>
          <dd className="tnum font-mono text-sm font-bold text-ink">
            {formatMoney(openingValueMinor ?? 0, currency, locale)}
          </dd>
        </div>
      </dl>

      <p className="text-xs leading-relaxed text-ink-3 text-pretty">
        {t('settings.inventoryMethod.activate.confirmNote', {
          period: formatPeriod(cutoverPeriod, locale),
        })}
      </p>

      <label
        className={cn(
          'flex cursor-pointer items-start gap-3 rounded-2xl border p-[15px] transition-colors',
          acknowledged ? 'border-profit-line bg-tint-profit/40' : 'border-line bg-surface',
        )}
      >
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={(e) => setAcknowledged(e.target.checked)}
          className="mt-0.5 size-5 shrink-0 accent-emerald"
        />
        <span className="text-xs font-medium leading-relaxed text-ink-2">
          {t('settings.inventoryMethod.activate.acknowledge')}
        </span>
      </label>

      {errorKey ? (
        <p className="text-sm text-loss" role="alert">
          {t(errorKey)}
        </p>
      ) : null}

      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          size="xl"
          className="shrink-0 px-5"
          disabled={activate.isPending}
          onClick={() => setStep('form')}
        >
          {t('common.back')}
        </Button>
        <Button
          type="button"
          size="xl"
          className="flex-1"
          disabled={!acknowledged || activate.isPending}
          onClick={handleActivate}
        >
          {activate.isPending ? <Spinner /> : t('settings.inventoryMethod.activate.confirmAction')}
        </Button>
      </div>
    </Card>
  )
}
