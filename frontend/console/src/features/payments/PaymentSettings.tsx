/**
 * PaymentSettings — `/settings/payments`, OWNER-ONLY (ADR 0045; division layer added by the ADR
 * 0045 amendment). The QRIS payment-mode console.
 *
 * A company has ONE QRIS mode (MANUAL/STATIC/GATEWAY). Any org UNIT — a division (business unit)
 * or an outlet — may carry its own MODE override (mode only — gateway credentials are
 * company-level) and its own STATIC image override, resolved **outlet → division → company**: an
 * outlet without its own setting falls back to its division's, and a division without its own
 * falls back to the company's.
 *
 * DIVISION-FIRST layout (owner request, ADR 0045 amendment): the primary list is now grouped BY
 * DIVISION. Each division gets its own accordion editor (mode incl. an explicit "inherit company
 * default" choice, its own static image) with its outlets nested underneath — each outlet's own
 * editor is unchanged in shape, but its "inherit" choice and effective-state badge now reflect the
 * REAL three-level chain: falls back to the division's setting when the division has one, else the
 * company's. Divisions + outlet parentage come from the org tree (`useOrgUnits`, the same read the
 * org pages use — `features/org/OrgTree.tsx`), joined client-side against the owner settings rows
 * by `unitId`. The company mode/image/gateway cards stay at the bottom, framed as the fallback
 * every division (and, through it, every outlet) without its own setting ultimately uses.
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
import {
  Building2,
  ChevronDown,
  ChevronRight,
  LogOut,
  QrCode,
  Trash2,
  TriangleAlert,
  UploadCloud,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { FormSkeleton, ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { ChoiceCards, type Choice } from '@/components/ui/ChoiceCards'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { useSession, type CompanySession } from '@/lib/session'
import { useOrgUnits, type OrgUnit } from '@/features/org/api'
import {
  useDeleteStaticQr,
  useDeleteUnitOverride,
  useOwnerPaymentSettings,
  useStaticQrImageUrl,
  useUploadStaticQr,
  useUpsertUnitOverride,
  useUpsertPaymentSettings,
  useVerifyGateway,
  type GatewayEnvCredential,
  type GatewayEnvironment,
  type GatewayVerifyResult,
  type OwnerPaymentSettingsResponse,
  type PaymentSettingsRow,
  type UpsertCompanySettingsBody,
} from './api'
import { canActivateEnvironment, gatewayActiveConnected } from './gatewayActivation'
import type { QrisMode } from './effectiveMode'

/** Client-side pre-flight only (the server is the authority: 413 over-size, 422 bad magic bytes)
 *  — mirrors the 2 MiB cap the backend documents for this endpoint. */
const STATIC_MAX_BYTES = 2 * 1024 * 1024

/** The human label for a QRIS mode ("Manual" / "Your own QRIS code" / "Payment gateway"), shared
 *  by the company mode picker and every division/outlet editor/badge. */
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
      <>
        <ListSkeleton rows={3} className="rounded-2xl" />
        <FormSkeleton fields={2} />
        <Skeleton className="h-32 rounded-card" />
        <FormSkeleton fields={3} />
      </>
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
      {/* PRIMARY: every division, its effective QRIS state, and its nested outlets. */}
      <DivisionsSection
        session={session}
        companyMode={currentMode}
        companyGateway={gateway}
        isIdr={isIdr}
        unitOverrides={data.outletOverrides}
      />

      {/* FALLBACK: the company default every division (and, through it, every outlet) without its
          own setting uses. */}
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
// Divisions — the primary grouping (ADR 0045 amendment)
// ---------------------------------------------------------------------------

function DivisionsSection({
  session,
  companyMode,
  companyGateway,
  isIdr,
  unitOverrides,
}: {
  session: CompanySession
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
  unitOverrides: PaymentSettingsRow[]
}) {
  const { t } = useTranslation()
  const orgUnitsQuery = useOrgUnits({ companyId: session.companyId, actor: session.actor, enabled: true })
  const rowByUnitId = new Map(unitOverrides.map((row) => [row.unitId, row]))

  const allUnits = orgUnitsQuery.data ?? []
  const divisions = allUnits
    .filter((u) => u.type === 'BUSINESS_UNIT' && u.active)
    .sort((a, b) => a.name.localeCompare(b.name))
  const outletsByDivision = new Map<string, OrgUnit[]>()
  for (const u of allUnits) {
    if (u.type === 'OUTLET' && u.active && u.parentId) {
      const list = outletsByDivision.get(u.parentId) ?? []
      list.push(u)
      outletsByDivision.set(u.parentId, list)
    }
  }
  for (const list of outletsByDivision.values()) list.sort((a, b) => a.name.localeCompare(b.name))

  const [expandedUnitId, setExpandedUnitId] = useState<string | null>(null)
  const toggle = (unitId: string) => setExpandedUnitId((current) => (current === unitId ? null : unitId))

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.divisions.heading')}</h2>
        <p className="mt-1 text-sm text-ink-3">{t('settings.payments.divisions.hint')}</p>
      </div>

      {divisions.length > 0 ? (
        <div className="flex flex-col gap-3">
          {divisions.map((division) => (
            <DivisionBlock
              key={division.id}
              session={session}
              division={division}
              divisionRow={rowByUnitId.get(division.id) ?? null}
              outlets={outletsByDivision.get(division.id) ?? []}
              rowByUnitId={rowByUnitId}
              companyMode={companyMode}
              companyGateway={companyGateway}
              isIdr={isIdr}
              expandedUnitId={expandedUnitId}
              onToggle={toggle}
            />
          ))}
        </div>
      ) : orgUnitsQuery.isLoading ? (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-16 rounded-2xl" />
          <Skeleton className="h-16 rounded-2xl" />
        </div>
      ) : (
        <p className="text-sm text-ink-3">{t('settings.payments.divisions.empty')}</p>
      )}
    </Card>
  )
}

function DivisionBlock({
  session,
  division,
  divisionRow,
  outlets,
  rowByUnitId,
  companyMode,
  companyGateway,
  isIdr,
  expandedUnitId,
  onToggle,
}: {
  session: CompanySession
  division: OrgUnit
  divisionRow: PaymentSettingsRow | null
  outlets: OrgUnit[]
  rowByUnitId: Map<string | null, PaymentSettingsRow>
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
  expandedUnitId: string | null
  onToggle: (unitId: string) => void
}) {
  const { t } = useTranslation()
  const hasOverride = divisionRow != null
  const effectiveMode: QrisMode = divisionRow?.mode ?? companyMode
  const hasImage = divisionRow?.hasStaticImage ?? false
  const expanded = expandedUnitId === division.id
  const panelId = `division-qris-panel-${division.id}`

  return (
    <div className="rounded-2xl border border-line-strong bg-paper/40">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={() => onToggle(division.id)}
        className="flex w-full flex-wrap items-center justify-between gap-3 rounded-2xl p-4 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
      >
        <span className="flex min-w-0 items-center gap-2.5">
          {expanded ? (
            <ChevronDown className="size-4 shrink-0 text-ink-3" aria-hidden />
          ) : (
            <ChevronRight className="size-4 shrink-0 text-ink-3" aria-hidden />
          )}
          <Building2 className="size-4 shrink-0 text-ink-3" aria-hidden />
          <span className="truncate font-semibold text-ink">{division.name}</span>
          <Badge tone="info">{t('org.type.BUSINESS_UNIT')}</Badge>
        </span>
        <span className="flex flex-wrap items-center gap-2">
          <Badge tone={hasOverride ? 'emerald' : 'neutral'}>
            {hasOverride
              ? t('settings.payments.outlet.effectiveOwn', { mode: modeLabel(t, effectiveMode) })
              : t('settings.payments.outlet.effectiveInherits', { mode: modeLabel(t, companyMode) })}
          </Badge>
          <Badge tone={hasImage ? 'emerald' : 'neutral'}>
            {hasImage
              ? t('settings.payments.division.imageOwnSet')
              : t('settings.payments.static.usingCompany')}
          </Badge>
        </span>
      </button>

      {expanded ? (
        <div id={panelId} className="flex flex-col gap-4 border-t border-line p-4">
          <DivisionModeEditor
            session={session}
            division={division}
            row={divisionRow}
            companyMode={companyMode}
            companyGateway={companyGateway}
            isIdr={isIdr}
          />
        </div>
      ) : null}

      {/* Outlets nested under this division — always listed (independent of the division's own
          accordion state) so the owner can still browse/edit an outlet without first opening the
          division's own editor. */}
      <div className="flex flex-col gap-3 border-t border-line p-3 pl-4 sm:pl-7">
        {outlets.length > 0 ? (
          outlets.map((outlet) => (
            <OutletRow
              key={outlet.id}
              session={session}
              outlet={outlet}
              row={rowByUnitId.get(outlet.id) ?? null}
              divisionId={division.id}
              divisionRow={divisionRow}
              companyMode={companyMode}
              companyGateway={companyGateway}
              isIdr={isIdr}
              expanded={expandedUnitId === outlet.id}
              onToggle={() => onToggle(outlet.id)}
            />
          ))
        ) : (
          <p className="text-sm text-ink-3">{t('settings.payments.outlets.empty')}</p>
        )}
      </div>
    </div>
  )
}

/** The division's own mode/image editor — a single-level "inherit company default" (a division has
 *  no parent of its own to fall further back to). Structurally the same shape as `OutletModeEditor`
 *  below, minus the extra division-inherit branch. */
function DivisionModeEditor({
  session,
  division,
  row,
  companyMode,
  companyGateway,
  isIdr,
}: {
  session: CompanySession
  division: OrgUnit
  row: PaymentSettingsRow | null
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
}) {
  const { t } = useTranslation()
  const upsertOverride = useUpsertUnitOverride(session)
  const deleteOverride = useDeleteUnitOverride(session)
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
      // The backend deletes the WHOLE override row on revert — including any division-specific
      // image — so guard with an inline confirm only when there is something to lose.
      if (hasImage) {
        setPendingInherit(true)
        return
      }
      deleteOverride.mutate(division.id)
      return
    }
    setPendingInherit(false)
    upsertOverride.mutate({ unitId: division.id, mode: next })
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h4 className="mb-2 text-sm font-bold text-ink">
          {t('settings.payments.division.modeHeading', { division: division.name })}
        </h4>
        <ChoiceCards
          name={`qris-mode-${division.id}`}
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
          <p>{t('settings.payments.division.inheritConfirmMessage')}</p>
          <div className="flex gap-2">
            <Button
              type="button"
              className="bg-loss text-white hover:opacity-90"
              size="sm"
              disabled={deleteOverride.isPending}
              onClick={() =>
                deleteOverride.mutate(division.id, { onSuccess: () => setPendingInherit(false) })
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
          <span>{t('settings.payments.division.gatewayHint')}</span>
          <Badge tone={gatewayActiveConnected(companyGateway) ? 'profit' : 'neutral'}>
            {gatewayActiveConnected(companyGateway)
              ? t('settings.payments.gateway.connected')
              : t('settings.payments.gateway.notConnected')}
          </Badge>
        </div>
      ) : null}

      <div className="flex flex-col gap-2 border-t border-line pt-4">
        <h4 className="text-sm font-bold text-ink">{t('settings.payments.outlet.imageHeading')}</h4>
        <StaticImageEditor session={session} unitId={division.id} previewDivisionId={division.id} hasImage={hasImage} />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Outlets — nested under their division
// ---------------------------------------------------------------------------

function OutletRow({
  session,
  outlet,
  row,
  divisionId,
  divisionRow,
  companyMode,
  companyGateway,
  isIdr,
  expanded,
  onToggle,
}: {
  session: CompanySession
  outlet: OrgUnit
  row: PaymentSettingsRow | null
  /** This outlet's parent division id — always known here (outlets are only ever rendered nested
   *  under their division block). */
  divisionId: string
  /** The division's OWN override row, or null when the division has none — decides whether this
   *  outlet's "inherit" falls back to the division's setting or straight to the company's. */
  divisionRow: PaymentSettingsRow | null
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
  expanded: boolean
  onToggle: () => void
}) {
  const { t } = useTranslation()
  const hasOverride = row != null
  const effectiveMode: QrisMode = row?.mode ?? divisionRow?.mode ?? companyMode
  const hasImage = row?.hasStaticImage ?? false
  const divisionHasImage = divisionRow?.hasStaticImage ?? false
  const panelId = `outlet-qris-panel-${outlet.id}`

  return (
    <div className="rounded-2xl border border-line bg-surface">
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
              : divisionRow
                ? t('settings.payments.outlet.effectiveInheritsDivision', { mode: modeLabel(t, divisionRow.mode) })
                : t('settings.payments.outlet.effectiveInherits', { mode: modeLabel(t, companyMode) })}
          </Badge>
          <Badge tone={hasImage ? 'emerald' : 'neutral'}>
            {hasImage
              ? t('settings.payments.static.overrideSet')
              : divisionHasImage
                ? t('settings.payments.outlet.imageInheritsDivision')
                : t('settings.payments.static.usingCompany')}
          </Badge>
        </span>
      </button>

      {expanded ? (
        <div id={panelId} className="flex flex-col gap-4 border-t border-line p-4">
          <OutletModeEditor
            session={session}
            outlet={outlet}
            row={row}
            divisionId={divisionId}
            divisionRow={divisionRow}
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
  divisionId,
  divisionRow,
  companyMode,
  companyGateway,
  isIdr,
}: {
  session: CompanySession
  outlet: OrgUnit
  row: PaymentSettingsRow | null
  divisionId: string
  divisionRow: PaymentSettingsRow | null
  companyMode: QrisMode
  companyGateway: PaymentSettingsRow['gateway']
  isIdr: boolean
}) {
  const { t } = useTranslation()
  const upsertOverride = useUpsertUnitOverride(session)
  const deleteOverride = useDeleteUnitOverride(session)
  const [pendingInherit, setPendingInherit] = useState(false)

  const hasOverride = row != null
  const fallbackMode: QrisMode = divisionRow?.mode ?? companyMode
  const effectiveMode: QrisMode = row?.mode ?? fallbackMode
  const hasImage = row?.hasStaticImage ?? false
  const displayValue: QrisMode | 'INHERIT' = pendingInherit ? 'INHERIT' : hasOverride ? row.mode : 'INHERIT'

  const options: Choice<QrisMode | 'INHERIT'>[] = [
    {
      value: 'INHERIT',
      // ADR 0045 amendment: the "inherit" choice now reflects the REAL chain — the division's
      // own default when it has one, else the company's.
      title: divisionRow
        ? t('settings.payments.outlet.inheritOptionDivision')
        : t('settings.payments.outlet.inheritOption'),
      subtitle: divisionRow
        ? t('settings.payments.outlet.inheritDescDivision', { mode: modeLabel(t, divisionRow.mode) })
        : t('settings.payments.outlet.inheritDesc', { mode: modeLabel(t, companyMode) }),
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
    upsertOverride.mutate({ unitId: outlet.id, mode: next })
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
          <Badge tone={gatewayActiveConnected(companyGateway) ? 'profit' : 'neutral'}>
            {gatewayActiveConnected(companyGateway)
              ? t('settings.payments.gateway.connected')
              : t('settings.payments.gateway.notConnected')}
          </Badge>
        </div>
      ) : null}

      <div className="flex flex-col gap-2 border-t border-line pt-4">
        <h4 className="text-sm font-bold text-ink">{t('settings.payments.outlet.imageHeading')}</h4>
        <StaticImageEditor
          session={session}
          unitId={outlet.id}
          previewBusinessId={outlet.id}
          previewDivisionId={divisionId}
          hasImage={hasImage}
        />
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

/** The company-level static-QR card — the fallback image every division/outlet without its own
 *  uses. */
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
        unitId={null}
        hasImage={companyDefault?.hasStaticImage ?? false}
      />
    </Card>
  )
}

/**
 * The static-QR preview + upload/replace/remove controls — shared by the company card and every
 * division/outlet editor.
 *
 * `unitId` is the PUT/DELETE target for upload/remove: null for the company, else the division or
 * outlet id being edited. `previewBusinessId`/`previewDivisionId` drive the GET preview's own
 * outlet → division → company resolution (ADR 0045 amendment) — for the company card both are
 * omitted (company-scoped); for a division editor only `previewDivisionId` (=`unitId`) is set; for
 * an outlet editor both are set (`previewBusinessId` = the outlet id, `previewDivisionId` = its
 * parent division id) so the preview shows exactly what the till would resolve, not just this
 * unit's own uploaded bytes.
 */
function StaticImageEditor({
  session,
  unitId,
  previewBusinessId,
  previewDivisionId,
  hasImage,
}: {
  session: CompanySession
  unitId: string | null
  previewBusinessId?: string
  previewDivisionId?: string | null
  hasImage: boolean
}) {
  const { t } = useTranslation()
  const [version, setVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const upload = useUploadStaticQr(session)
  const remove = useDeleteStaticQr(session)
  const preview = useStaticQrImageUrl(session, previewBusinessId, true, version, previewDivisionId)

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
      { file, unitId: unitId ?? undefined },
      {
        onSuccess: () => setVersion((v) => v + 1),
        onError: () => setError(t('settings.payments.static.uploadError')),
      },
    )
  }

  function onRemove() {
    setError(null)
    remove.mutate(unitId ?? undefined, { onSuccess: () => setVersion((v) => v + 1) })
  }

  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="grid size-24 shrink-0 place-items-center overflow-hidden rounded-xl border border-line bg-paper">
        {preview.status === 'ready' && preview.url ? (
          <img src={preview.url} alt={t('settings.payments.static.previewAlt')} className="size-full object-contain" />
        ) : preview.status === 'loading' ? (
          <Skeleton className="size-full rounded-none" />
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
            id={unitId ? `static-qr-input-${unitId}` : 'static-qr-input-company'}
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
  // (after Save) intentionally does not clobber the fields again, since the keys are never echoed
  // back and are cleared explicitly in the Save handler below. Per-environment (V6): each
  // environment's key lives in its own slot, so switching the active environment never re-types a
  // key and can never mismatch an environment with the wrong one.
  const [activeEnvironment, setActiveEnvironment] = useState<GatewayEnvironment>(
    gateway?.activeEnvironment ?? 'SANDBOX',
  )
  const [sandboxServerKey, setSandboxServerKey] = useState('')
  const [sandboxClientKey, setSandboxClientKey] = useState('')
  const [productionServerKey, setProductionServerKey] = useState('')
  const [productionClientKey, setProductionClientKey] = useState('')

  // The client mirror of the server's structural guard: you cannot activate an environment whose
  // slot has neither a stored nor a just-typed key.
  const activationOk = canActivateEnvironment(activeEnvironment, gateway, {
    sandboxServerKey,
    productionServerKey,
  })

  function onSave() {
    const body: UpsertCompanySettingsBody = {
      mode: currentMode,
      provider: 'MIDTRANS',
      activeEnvironment,
    }
    if (sandboxServerKey.trim()) body.sandboxServerKey = sandboxServerKey.trim()
    if (sandboxClientKey.trim()) body.sandboxClientKey = sandboxClientKey.trim()
    if (productionServerKey.trim()) body.productionServerKey = productionServerKey.trim()
    if (productionClientKey.trim()) body.productionClientKey = productionClientKey.trim()
    upsert.mutate(body, {
      onSuccess: () => {
        setSandboxServerKey('')
        setSandboxClientKey('')
        setProductionServerKey('')
        setProductionClientKey('')
      },
    })
  }

  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-lg font-bold text-ink">{t('settings.payments.gateway.heading')}</h2>
      </div>

      <div>
        <span className="mb-1.5 block text-sm font-medium text-ink">
          {t('settings.payments.gateway.activeEnvironment')}
        </span>
        <Segmented
          ariaLabel={t('settings.payments.gateway.activeEnvironment')}
          value={activeEnvironment}
          onChange={setActiveEnvironment}
          options={[
            { value: 'SANDBOX', label: t('settings.payments.gateway.sandbox') },
            { value: 'PRODUCTION', label: t('settings.payments.gateway.production') },
          ]}
        />
        {!activationOk ? (
          <p className="mt-2 text-xs text-loss">{t('settings.payments.gateway.activateNeedsKey')}</p>
        ) : activeEnvironment === 'PRODUCTION' ? (
          <p className="mt-2 text-xs text-amber-2">{t('settings.payments.gateway.productionLiveWarning')}</p>
        ) : null}
      </div>

      <GatewayEnvSection
        session={session}
        env="SANDBOX"
        credential={gateway?.sandbox ?? null}
        serverKey={sandboxServerKey}
        onServerKey={setSandboxServerKey}
        clientKey={sandboxClientKey}
        onClientKey={setSandboxClientKey}
      />

      <GatewayEnvSection
        session={session}
        env="PRODUCTION"
        credential={gateway?.production ?? null}
        serverKey={productionServerKey}
        onServerKey={setProductionServerKey}
        clientKey={productionClientKey}
        onClientKey={setProductionClientKey}
      />

      {upsert.isError ? <p className="text-sm text-loss">{t('settings.payments.gateway.saveError')}</p> : null}

      <div>
        <Button type="button" disabled={upsert.isPending || !activationOk} onClick={onSave}>
          {upsert.isPending ? <Spinner /> : t('settings.payments.gateway.save')}
        </Button>
      </div>
    </Card>
  )
}

/** One environment's credential slot (Sandbox / Production): keys + Connected badge + Test koneksi. */
function GatewayEnvSection({
  session,
  env,
  credential,
  serverKey,
  onServerKey,
  clientKey,
  onClientKey,
}: {
  session: CompanySession
  env: GatewayEnvironment
  credential: GatewayEnvCredential | null
  serverKey: string
  onServerKey: (value: string) => void
  clientKey: string
  onClientKey: (value: string) => void
}) {
  const { t } = useTranslation()
  const verify = useVerifyGateway(session)
  const connected = credential?.connected ?? false
  const last4 = credential?.serverKeyLast4 ?? null
  const serverKeyPlaceholder = connected && last4 ? `•••• ${last4}` : undefined
  const envLabel = t(
    env === 'SANDBOX' ? 'settings.payments.gateway.sandbox' : 'settings.payments.gateway.production',
  )
  const canVerify = connected || serverKey.trim().length > 0

  function onVerify() {
    verify.mutate({ environment: env, serverKey: serverKey.trim() || undefined })
  }

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-line p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink">{envLabel}</h3>
        <Badge tone={connected ? 'profit' : 'neutral'}>
          {connected
            ? t('settings.payments.gateway.connectedWithLast4', { last4 })
            : t('settings.payments.gateway.notConnected')}
        </Badge>
      </div>

      <Field
        label={t('settings.payments.gateway.serverKey')}
        htmlFor={`gateway-server-key-${env}`}
        hint={connected ? t('settings.payments.gateway.serverKeySavedHint') : undefined}
      >
        <TextInput
          id={`gateway-server-key-${env}`}
          type="password"
          autoComplete="off"
          placeholder={serverKeyPlaceholder}
          value={serverKey}
          onChange={(e) => onServerKey(e.target.value)}
        />
      </Field>

      <Field label={t('settings.payments.gateway.clientKey')} htmlFor={`gateway-client-key-${env}`}>
        <TextInput
          id={`gateway-client-key-${env}`}
          type="text"
          autoComplete="off"
          value={clientKey}
          onChange={(e) => onClientKey(e.target.value)}
        />
      </Field>

      <div className="flex items-center gap-3">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!canVerify || verify.isPending}
          onClick={onVerify}
        >
          {verify.isPending ? <Spinner /> : t('settings.payments.gateway.testConnection')}
        </Button>
        {verify.isError ? (
          <span className="text-xs text-loss">{verifyMessage(t, 'UNREACHABLE')}</span>
        ) : verify.data ? (
          <span className={`text-xs ${verifyTone(verify.data.result)}`}>
            {verifyMessage(t, verify.data.result)}
          </span>
        ) : null}
      </div>
    </div>
  )
}

function verifyTone(result: GatewayVerifyResult): string {
  if (result === 'VALID') return 'text-profit'
  if (result === 'INVALID') return 'text-loss'
  return 'text-amber-2'
}

function verifyMessage(t: TFunction, result: GatewayVerifyResult): string {
  switch (result) {
    case 'VALID':
      return t('settings.payments.gateway.verify.valid')
    case 'INVALID':
      return t('settings.payments.gateway.verify.invalid')
    case 'UNREACHABLE':
      return t('settings.payments.gateway.verify.unreachable')
  }
}
