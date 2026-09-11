/**
 * IngredientManagement — the per-outlet ingredient (bahan) catalog behind the stock opname
 * (ADR 0046 phase 1), redrawn after the "Native Persediaan" design (ADR 0081): the catalog reads
 * in DAYS, not quantities. Every `/inventory/*` route lands here with a `page`; the host resolves
 * the outlet (NoCompany guard → OutletGate, restaurant, real outlet id per ADR 0012 → keyed
 * remount) and hands the page its data.
 *
 * Screens are ROUTES (ADR 0075 / 0078: a destination gets a history entry, so Back pops to the
 * list with its filter intact): `/inventory` catalog, `/:id` detail, `/new` + `/:id/edit` the
 * form, `/:id/convert` the unit fix-up, `/history[/:stocktakeId]` the opname history. Below
 * `lg` each is its own screen; from `lg` the catalog is a two-pane page whose right rail shows
 * the detail, the form or the conversion for the route's ingredient, and a row click REPLACES the
 * URL (rule N5 — selection is main-content state, not a destination). The only modal is the
 * quantity keypad (Terima / Atur jumlah), an interruption over whichever screen opened it.
 *
 * Quantities are integers in the ingredient's BASE unit shown through `lib/units.ts`; cost is
 * optional integer minor units in the company base currency (rule 8); an uncosted ingredient is
 * counted at opname but never posts to the books. ADR 0072 §5 — Terima is purely a quantity: a
 * purchase WITH a payment is recorded once, in full, via the company-expense form, which posts the
 * money and applies the stock receive together.
 */
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'
import { History, Plus, Search } from 'lucide-react'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { OutletGate } from '@/components/OutletGate'
import { OutletPicker } from '@/components/OutletPicker'
import { useMediaQuery } from '@/features/pos/lib/useMediaQuery'
import { effectiveRoles, hasAnyRole, useAuth } from '@/lib/authContext'
import { canFinance } from '@/lib/rolePreset'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { ConvertUnitForm } from './ConvertUnitForm'
import { IngredientDetail } from './IngredientDetail'
import { IngredientForm } from './IngredientForm'
import { ICON_BUTTON, InventoryChrome } from './InventoryChrome'
import { InventoryCatalog } from './InventoryCatalog'
import { useCatalogOrder, useDaysLeftPreference } from './catalogState'
import { QtyKeypadSheet, type KeypadMode } from './QtyKeypadSheet'
import { StocktakeHistoryPage } from './StocktakeHistory'
import { useInventoryData } from './inventoryData'
import { shownUnit } from './lib/units'
import type { Ingredient } from './ingredientApi'

export type InventoryPage = 'catalog' | 'detail' | 'create' | 'edit' | 'convert' | 'history'

export function IngredientManagement({ page }: { page: InventoryPage }) {
  const { company } = useSession()
  const { t } = useTranslation()
  if (!company) {
    return (
      <div className="grid min-h-screen place-items-center bg-paper px-5">
        <Card className="w-full max-w-md p-10 text-center">
          <h2 className="font-display text-xl font-semibold text-ink">
            {t('dashboard.noCompany')}
          </h2>
          <p className="mt-2 text-sm text-ink-3">{t('dashboard.noCompanyHint')}</p>
        </Card>
      </div>
    )
  }
  // Keyed remount on outlet change — keypad/selection state must not bleed across outlets.
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(session) => (
        <InventoryPages
          key={session.businessId}
          session={session}
          companyName={company.name}
          baseCurrency={company.baseCurrency}
          page={page}
        />
      )}
    </OutletGate>
  )
}

/** A keypad request: which ingredient, and whether it adds a delta or sets the absolute figure. */
interface KeypadRequest {
  id: string
  mode: KeypadMode
}

function InventoryPages({
  session,
  companyName,
  baseCurrency,
  page,
}: {
  session: CompanySession
  companyName: string
  baseCurrency: string
  page: InventoryPage
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const navigate = useNavigate()
  const location = useLocation()
  const params = useParams<{ ingredientId?: string; stocktakeId?: string }>()
  const isPhone = useIsPhone()
  const twoPane = useMediaQuery('(min-width: 1024px)')
  const data = useInventoryData(session)
  const { roles, elevatedRoles } = useAuth()
  const effective = effectiveRoles(roles, elevatedRoles)
  const isOwner = hasAnyRole(effective, 'owner')
  // ADR 0072 §5 — the "record it as an expense" hint is FINANCE-gated (owner/accountant) so it
  // never links a non-finance login to a route that would 403 for them.
  const financeOk = canFinance(effective)
  const [showDays, setShowDays] = useDaysLeftPreference()
  const [search, setSearch] = useState('')
  const order = useCatalogOrder(data, locale, search)

  // The keypad is local state — an interruption, never a route. One caller arrives with it
  // pre-opened: the edit form's "Set quantity to 0" fix-forward (409 unit-change-blocked) lands on
  // the detail with `state.keypad`, and closing the sheet clears that state so it cannot replay.
  const [keypadLocal, setKeypadLocal] = useState<KeypadRequest | null>(null)
  const stateKeypad = (location.state as { keypad?: KeypadMode } | null)?.keypad
  const keypad: KeypadRequest | null =
    keypadLocal ??
    (stateKeypad && params.ingredientId ? { id: params.ingredientId, mode: stateKeypad } : null)
  const closeKeypad = () => {
    setKeypadLocal(null)
    // Same URL, search included (a bare '.' would drop `?filter=`), with the one-shot state gone.
    if (stateKeypad) {
      navigate(
        { pathname: location.pathname, search: location.search },
        { replace: true, state: null },
      )
    }
  }
  const openKeypad = (id: string, mode: KeypadMode) => setKeypadLocal({ id, mode })

  const routeIngredient: Ingredient | null = params.ingredientId
    ? (data.byId.get(params.ingredientId) ?? null)
    : null
  const needsIngredient = page === 'detail' || page === 'edit' || page === 'convert'
  // A deleted / foreign / mistyped id once the list has answered → back to the catalog.
  if (needsIngredient && data.query.isSuccess && params.ingredientId && !routeIngredient) {
    return <Navigate to="/inventory" replace />
  }

  const keypadIngredient = keypad ? (data.byId.get(keypad.id) ?? null) : null
  const keypadSheet =
    keypad && keypadIngredient ? (
      <QtyKeypadSheet
        session={session}
        ingredient={keypadIngredient}
        mode={keypad.mode}
        locale={locale}
        financeOk={financeOk}
        onClose={closeKeypad}
      />
    ) : null

  if (page === 'history') {
    return (
      <StocktakeHistoryPage
        data={data}
        companyName={companyName}
        currency={baseCurrency}
        locale={locale}
        stocktakeId={params.stocktakeId ?? null}
      />
    )
  }

  // ── the catalog frame (phone screen, or the desktop two-pane) ─────────────────────────────
  // A row is a destination on a single-column screen (push — Back returns to the list) and a
  // selection on the two-pane page (replace — the list never left).
  const openIngredient = (id: string) => navigate(`/inventory/${id}`, { replace: twoPane })
  const catalogChrome = (body: ReactNode) => (
    <InventoryChrome
      title={t('inventory.title')}
      subtitle={
        isPhone ? (
          <>
            <span className="truncate">{companyName}</span>
            <span aria-hidden="true">·</span>
            <OutletPicker variant="subtitle" />
          </>
        ) : (
          t('inventory.catalog.subtitle', {
            company: companyName,
            count: data.ingredients.length,
          })
        )
      }
      backFallback="/pos"
      phoneTrailing={
        <>
          <button
            type="button"
            onClick={() => navigate('/inventory/history')}
            aria-label={t('stocktake.historyTitle')}
            className={ICON_BUTTON}
          >
            <History className="size-[19px]" strokeWidth={1.8} aria-hidden="true" />
          </button>
          <button
            type="button"
            onClick={() => navigate('/inventory/new')}
            aria-label={t('inventory.addAction')}
            className={`${ICON_BUTTON} text-ink`}
          >
            <Plus className="size-[21px]" strokeWidth={2} aria-hidden="true" />
          </button>
        </>
      }
      desktopActions={
        <>
          <OutletPicker />
          <label className="relative hidden lg:block">
            <Search
              className="pointer-events-none absolute left-3.5 top-1/2 size-[15px] -translate-y-1/2 text-ink-400"
              aria-hidden="true"
            />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('inventory.catalog.searchPlaceholder')}
              aria-label={t('inventory.catalog.searchPlaceholder')}
              className="h-[38px] w-[250px] rounded-xl border border-line bg-surface pl-9 pr-3 text-sm text-ink placeholder:text-ink-400 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
            />
          </label>
          <Button variant="outline" onClick={() => navigate('/inventory/history')}>
            <History className="size-[15px]" aria-hidden="true" />
            <span className="hidden md:inline">{t('stocktake.historyAction')}</span>
          </Button>
          <Button onClick={() => navigate('/inventory/new')}>
            <Plus className="size-[15px]" aria-hidden="true" />
            <span className="hidden md:inline">{t('inventory.addAction')}</span>
          </Button>
        </>
      }
    >
      {body}
    </InventoryChrome>
  )

  if (twoPane) {
    // With no ingredient in the URL the rail shows the first ranked row — the one that most needs
    // a decision — without claiming it in the address bar.
    const railId = params.ingredientId ?? order.ranked[0]?.ingredient.id ?? null
    // The rail follows the route: an ingredient's detail (the first ranked row when the URL names
    // none), the create/edit form, or the conversion. The list stays put underneath every one.
    const rail: ReactNode =
      page === 'create' ? (
        <IngredientForm
          session={session}
          baseCurrency={baseCurrency}
          ingredient={null}
          variant="rail"
          onSaved={(created) =>
            navigate(created ? `/inventory/${created.id}` : '/inventory', { replace: true })
          }
          onCancel={() => navigate('/inventory', { replace: true })}
        />
      ) : page === 'edit' && routeIngredient ? (
        <IngredientForm
          session={session}
          baseCurrency={baseCurrency}
          ingredient={routeIngredient}
          variant="rail"
          onSaved={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onRemoved={() => navigate('/inventory', { replace: true })}
          onCancel={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onSetZero={() =>
            navigate(`/inventory/${routeIngredient.id}`, {
              replace: true,
              state: { keypad: 'set' },
            })
          }
        />
      ) : page === 'convert' && routeIngredient ? (
        <ConvertUnitForm
          session={session}
          ingredient={routeIngredient}
          locale={locale}
          variant="rail"
          onDone={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onCancel={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
        />
      ) : null
    return (
      <>
        {catalogChrome(
          <div className="flex items-start">
            <div className="min-w-0 flex-1">
              <InventoryCatalog
                data={data}
                order={order}
                locale={locale}
                currency={baseCurrency}
                variant="desktop"
                selectedId={page === 'create' ? null : railId}
                showDays={showDays}
                onToggleDays={() => setShowDays(!showDays)}
                isOwner={isOwner}
                onOpen={openIngredient}
                onReceive={(id) => openKeypad(id, 'receive')}
                onCreate={() => navigate('/inventory/new')}
              />
            </div>
            <aside className="sticky top-16 max-h-[calc(100dvh-4rem)] w-[352px] shrink-0 self-start overflow-y-auto border-l border-line">
              {rail ?? (
                <DesktopRailDetail
                  data={data}
                  selectedId={railId}
                  locale={locale}
                  currency={baseCurrency}
                  onReceive={(id) => openKeypad(id, 'receive')}
                  onSet={(id) => openKeypad(id, 'set')}
                />
              )}
            </aside>
          </div>,
        )}
        {keypadSheet}
      </>
    )
  }

  // ── single-column screens (phone, and tablet widths under lg) ───────────────────────────
  if (page === 'catalog') {
    return (
      <>
        {catalogChrome(
          <InventoryCatalog
            data={data}
            order={order}
            locale={locale}
            currency={baseCurrency}
            variant={isPhone ? 'phone' : 'desktop'}
            selectedId={null}
            showDays={showDays}
            onToggleDays={() => setShowDays(!showDays)}
            isOwner={isOwner}
            onOpen={openIngredient}
            onReceive={(id) => openKeypad(id, 'receive')}
            onCreate={() => navigate('/inventory/new')}
          />,
        )}
        {keypadSheet}
      </>
    )
  }

  if (page === 'create') {
    return (
      <InventoryChrome
        title={t('inventory.addTitle')}
        subtitle={companyName}
        backFallback="/inventory"
      >
        <IngredientForm
          session={session}
          baseCurrency={baseCurrency}
          ingredient={null}
          variant="page"
          onSaved={(created) =>
            navigate(created ? `/inventory/${created.id}` : '/inventory', { replace: true })
          }
          onCancel={() => navigate('/inventory', { replace: true })}
        />
      </InventoryChrome>
    )
  }

  // The remaining screens are about ONE ingredient; until the list answers there is nothing to
  // draw but the frame (the catalog query is the gate — see inventoryData.ts).
  if (!routeIngredient) {
    return (
      <InventoryChrome title={t('inventory.title')} backFallback="/inventory">
        <div className="px-4 py-6" aria-busy="true" />
      </InventoryChrome>
    )
  }

  if (page === 'edit') {
    return (
      <InventoryChrome
        title={t('inventory.editTitle')}
        subtitle={routeIngredient.name}
        backFallback={`/inventory/${routeIngredient.id}`}
      >
        <IngredientForm
          session={session}
          baseCurrency={baseCurrency}
          ingredient={routeIngredient}
          variant="page"
          onSaved={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onRemoved={() => navigate('/inventory', { replace: true })}
          onCancel={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onSetZero={() =>
            navigate(`/inventory/${routeIngredient.id}`, {
              replace: true,
              state: { keypad: 'set' },
            })
          }
        />
      </InventoryChrome>
    )
  }

  if (page === 'convert') {
    return (
      <InventoryChrome
        title={t('inventory.convertUnit.screenTitle')}
        subtitle={routeIngredient.name}
        backFallback={`/inventory/${routeIngredient.id}`}
      >
        <ConvertUnitForm
          session={session}
          ingredient={routeIngredient}
          locale={locale}
          variant="page"
          onDone={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
          onCancel={() => navigate(`/inventory/${routeIngredient.id}`, { replace: true })}
        />
      </InventoryChrome>
    )
  }

  // page === 'detail'
  const row = data.rowById.get(routeIngredient.id) ?? null
  return (
    <>
      <InventoryChrome
        title={routeIngredient.name}
        subtitle={
          routeIngredient.displayUnit
            ? t('inventory.detail.subtitleShown', {
                unit: routeIngredient.unit,
                shown: shownUnit(routeIngredient),
              })
            : t('inventory.detail.subtitle', { unit: routeIngredient.unit })
        }
        backFallback="/inventory"
      >
        {row ? (
          <IngredientDetail
            data={data}
            row={row}
            locale={locale}
            currency={baseCurrency}
            variant="page"
            onReceive={() => openKeypad(routeIngredient.id, 'receive')}
            onSet={() => openKeypad(routeIngredient.id, 'set')}
          />
        ) : null}
      </InventoryChrome>
      {keypadSheet}
    </>
  )
}

/** The desktop rail's default content: the route's ingredient, else the first ranked row. */
function DesktopRailDetail({
  data,
  selectedId,
  locale,
  currency,
  onReceive,
  onSet,
}: {
  data: ReturnType<typeof useInventoryData>
  selectedId: string | null
  locale: string
  currency: string
  onReceive: (id: string) => void
  onSet: (id: string) => void
}) {
  const row = selectedId ? (data.rowById.get(selectedId) ?? null) : null
  if (!row) return null
  return (
    <IngredientDetail
      data={data}
      row={row}
      locale={locale}
      currency={currency}
      variant="rail"
      onReceive={() => onReceive(row.ingredient.id)}
      onSet={() => onSet(row.ingredient.id)}
    />
  )
}
