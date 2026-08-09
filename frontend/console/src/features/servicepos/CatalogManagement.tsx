/**
 * CatalogManagement — owner/manager back-office page for a service vertical's catalog: primary
 * items (packages for carwash, services for barbershop), add-ons, and staff profiles (washers for
 * carwash, barbers for barbershop). Standalone full-screen page at /catalog, outside the Shell,
 * mirroring features/menu/MenuManagement.tsx's page skeleton (its restaurant counterpart).
 *
 * Parameterized by a VerticalPosConfig prop (PosSwitch's CatalogSwitch decides which one, and
 * whether a company's outlet resolves here at all) — NOT hardcoded to one vertical. Structural
 * field labels (name/description/price/active/etc.) stay shared serviceCatalog.* copy; only the
 * nouns that must differ (the primary item's name, the staff role's name) are config-driven, via
 * VerticalPosConfig.primaryItemLabels / .staffTitleKey / .i18nNs.
 *
 * Rules enforced:
 *   - No hardcoded user-facing strings (rule 9): every label is a react-i18next key.
 *   - Money is integer minor units on the wire (rule 8): formatMoney + isoMinorExponent used.
 *   - TanStack Query for all server state; mutations invalidate their list so the POS stays in sync.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowLeft, Pencil, Plus, Wrench } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { useSession, type CompanySession } from '@/lib/session'
import { effectiveRoles, hasAnyRole, useAuth } from '@/lib/authContext'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { OutletPicker } from '@/components/OutletPicker'
import { OutletGate } from '@/components/OutletGate'
import { useEmployees } from '@/features/hr/api'
import type { VerticalPosConfig } from './config'
import {
  useCatalogAddons,
  useCatalogPackages,
  useCreateAddon,
  useCreatePackage,
  useCreateStaffProfile,
  useStaffProfiles,
  useUpdateAddon,
  useUpdatePackage,
  useUpdateStaffProfile,
  type CatalogItemResponse,
  type StaffProfileResponse,
} from './api'

// ---------------------------------------------------------------------------
// Entry guard
// ---------------------------------------------------------------------------

export function CatalogManagement({ config }: { config: VerticalPosConfig }) {
  const { company } = useSession()
  if (!company) return <NoCompany />
  return (
    <OutletGate company={company} requiredVertical={config.vertical}>
      {(session) => <CatalogManagementInner key={session.businessId} config={config} session={session} />}
    </OutletGate>
  )
}

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <Card className="w-full max-w-md p-10 text-center">
        <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('serviceCatalog.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-5 inline-block rounded-xl bg-emerald px-4 py-2.5 text-sm font-bold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inner
// ---------------------------------------------------------------------------

function CatalogManagementInner({
  config,
  session,
}: {
  config: VerticalPosConfig
  session: CompanySession
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const auth = useAuth()
  // ADR 0049 P3b — WasherDialog's employee-link dropdown fires features/hr/api's useEmployees,
  // a DASHBOARD_ROLES-only call (that module always sends the PERSONAL bearer). /catalog itself
  // stays reachable by a bare cashier (canPos, App.tsx `menuAllowed`) — deliberately unchanged, a
  // cashier manages the catalog intentionally — so this call must be gated separately: a bare
  // cashier's personal bearer is null and the call would 401 → spurious global recovery. Mirrors
  // ServicePos.tsx/App.tsx's canDashboard (merged/elevated roles) — a normal `user` login has
  // `elevatedRoles = []`, so this is byte-identical to the base owner/manager check there.
  const canDashboard = hasAnyRole(effectiveRoles(auth.roles, auth.elevatedRoles), 'owner', 'manager')
  // The page subtitle names the primary item and the staff role inline — lowercased so it reads as
  // a natural mid-sentence noun ("packages"/"washers" for carwash, "services"/"barbers" for
  // barbershop) while the sentence itself stays ONE shared serviceCatalog.subtitle key.
  const primaryNoun = t(config.primaryItemLabels.titleKey).toLowerCase()
  const staffNoun = t(config.staffTitleKey).toLowerCase()

  return (
    <div className="min-h-screen bg-paper">
      {/* Phone: the header wraps (auto height) and the picker takes its own full-width row —
          inline at a fixed h-16 it crushed the title (the header-crush class). Desktop keeps
          the original single h-16 row. */}
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line bg-surface px-4 py-2.5 sm:h-16 sm:flex-nowrap sm:px-6 sm:py-0">
        <Link
          to="/pos"
          aria-label={t('a11y.backToDashboard')}
          className="grid size-9 shrink-0 place-items-center rounded-full border border-line text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ArrowLeft className="size-4" />
        </Link>
        <div className="min-w-0 flex-1">
          <h1 className="truncate font-display text-[15px] font-bold text-ink">{t('serviceCatalog.title')}</h1>
        </div>
        <div className="min-w-0 max-sm:order-last max-sm:w-full">
          <OutletPicker />
        </div>
      </div>

      <div className="mx-auto max-w-3xl space-y-6 px-4 py-6 sm:px-6">
        <p className="text-sm text-ink-3">
          {t('serviceCatalog.subtitle', { primary: primaryNoun, staff: staffNoun })}
        </p>
        <PackagesSection session={session} config={config} locale={locale} />
        <AddonsSection session={session} config={config} locale={locale} />
        <WashersSection session={session} config={config} canDashboard={canDashboard} />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Shared dialog chrome (mirrors MenuManagement's DialogOverlay pattern)
// ---------------------------------------------------------------------------

function DialogOverlay({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
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
      <Card className="w-full max-w-md p-6">{children}</Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Packages section
// ---------------------------------------------------------------------------

function PackagesSection({
  session,
  config,
  locale,
}: {
  session: CompanySession
  config: VerticalPosConfig
  locale: string
}) {
  const { t } = useTranslation()
  const query = useCatalogPackages(config, session, { includeInactive: true })
  const updateMutation = useUpdatePackage(config, session)
  const [dialog, setDialog] = useState<'create' | CatalogItemResponse | null>(null)

  const items = query.data ?? []

  return (
    <Card className="p-5">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-display text-base font-semibold text-ink">
          {t(config.primaryItemLabels.titleKey)}
        </h2>
        <Button size="sm" onClick={() => setDialog('create')}>
          <Plus className="size-4" aria-hidden="true" />
          {t(config.primaryItemLabels.addLabelKey)}
        </Button>
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={3} className="rounded-none border-0" />
      ) : items.length === 0 ? (
        <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
          {t(config.primaryItemLabels.emptyKey)}
        </p>
      ) : (
        <ul className="space-y-1.5">
          {items.map((item) => (
            <CatalogItemRow
              key={item.id}
              item={item}
              locale={locale}
              onEdit={() => setDialog(item)}
              onToggleActive={() => updateMutation.mutate({ id: item.id, active: !item.active })}
              togglePending={updateMutation.isPending}
            />
          ))}
        </ul>
      )}

      {dialog === 'create' ? (
        <CreateCatalogItemDialog
          session={session}
          config={config}
          kind="package"
          titleKey={config.primaryItemLabels.addLabelKey}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog && dialog !== 'create' ? (
        <EditCatalogItemDialog
          session={session}
          config={config}
          kind="package"
          item={dialog}
          titleKey={config.primaryItemLabels.editLabelKey}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Add-ons section
// ---------------------------------------------------------------------------

function AddonsSection({
  session,
  config,
  locale,
}: {
  session: CompanySession
  config: VerticalPosConfig
  locale: string
}) {
  const { t } = useTranslation()
  const query = useCatalogAddons(config, session, { includeInactive: true })
  const updateMutation = useUpdateAddon(config, session)
  const [dialog, setDialog] = useState<'create' | CatalogItemResponse | null>(null)

  const items = query.data ?? []

  return (
    <Card className="p-5">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-display text-base font-semibold text-ink">{t('serviceCatalog.addonsTitle')}</h2>
        <Button size="sm" onClick={() => setDialog('create')}>
          <Plus className="size-4" aria-hidden="true" />
          {t('serviceCatalog.addAddon')}
        </Button>
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={3} className="rounded-none border-0" />
      ) : items.length === 0 ? (
        <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
          {t(`${config.i18nNs}.emptyAddons`)}
        </p>
      ) : (
        <ul className="space-y-1.5">
          {items.map((item) => (
            <CatalogItemRow
              key={item.id}
              item={item}
              locale={locale}
              onEdit={() => setDialog(item)}
              onToggleActive={() => updateMutation.mutate({ id: item.id, active: !item.active })}
              togglePending={updateMutation.isPending}
            />
          ))}
        </ul>
      )}

      {dialog === 'create' ? (
        <CreateCatalogItemDialog
          session={session}
          config={config}
          kind="addon"
          titleKey="serviceCatalog.addAddon"
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog && dialog !== 'create' ? (
        <EditCatalogItemDialog
          session={session}
          config={config}
          kind="addon"
          item={dialog}
          titleKey="serviceCatalog.editAddon"
          onClose={() => setDialog(null)}
        />
      ) : null}
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Catalog item row (shared between Packages and Add-ons)
// ---------------------------------------------------------------------------

function CatalogItemRow({
  item,
  locale,
  onEdit,
  onToggleActive,
  togglePending,
}: {
  item: CatalogItemResponse
  locale: string
  onEdit: () => void
  onToggleActive: () => void
  togglePending: boolean
}) {
  const { t } = useTranslation()
  return (
    <li
      className={cn(
        'flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-hover',
        !item.active && 'opacity-55',
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-semibold text-ink">{item.name}</span>
          {!item.active ? <Badge tone="neutral">{t('serviceCatalog.inactive')}</Badge> : null}
        </div>
        {item.description ? <p className="truncate text-xs text-ink-3">{item.description}</p> : null}
      </div>
      <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
      <button
        type="button"
        onClick={onToggleActive}
        disabled={togglePending}
        className="shrink-0 rounded-lg px-2.5 py-1 text-xs font-semibold text-ink-3 transition-colors hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {togglePending ? <Spinner /> : item.active ? t('serviceCatalog.deactivate') : t('serviceCatalog.activate')}
      </button>
      <button
        type="button"
        onClick={onEdit}
        aria-label={t('serviceCatalog.editAction', { name: item.name })}
        className="grid size-7 shrink-0 place-items-center rounded-md text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
      >
        <Pencil className="size-3.5" aria-hidden="true" />
      </button>
    </li>
  )
}

// ---------------------------------------------------------------------------
// Create / edit dialogs — shared fields for both packages and add-ons
// ---------------------------------------------------------------------------

type CatalogItemKindLabel = 'package' | 'addon'

function CreateCatalogItemDialog({
  session,
  config,
  kind,
  titleKey,
  onClose,
}: {
  session: CompanySession
  config: VerticalPosConfig
  kind: CatalogItemKindLabel
  titleKey: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const createPackage = useCreatePackage(config, session)
  const createAddon = useCreateAddon(config, session)
  const mutation = kind === 'package' ? createPackage : createAddon
  const exp = isoMinorExponent(session.baseCurrency)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [priceInput, setPriceInput] = useState('')
  const [priceError, setPriceError] = useState<string | null>(null)

  function handlePriceChange(raw: string) {
    setPriceInput(raw)
    const val = Number(raw)
    setPriceError(raw !== '' && (isNaN(val) || val <= 0) ? t('menu.createItem.priceInvalid') : null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(priceInput)
    if (isNaN(val) || val <= 0) return
    const priceMinor = Math.round(val * 10 ** exp)
    mutation.mutate(
      {
        businessId: session.businessId,
        name: name.trim(),
        description: description.trim() || undefined,
        priceMinor,
        currency: session.baseCurrency,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && priceInput.length > 0 && !priceError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t(titleKey)}</h2>

        <Field label={t('serviceCatalog.nameLabel')} htmlFor="ci-name">
          <TextInput
            id="ci-name"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('serviceCatalog.namePlaceholder')}
            required
          />
        </Field>

        <Field label={t('serviceCatalog.descriptionLabel')} htmlFor="ci-desc">
          <TextInput
            id="ci-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t('serviceCatalog.descriptionPlaceholder')}
          />
        </Field>

        <Field
          label={t('serviceCatalog.priceLabel', { currency: session.baseCurrency })}
          htmlFor="ci-price"
          error={priceError ?? undefined}
        >
          <TextInput
            id="ci-price"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={priceInput}
            onChange={(e) => handlePriceChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('serviceCatalog.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('serviceCatalog.creating') : t('serviceCatalog.create')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

function EditCatalogItemDialog({
  session,
  config,
  kind,
  item,
  titleKey,
  onClose,
}: {
  session: CompanySession
  config: VerticalPosConfig
  kind: CatalogItemKindLabel
  item: CatalogItemResponse
  titleKey: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const updatePackage = useUpdatePackage(config, session)
  const updateAddon = useUpdateAddon(config, session)
  const mutation = kind === 'package' ? updatePackage : updateAddon
  const exp = isoMinorExponent(item.currency)

  const [name, setName] = useState(item.name)
  const [description, setDescription] = useState(item.description ?? '')
  const [priceInput, setPriceInput] = useState(String(item.priceMinor / 10 ** exp))
  const [priceError, setPriceError] = useState<string | null>(null)

  function handlePriceChange(raw: string) {
    setPriceInput(raw)
    const val = Number(raw)
    setPriceError(raw !== '' && (isNaN(val) || val <= 0) ? t('menu.createItem.priceInvalid') : null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(priceInput)
    if (isNaN(val) || val <= 0) return
    const priceMinor = Math.round(val * 10 ** exp)
    mutation.mutate(
      {
        id: item.id,
        name: name.trim(),
        description: description.trim() || null,
        priceMinor,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && priceInput.length > 0 && !priceError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t(titleKey)}</h2>

        <Field label={t('serviceCatalog.nameLabel')} htmlFor="ci-edit-name">
          <TextInput
            id="ci-edit-name"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('serviceCatalog.namePlaceholder')}
            required
          />
        </Field>

        <Field label={t('serviceCatalog.descriptionLabel')} htmlFor="ci-edit-desc">
          <TextInput
            id="ci-edit-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t('serviceCatalog.descriptionPlaceholder')}
          />
        </Field>

        <Field
          label={t('serviceCatalog.priceLabel', { currency: item.currency })}
          htmlFor="ci-edit-price"
          error={priceError ?? undefined}
        >
          <TextInput
            id="ci-edit-price"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={priceInput}
            onChange={(e) => handlePriceChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('serviceCatalog.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('serviceCatalog.submitting') : t('serviceCatalog.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Washers section (staff profiles)
// ---------------------------------------------------------------------------

function WashersSection({
  session,
  config,
  canDashboard,
}: {
  session: CompanySession
  config: VerticalPosConfig
  /** ADR 0049 P3b — gates WasherDialog's useEmployees call; see CatalogManagementInner's doc. */
  canDashboard: boolean
}) {
  const { t } = useTranslation()
  const query = useStaffProfiles(config, session, { includeInactive: true })
  const updateMutation = useUpdateStaffProfile(config, session)
  const [dialog, setDialog] = useState<'create' | StaffProfileResponse | null>(null)

  const items = query.data ?? []

  return (
    <Card className="p-5">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-display text-base font-semibold text-ink">{t(config.staffTitleKey)}</h2>
        <Button size="sm" onClick={() => setDialog('create')}>
          <Plus className="size-4" aria-hidden="true" />
          {t(config.staffLabels.addLabelKey)}
        </Button>
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={3} className="rounded-none border-0" />
      ) : items.length === 0 ? (
        <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
          {t(config.staffLabels.emptyKey)}
        </p>
      ) : (
        <ul className="space-y-1.5">
          {items.map((s) => (
            <li
              key={s.id}
              className={cn(
                'flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-hover',
                !s.active && 'opacity-55',
              )}
            >
              <span className="grid size-8 shrink-0 place-items-center rounded-full bg-emerald-tint text-emerald-2">
                <Wrench className="size-3.5" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-semibold text-ink">{s.displayLabel}</span>
                  {!s.active ? <Badge tone="neutral">{t('serviceCatalog.inactive')}</Badge> : null}
                </div>
              </div>
              <button
                type="button"
                onClick={() => updateMutation.mutate({ id: s.id, active: !s.active })}
                disabled={updateMutation.isPending}
                className="shrink-0 rounded-lg px-2.5 py-1 text-xs font-semibold text-ink-3 transition-colors hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {updateMutation.isPending ? (
                  <Spinner />
                ) : s.active ? (
                  t('serviceCatalog.deactivate')
                ) : (
                  t('serviceCatalog.activate')
                )}
              </button>
              <button
                type="button"
                onClick={() => setDialog(s)}
                aria-label={t('serviceCatalog.editAction', { name: s.displayLabel })}
                className="grid size-7 shrink-0 place-items-center rounded-md text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
              >
                <Pencil className="size-3.5" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {dialog === 'create' ? (
        <WasherDialog
          session={session}
          config={config}
          titleKey={config.staffLabels.addLabelKey}
          canDashboard={canDashboard}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog && dialog !== 'create' ? (
        <WasherDialog
          session={session}
          config={config}
          titleKey={config.staffLabels.editLabelKey}
          profile={dialog}
          canDashboard={canDashboard}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </Card>
  )
}

/**
 * The employee link dropdown REUSES the existing HR employees list (features/hr/api's
 * useEmployees) rather than a bespoke fetch — it is already a lightweight GET /api/v1/employees
 * call with no heavy UI dependencies.
 *
 * ADR 0049 P3b: `useEmployees` is DASHBOARD_ROLES-only (features/hr/api.ts always sends the
 * PERSONAL bearer) but `/catalog` itself stays reachable by a bare cashier (canPos) — so the call
 * is gated on `canDashboard`, not fired unconditionally (see CatalogManagementInner's doc). A
 * non-manager still sees the dropdown; it just degrades to "no employee linked" (the
 * `serviceCatalog.employeeNone` option) with an empty list — never a spurious 401/recovery.
 */
function WasherDialog({
  session,
  config,
  titleKey,
  profile,
  canDashboard,
  onClose,
}: {
  session: CompanySession
  config: VerticalPosConfig
  titleKey: string
  profile?: StaffProfileResponse
  canDashboard: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const createMutation = useCreateStaffProfile(config, session)
  const updateMutation = useUpdateStaffProfile(config, session)
  const mutation = profile ? updateMutation : createMutation
  const employeesQuery = useEmployees({
    companyId: session.companyId,
    actor: session.actor,
    orgUnitIds: [],
    enabled: canDashboard,
  })
  const employees = employeesQuery.data ?? []

  const [displayLabel, setDisplayLabel] = useState(profile?.displayLabel ?? '')
  const [employeeId, setEmployeeId] = useState<string>(profile?.employeeId ?? '')

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (displayLabel.trim().length === 0) return
    if (profile) {
      updateMutation.mutate(
        { id: profile.id, displayLabel: displayLabel.trim(), employeeId: employeeId || null },
        { onSuccess: () => onClose() },
      )
    } else {
      createMutation.mutate(
        {
          businessId: session.businessId,
          displayLabel: displayLabel.trim(),
          employeeId: employeeId || null,
        },
        { onSuccess: () => onClose() },
      )
    }
  }

  const canSubmit = displayLabel.trim().length > 0 && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t(titleKey)}</h2>

        <Field label={t('serviceCatalog.displayLabelLabel')} htmlFor="washer-label">
          <TextInput
            id="washer-label"
            autoFocus
            value={displayLabel}
            onChange={(e) => setDisplayLabel(e.target.value)}
            placeholder={t('serviceCatalog.displayLabelPlaceholder')}
            required
          />
        </Field>

        <Field label={t('serviceCatalog.employeeLabel')} htmlFor="washer-employee">
          <select
            id="washer-employee"
            value={employeeId}
            onChange={(e) => setEmployeeId(e.target.value)}
            className="h-[52px] w-full rounded-xl border border-line bg-surface px-4 text-[15px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          >
            <option value="">{t('serviceCatalog.employeeNone')}</option>
            {employees.map((e) => (
              <option key={e.employeeId} value={e.employeeId}>
                {e.fullName}
              </option>
            ))}
          </select>
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('serviceCatalog.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? profile
                ? t('serviceCatalog.submitting')
                : t('serviceCatalog.creating')
              : profile
                ? t('serviceCatalog.submit')
                : t('serviceCatalog.create')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
