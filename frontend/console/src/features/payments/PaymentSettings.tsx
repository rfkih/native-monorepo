/**
 * PaymentSettings — `/settings/payments`, OWNER-ONLY (ADR 0045): the QRIS payment-mode console.
 * A company has ONE QRIS mode (MANUAL/STATIC/GATEWAY) with an optional per-outlet MODE override
 * (mode only — gateway credentials are company-level; the STATIC image MAY be overridden per
 * outlet, inheriting the company image otherwise).
 *
 * OUTLET-FIRST layout (owner request): the outlet list is the primary unit — each outlet shows
 * its EFFECTIVE mode (own override, or "inherits company default") and image state, and expands
 * inline into a per-outlet editor (mode incl. an explicit "inherit" choice, its own static image,
 * a gateway hint when relevant). The company mode/image/gateway cards live BELOW, framed as the
 * fallback every outlet without its own setting uses.
 *
 * Copies the exact idiom of `features/settings/FeaturesSettings.tsx`: its own minimal topbar
 * (this route is registered OUTSIDE the dashboard Shell in App.tsx, owner-gated), and the
 * error-card/Spinner-while-pending gate `features/channels/Channels.tsx` uses for its query.
 * Deliberately carries NO PageKey and NO featureTier entry — owner-gated only, never tier-gated
 * (a QRIS mode is a payments-integrity decision, not a plan-tier feature).
 */
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Link } from 'react-router-dom'
import { ChevronDown, ChevronRight, LogOut, QrCode, Trash2, TriangleAlert, UploadCloud } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards, type Choice } from '@/components/ui/ChoiceCards'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession, type CompanySession } from '@/lib/session'
import { useOutlets, type OutletSummary } from '@/features/org/api'
import {
  useDeleteOutletOverride,
  useDeleteStaticQr,
  useOwnerPaymentSettings,
  useStaticQrImageUrl,
  useUploadStaticQr,
  useUpsertOutletOverride,
  useUpsertPaymentSettings,
  type GatewayEnvironment,
  type OwnerPaymentSettingsResponse,
  type PaymentSettingsRow,
  type UpsertCompanySettingsBody,
} from './api'
import type { QrisMode } from './effectiveMode'

/** Client-side pre-flight only (the server is the authority: 413 over-size, 422 bad magic bytes)
 *  — mirrors the 2 MiB cap the backend documents for this endpoint. */
const STATIC_MAX_BYTES = 2 * 1024 * 1024

/** The human label for a QRIS mode ("Manual" / "Your own QRIS code" / "Payment gateway"), shared
 *  by the company mode picker and every per-outlet editor/badge. */
function modeLabel(t: TFunction, mode: QrisMode): string {
  return t(`settings.payments.mode.${mode.toLowerCase()}` as Parameters<typeof t>[0])
}

export function PaymentSettings() {
  const { t } = useTranslation()
  const auth = useAuth()
  const { company } = useSession()

  if (!company) return null

  return (
    <div className="min-h-screen bg-paper">
      {/* This route renders OUTSIDE the dashboard Shell (owner-only, mirrors /settings/features'
          registration in App.tsx), so it carries its own minimal topbar — same idiom as /me and
          FeaturesSettings.tsx. */}
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

      <main className="mx-auto flex w-full max-w-[900px] flex-col gap-7 px-5 py-8 sm:px-8 sm:py-10">
        <div className="flex items-start gap-4">
          <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-brand-50 text-brand-600">
            <QrCode className="size-6" strokeWidth={1.8} />
          </span>
          <div>
            <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
              {t('settings.payments.title')}
            </h1>
            <p className="mt-1.5 max-w-xl text-[15px] leading-relaxed text-ink-3">
              {t('settings.payments.subtitle')}
            </p>
          </div>
        </div>

        <PaymentSettingsContent session={company} />
      </main>
    </div>
  )
}

function PaymentSettingsContent({ session }: { session: CompanySession }) {
  const { t } = useTranslation()
  const ownerSettings = useOwnerPaymentSettings(session)

  if (ownerSettings.isError) {
    return (
      <Card className="flex items-center gap-2 p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto size-5 shrink-0" />
        {t('settings.payments.loadError')}
      </Card>
    )
  }

  if (!ownerSettings.data) {
    return (
      <Card className="p-10 text-center">
        <Spinner className="mx-auto text-brand-500" />
      </Card>
    )
  }

  return <PaymentSettingsLoaded session={session} data={ownerSettings.data} />
}

function PaymentSettingsLoaded({
  session,
  data,
}: {
  session: CompanySession
  data: OwnerPaymentSettingsResponse
}) {
  const { t } = useTranslation()
  const currentMode: QrisMode = data.companyDefault?.mode ?? 'MANUAL'
  const isIdr = session.baseCurrency === 'IDR'
  const gateway = data.companyDefault?.gateway ?? null

  return (
    <>
      {/* PRIMARY: every outlet, its effective QRIS state, and an inline editor. */}
      <OutletsSection
        session={session}
        companyMode={currentMode}
        companyGateway={gateway}
        isIdr={isIdr}
        outletOverrides={data.outletOverrides}
      />

      {/* FALLBACK: the company default every outlet without its own setting uses. */}
      <div>
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.company.heading')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('settings.payments.company.subtitle')}</p>
      </div>
      <ModePickerCard session={session} currentMode={currentMode} isIdr={isIdr} />
      <CompanyStaticImageCard session={session} companyDefault={data.companyDefault} />
      <GatewayCard session={session} currentMode={currentMode} gateway={gateway} />
    </>
  )
}

// ---------------------------------------------------------------------------
// Outlets — the primary unit
// ---------------------------------------------------------------------------

function OutletsSection({
  session,
  companyMode,
  companyGateway,
  isIdr,
  outletOverrides,
}: {
  session: CompanySession
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
  outletOverrides: PaymentSettingsRow[]
}) {
  const { t } = useTranslation()
  const outletsQuery = useOutlets(session.companyId, session.actor)
  const overrideByOutletId = new Map(outletOverrides.map((row) => [row.outletId, row]))
  const [expandedOutletId, setExpandedOutletId] = useState<string | null>(null)

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.outlets.heading')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('settings.payments.outlets.hint')}</p>
      </div>

      {outletsQuery.data && outletsQuery.data.length > 0 ? (
        <div className="flex flex-col gap-3">
          {outletsQuery.data.map((outlet) => (
            <OutletRow
              key={outlet.id}
              session={session}
              outlet={outlet}
              row={overrideByOutletId.get(outlet.id) ?? null}
              companyMode={companyMode}
              companyGateway={companyGateway}
              isIdr={isIdr}
              expanded={expandedOutletId === outlet.id}
              onToggle={() =>
                setExpandedOutletId((current) => (current === outlet.id ? null : outlet.id))
              }
            />
          ))}
        </div>
      ) : outletsQuery.isLoading ? (
        <Spinner className="mx-auto text-brand-500" />
      ) : (
        <p className="text-sm text-ink-3">{t('settings.payments.outlets.empty')}</p>
      )}
    </Card>
  )
}

function OutletRow({
  session,
  outlet,
  row,
  companyMode,
  companyGateway,
  isIdr,
  expanded,
  onToggle,
}: {
  session: CompanySession
  outlet: OutletSummary
  row: PaymentSettingsRow | null
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
  expanded: boolean
  onToggle: () => void
}) {
  const { t } = useTranslation()
  const hasOverride = row != null
  const effectiveMode: QrisMode = row?.mode ?? companyMode
  const hasImage = row?.hasStaticImage ?? false
  const panelId = `outlet-qris-panel-${outlet.id}`

  return (
    <div className="rounded-2xl border border-line">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={onToggle}
        className="flex w-full flex-wrap items-center justify-between gap-3 rounded-2xl p-4 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
      >
        <span className="flex min-w-0 items-center gap-2.5">
          {expanded ? (
            <ChevronDown className="size-4 shrink-0 text-ink-3" aria-hidden />
          ) : (
            <ChevronRight className="size-4 shrink-0 text-ink-3" aria-hidden />
          )}
          <span className="truncate font-semibold text-ink">{outlet.name}</span>
        </span>
        <span className="flex flex-wrap items-center gap-2">
          <Badge tone={hasOverride ? 'emerald' : 'neutral'}>
            {hasOverride
              ? t('settings.payments.outlet.effectiveOwn', { mode: modeLabel(t, effectiveMode) })
              : t('settings.payments.outlet.effectiveInherits', { mode: modeLabel(t, companyMode) })}
          </Badge>
          <Badge tone={hasImage ? 'emerald' : 'neutral'}>
            {hasImage ? t('settings.payments.static.overrideSet') : t('settings.payments.static.usingCompany')}
          </Badge>
        </span>
      </button>

      {expanded ? (
        <div id={panelId} className="flex flex-col gap-4 border-t border-line p-4">
          <OutletModeEditor
            session={session}
            outlet={outlet}
            row={row}
            companyMode={companyMode}
            companyGateway={companyGateway}
            isIdr={isIdr}
          />
        </div>
      ) : null}
    </div>
  )
}

function OutletModeEditor({
  session,
  outlet,
  row,
  companyMode,
  companyGateway,
  isIdr,
}: {
  session: CompanySession
  outlet: OutletSummary
  row: PaymentSettingsRow | null
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
}) {
  const { t } = useTranslation()
  const upsertOverride = useUpsertOutletOverride(session)
  const deleteOverride = useDeleteOutletOverride(session)
  const [pendingInherit, setPendingInherit] = useState(false)

  const hasOverride = row != null
  const effectiveMode: QrisMode = row?.mode ?? companyMode
  const hasImage = row?.hasStaticImage ?? false
  const displayValue: QrisMode | 'INHERIT' = pendingInherit ? 'INHERIT' : hasOverride ? row.mode : 'INHERIT'

  const options: Choice<QrisMode | 'INHERIT'>[] = [
    {
      value: 'INHERIT',
      title: t('settings.payments.outlet.inheritOption'),
      subtitle: t('settings.payments.outlet.inheritDesc', { mode: modeLabel(t, companyMode) }),
    },
    {
      value: 'MANUAL',
      title: t('settings.payments.mode.manual'),
      subtitle: t('settings.payments.mode.manualDesc'),
    },
    {
      value: 'STATIC',
      title: t('settings.payments.mode.static'),
      subtitle: t('settings.payments.mode.staticDesc'),
    },
    {
      value: 'GATEWAY',
      title: t('settings.payments.mode.gateway'),
      subtitle: isIdr ? t('settings.payments.mode.gatewayDesc') : t('settings.payments.mode.gatewayIdrOnly'),
      disabled: !isIdr,
    },
  ]

  function onChange(next: QrisMode | 'INHERIT') {
    if (next === 'INHERIT') {
      // The backend deletes the WHOLE override row on revert — including any outlet-specific
      // image — so guard with an inline confirm only when there is something to lose.
      if (hasImage) {
        setPendingInherit(true)
        return
      }
      deleteOverride.mutate(outlet.id)
      return
    }
    setPendingInherit(false)
    upsertOverride.mutate({ outletId: outlet.id, mode: next })
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h4 className="mb-2 text-sm font-bold text-ink">
          {t('settings.payments.outlet.modeHeading', { outlet: outlet.name })}
        </h4>
        <ChoiceCards
          name={`qris-mode-${outlet.id}`}
          options={options}
          value={displayValue}
          columns={2}
          onChange={onChange}
        />
        {upsertOverride.isError ? (
          <p className="mt-2 text-sm text-loss">{t('settings.payments.mode.saveError')}</p>
        ) : null}
      </div>

      {pendingInherit ? (
        <div className="flex flex-col gap-3 rounded-xl border border-amber/30 bg-amber-tint p-3.5 text-xs leading-relaxed text-amber">
          <p>{t('settings.payments.outlet.inheritConfirmMessage')}</p>
          <div className="flex gap-2">
            <Button
              type="button"
              className="bg-loss text-white hover:opacity-90"
              size="sm"
              disabled={deleteOverride.isPending}
              onClick={() =>
                deleteOverride.mutate(outlet.id, { onSuccess: () => setPendingInherit(false) })
              }
            >
              {deleteOverride.isPending ? <Spinner /> : t('settings.payments.outlet.inheritConfirmYes')}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setPendingInherit(false)}
            >
              {t('common.cancel')}
            </Button>
          </div>
        </div>
      ) : null}

      {effectiveMode === 'GATEWAY' ? (
        <div className="flex flex-wrap items-center gap-2 rounded-xl bg-paper px-3.5 py-2.5 text-xs text-ink-3">
          <span>{t('settings.payments.outlet.gatewayHint')}</span>
          <Badge tone={companyGateway?.connected ? 'profit' : 'neutral'}>
            {companyGateway?.connected
              ? t('settings.payments.gateway.connected')
              : t('settings.payments.gateway.notConnected')}
          </Badge>
        </div>
      ) : null}

      <div className="flex flex-col gap-2 border-t border-line pt-4">
        <h4 className="text-sm font-bold text-ink">{t('settings.payments.outlet.imageHeading')}</h4>
        <StaticImageEditor session={session} outletId={outlet.id} hasImage={hasImage} />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Company default — the fallback
// ---------------------------------------------------------------------------

function ModePickerCard({
  session,
  currentMode,
  isIdr,
}: {
  session: CompanySession
  currentMode: QrisMode
  isIdr: boolean
}) {
  const { t } = useTranslation()
  const upsert = useUpsertPaymentSettings(session)

  const options: Choice<QrisMode>[] = [
    {
      value: 'MANUAL',
      title: t('settings.payments.mode.manual'),
      subtitle: t('settings.payments.mode.manualDesc'),
    },
    {
      value: 'STATIC',
      title: t('settings.payments.mode.static'),
      subtitle: t('settings.payments.mode.staticDesc'),
    },
    {
      value: 'GATEWAY',
      title: t('settings.payments.mode.gateway'),
      subtitle: isIdr ? t('settings.payments.mode.gatewayDesc') : t('settings.payments.mode.gatewayIdrOnly'),
      disabled: !isIdr,
    },
  ]

  return (
    <Card className="flex flex-col gap-4 p-6">
      <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.mode.heading')}</h2>
      <ChoiceCards
        name="qris-mode"
        options={options}
        value={currentMode}
        columns={1}
        onChange={(mode) => upsert.mutate({ mode })}
      />
      {upsert.isError ? <p className="text-sm text-loss">{t('settings.payments.mode.saveError')}</p> : null}
    </Card>
  )
}

/** The company-level static-QR card — the fallback image every outlet without its own uses. */
function CompanyStaticImageCard({
  session,
  companyDefault,
}: {
  session: CompanySession
  companyDefault: PaymentSettingsRow | null
}) {
  const { t } = useTranslation()

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.static.heading')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('settings.payments.static.companyLabel')}</p>
      </div>

      <StaticImageEditor
        session={session}
        outletId={null}
        hasImage={companyDefault?.hasStaticImage ?? false}
      />
    </Card>
  )
}

/** The static-QR preview + upload/replace/remove controls — shared by the company card and every
 *  outlet's editor (`outletId` null = company; set = that outlet's own image). */
function StaticImageEditor({
  session,
  outletId,
  hasImage,
}: {
  session: CompanySession
  outletId: string | null
  hasImage: boolean
}) {
  const { t } = useTranslation()
  const [version, setVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const upload = useUploadStaticQr(session)
  const remove = useDeleteStaticQr(session)
  const preview = useStaticQrImageUrl(session, outletId, true, version)

  function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (!file) return
    if (file.size > STATIC_MAX_BYTES) {
      setError(t('settings.payments.static.tooLarge'))
      return
    }
    setError(null)
    upload.mutate(
      { file, outletId: outletId ?? undefined },
      {
        onSuccess: () => setVersion((v) => v + 1),
        onError: () => setError(t('settings.payments.static.uploadError')),
      },
    )
  }

  function onRemove() {
    setError(null)
    remove.mutate(outletId ?? undefined, { onSuccess: () => setVersion((v) => v + 1) })
  }

  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="grid size-24 shrink-0 place-items-center overflow-hidden rounded-xl border border-line bg-paper">
        {preview.status === 'ready' && preview.url ? (
          <img src={preview.url} alt={t('settings.payments.static.previewAlt')} className="size-full object-contain" />
        ) : preview.status === 'loading' ? (
          <Spinner className="text-brand-500" />
        ) : (
          <QrCode className="size-8 text-ink-3" strokeWidth={1.5} aria-hidden />
        )}
      </div>

      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            onChange={onPick}
            className="hidden"
            id={outletId ? `static-qr-input-${outletId}` : 'static-qr-input-company'}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={upload.isPending}
            onClick={() => fileInputRef.current?.click()}
          >
            <UploadCloud className="size-4" />
            {hasImage ? t('settings.payments.static.replace') : t('settings.payments.static.upload')}
          </Button>
          {hasImage ? (
            <Button type="button" variant="ghost" size="sm" disabled={remove.isPending} onClick={onRemove}>
              <Trash2 className="size-4" />
              {t('settings.payments.static.remove')}
            </Button>
          ) : null}
        </div>
        {error ? <p className="text-xs text-loss">{error}</p> : null}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Gateway (Midtrans)
// ---------------------------------------------------------------------------

function GatewayCard({
  session,
  currentMode,
  gateway,
}: {
  session: CompanySession
  currentMode: QrisMode
  gateway: PaymentSettingsRow['gateway']
}) {
  const { t } = useTranslation()
  const upsert = useUpsertPaymentSettings(session)
  // The owner settings query is already loaded by the time this card mounts (PaymentSettingsContent
  // gates on it) — these initializers read the CURRENT stored value exactly once; a later refetch
  // (after Save) intentionally does not clobber the fields again, since serverKey/clientKey are
  // never echoed back and are cleared explicitly in the Save handler below.
  const [environment, setEnvironment] = useState<GatewayEnvironment>(gateway?.environment ?? 'SANDBOX')
  const [serverKey, setServerKey] = useState('')
  const [clientKey, setClientKey] = useState('')

  const connected = gateway?.connected ?? false
  const serverKeyPlaceholder = connected && gateway?.serverKeyLast4 ? `•••• ${gateway.serverKeyLast4}` : undefined

  function onSave() {
    const body: UpsertCompanySettingsBody = {
      mode: currentMode,
      provider: 'MIDTRANS',
      environment,
    }
    if (serverKey.trim()) body.serverKey = serverKey.trim()
    if (clientKey.trim()) body.clientKey = clientKey.trim()
    upsert.mutate(body, {
      onSuccess: () => {
        setServerKey('')
        setClientKey('')
      },
    })
  }

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.gateway.heading')}</h2>
        <Badge tone={connected ? 'profit' : 'neutral'}>
          {connected ? t('settings.payments.gateway.connected') : t('settings.payments.gateway.notConnected')}
        </Badge>
      </div>

      <div>
        <span className="mb-1.5 block text-sm font-medium text-ink">
          {t('settings.payments.gateway.environment')}
        </span>
        <Segmented
          ariaLabel={t('settings.payments.gateway.environment')}
          value={environment}
          onChange={setEnvironment}
          options={[
            { value: 'SANDBOX', label: t('settings.payments.gateway.sandbox') },
            { value: 'PRODUCTION', label: t('settings.payments.gateway.production') },
          ]}
        />
      </div>

      <Field
        label={t('settings.payments.gateway.serverKey')}
        htmlFor="gateway-server-key"
        hint={connected ? t('settings.payments.gateway.serverKeySavedHint') : undefined}
      >
        <TextInput
          id="gateway-server-key"
          type="password"
          autoComplete="off"
          placeholder={serverKeyPlaceholder}
          value={serverKey}
          onChange={(e) => setServerKey(e.target.value)}
        />
      </Field>

      <Field label={t('settings.payments.gateway.clientKey')} htmlFor="gateway-client-key">
        <TextInput
          id="gateway-client-key"
          type="text"
          autoComplete="off"
          value={clientKey}
          onChange={(e) => setClientKey(e.target.value)}
        />
      </Field>

      {upsert.isError ? <p className="text-sm text-loss">{t('settings.payments.gateway.saveError')}</p> : null}

      <div>
        <Button type="button" disabled={upsert.isPending} onClick={onSave}>
          {upsert.isPending ? <Spinner /> : t('settings.payments.gateway.save')}
        </Button>
      </div>
    </Card>
  )
}
