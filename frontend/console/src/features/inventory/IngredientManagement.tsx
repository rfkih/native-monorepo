/**
 * IngredientManagement — the per-outlet ingredient (bahan) catalog behind the stock opname
 * (ADR 0046 phase 1). Full-screen /ingredients route modeled on MenuManagement: NoCompany
 * guard → OutletGate (restaurant, real outlet id per ADR 0012) → keyed inner remount.
 *
 * Quantities are integers in the ingredient's own unit; the unit picker offers g/ml/pcs/pack
 * only (no kg/L — fractional quantities cannot exist, see ingredientApi.ts). Cost is
 * optional and entered in MAJOR units of the company base currency (converted exponent-aware
 * via parseDiscountInput, rendered via formatMoney — rule 8); an uncosted ingredient is
 * counted at opname but never posts to the books.
 *
 * ADR 0072 §5 — the Terima ("receive") dialog's PRICED path is demoted: it no longer accepts a
 * price (the costless quantity adjust is unchanged). A purchase WITH a payment is now recorded
 * once, in full, via the company-expense form (`/expenses/record`) — the ONLY priced entry
 * surface — which posts the money AND applies the stock receive together (finance's
 * `InventoryPurchaseRecorded` → this same `Ingredient.receive` machinery). This prevents the same
 * purchase being entered twice. The backend still accepts a priced call (backward compatible).
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowLeft, ClipboardList, Moon, Package, Plus, Sun, TriangleAlert, X } from 'lucide-react'
import { needsUnitConversion, previewConversion } from './lib/unitConversion'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { OutletGate } from '@/components/OutletGate'
import { OutletPicker } from '@/components/OutletPicker'
import { ApiError } from '@/lib/api'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canFinance } from '@/lib/rolePreset'
import { useSession, type CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { parseDiscountInput } from '@/features/pos/lib/discountInput'
import { minorToMajorInput } from '@/features/pos/lib/registerFloat'
import { StocktakeHistorySheet } from './StocktakeHistorySheet'
import {
  allowsFraction,
  formatShownQty,
  parseShownQtyInput,
  shownFactor,
  shownUnit,
  shownUnitCostMinor,
  storedToUnitSelection,
  toDisplayQty,
  unitSelectionToStored,
} from './lib/units'
import {
  INGREDIENT_UNIT_GROUPS,
  useConvertIngredientUnit,
  useAddIngredientStock,
  useCreateIngredient,
  useDeactivateIngredient,
  useIngredients,
  useSetIngredientStock,
  useUpdateIngredient,
  type Ingredient,
} from './ingredientApi'

export function IngredientManagement() {
  const { company } = useSession()
  const { t } = useTranslation()
  if (!company) {
    return (
      <div className="grid min-h-screen place-items-center bg-paper px-5">
        <Card className="w-full max-w-md p-10 text-center">
          <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
          <p className="mt-2 text-sm text-ink-3">{t('dashboard.noCompanyHint')}</p>
        </Card>
      </div>
    )
  }
  // Keyed remount on outlet change — dialog/edit state must not bleed across outlets.
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(session) => (
        <IngredientManagementInner
          key={session.businessId}
          session={session}
          baseCurrency={company.baseCurrency}
        />
      )}
    </OutletGate>
  )
}

function IngredientManagementInner({
  session,
  baseCurrency,
}: {
  session: CompanySession
  baseCurrency: string
}) {
  const { t, i18n } = useTranslation()
  const { theme, toggle } = useTheme()
  const locale = localeOf(i18n.language)

  const query = useIngredients(session)
  const [showCreate, setShowCreate] = useState(false)
  const [editing, setEditing] = useState<Ingredient | null>(null)
  const [receiving, setReceiving] = useState<Ingredient | null>(null)
  const [setting, setSetting] = useState<Ingredient | null>(null)
  const [converting, setConverting] = useState<Ingredient | null>(null)
  // Riwayat opname (V42 usage in the detail).
  const [showHistory, setShowHistory] = useState(false)

  const ingredients = query.data ?? []

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      {/* Header — mirrors MenuManagement chrome */}
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        <Link
          to="/pos"
          aria-label={t('inventory.backToPos')}
          title={t('inventory.backToPos')}
          className="grid size-[38px] shrink-0 place-items-center rounded-full border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          <ArrowLeft className="size-[18px]" />
        </Link>

        <div className="min-w-0 flex-1">
          <div className="font-display text-[17px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {t('inventory.title')}
          </div>
          <div className="truncate text-xs text-ink-3">{session.name}</div>
        </div>

        {/* Phone: the picker drops to its own full-width second row (order-last inside the
            flex-wrap) — inline it crushed the title to nothing on a 360px header. */}
        <div className="min-w-0 max-sm:order-last max-sm:w-full">
          <OutletPicker />
        </div>

        {/* Riwayat opname — past counts with sistem/hitung/selisih + "terpakai hari itu" (V42). */}
        <Button
          variant="outline"
          onClick={() => setShowHistory(true)}
          aria-label={t('stocktake.historyTitle')}
          className="shrink-0"
        >
          <ClipboardList className="size-4" />
          <span className="hidden sm:inline">{t('stocktake.historyAction')}</span>
        </Button>

        <Button onClick={() => setShowCreate(true)} aria-label={t('inventory.addAction')} className="shrink-0">
          <Plus className="size-4" />
          <span className="hidden sm:inline">{t('inventory.addAction')}</span>
        </Button>

        <button
          type="button"
          onClick={toggle}
          aria-label={t('a11y.toggleTheme')}
          title={t('a11y.toggleTheme')}
          className="grid size-[38px] shrink-0 place-items-center rounded-full border border-line bg-surface text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {theme === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
        </button>
      </header>

      {/* Body */}
      <main className="min-h-0 flex-1 overflow-y-auto px-4 py-5 sm:px-5 lg:px-8">
        <div className="mx-auto max-w-3xl">
          {query.isLoading ? (
            <ListSkeleton rows={6} />
          ) : query.isError ? (
            <Card className="p-8 text-center text-sm text-loss">
              <TriangleAlert className="mx-auto mb-2 size-5" />
              {t('inventory.loadError')}
            </Card>
          ) : ingredients.length === 0 ? (
            <Card className="mx-auto max-w-md p-10 text-center">
              <div className="mx-auto grid size-12 place-items-center rounded-full bg-brand-50 text-brand-600">
                <Package className="size-5" aria-hidden="true" />
              </div>
              <h2 className="mt-3 font-display text-lg font-semibold text-ink">
                {t('inventory.emptyTitle')}
              </h2>
              <p className="mt-1.5 text-sm text-ink-3">{t('inventory.emptyHint')}</p>
              <Button className="mt-5" onClick={() => setShowCreate(true)}>
                <Plus className="size-4" /> {t('inventory.addAction')}
              </Button>
            </Card>
          ) : (
            <Card className="divide-y divide-line p-2">
              {ingredients.map((ing) => (
                <IngredientRow
                  key={ing.id}
                  ingredient={ing}
                  locale={locale}
                  onReceive={() => setReceiving(ing)}
                  onSet={() => setSetting(ing)}
                  onEdit={() => setEditing(ing)}
                  onConvert={() => setConverting(ing)}
                />
              ))}
            </Card>
          )}
        </div>
      </main>

      {showCreate ? (
        <IngredientFormDialog
          session={session}
          baseCurrency={baseCurrency}
          ingredient={null}
          onClose={() => setShowCreate(false)}
        />
      ) : null}
      {editing ? (
        <IngredientFormDialog
          session={session}
          baseCurrency={baseCurrency}
          ingredient={editing}
          onClose={() => setEditing(null)}
        />
      ) : null}
      {receiving ? (
        <ReceiveDialog
          session={session}
          ingredient={receiving}
          locale={locale}
          onClose={() => setReceiving(null)}
        />
      ) : null}
      {setting ? (
        <SetQtyDialog session={session} ingredient={setting} locale={locale} onClose={() => setSetting(null)} />
      ) : null}
      {converting ? (
        <ConvertUnitDialog
          session={session}
          ingredient={converting}
          locale={locale}
          onClose={() => setConverting(null)}
        />
      ) : null}
      {showHistory ? (
        <StocktakeHistorySheet
          session={session}
          currency={baseCurrency}
          locale={locale}
          onClose={() => setShowHistory(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

function IngredientRow({
  ingredient,
  locale,
  onReceive,
  onSet,
  onEdit,
  onConvert,
}: {
  ingredient: Ingredient
  locale: string
  onReceive: () => void
  onSet: () => void
  onEdit: () => void
  onConvert: () => void
}) {
  const { t } = useTranslation()
  const low = ingredient.stockQty === 0
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl px-3 py-3 transition-colors hover:bg-hover">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-ink">{ingredient.name}</div>
        {ingredient.unitCostMinor != null && ingredient.costCurrency != null ? (
          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
            {t('inventory.costPerUnit', {
              cost: formatMoney(
                shownUnitCostMinor(ingredient) ?? ingredient.unitCostMinor,
                ingredient.costCurrency,
                locale,
              ),
              unit: shownUnit(ingredient),
            })}
            {/* Guard: an older restaurant-service (pre-ADR 0056) omits stockValueMinor → undefined →
                formatMoney renders "IDRNaN". Only show the stock value when it's a real number. */}
            {Number.isFinite(ingredient.stockValueMinor) ? (
              <>
                {' · '}
                {t('inventory.stockValue', {
                  value: formatMoney(ingredient.stockValueMinor, ingredient.costCurrency, locale),
                })}
              </>
            ) : null}
          </div>
        ) : (
          <div className="mt-0.5 text-xs text-ink-3">{t('inventory.noCost')}</div>
        )}
        {/* A base unit nothing can be cooked from (a `pack`, or a legacy `kg` stored as the base):
            the smallest quantity a recipe can express is one whole one, so the ingredient ends up
            in no recipe at all — invisible to HPP and to shortfall detection. Surfaced on the row
            itself rather than buried in the edit dialog, because the owner has no reason to go
            looking for a problem they experience as "the recipe form won't take my number". */}
        {needsUnitConversion(ingredient) ? (
          <button
            type="button"
            onClick={onConvert}
            className="mt-1 rounded-lg border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[11px] font-semibold text-amber-700 transition-colors hover:bg-amber-500/20 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 dark:text-amber-400"
          >
            {t('inventory.convertUnit.badge', { unit: ingredient.unit })}
          </button>
        ) : null}
      </div>

      <div
        className={cn(
          'tnum shrink-0 rounded-xl border px-2.5 py-1.5 font-mono text-xs font-semibold',
          low ? 'border-loss/30 bg-tint-loss text-loss' : 'border-line bg-paper text-ink-2',
        )}
      >
        {formatShownQty(ingredient.stockQty, ingredient, locale)} {shownUnit(ingredient)}
      </div>

      {/* Phone: a full-width three-up grid with taller touch targets; desktop keeps the
          compact inline cluster. */}
      <div className="flex shrink-0 items-center gap-1.5 max-sm:grid max-sm:w-full max-sm:grid-cols-3">
        <button
          type="button"
          onClick={onReceive}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-semibold text-brand-600 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.receiveAction')}
        </button>
        <button
          type="button"
          onClick={onSet}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.setAction')}
        </button>
        <button
          type="button"
          onClick={onEdit}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.editAction')}
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Unit conversion — moving an ingredient off a base unit too coarse to cook with
// ---------------------------------------------------------------------------

/**
 * Re-expresses an ingredient in a finer base unit.
 *
 * A `pack` is a purchase container, not a unit of consumption: you buy sauce by the pack and use it
 * by the gram. With `pack` as the BASE unit the smallest quantity a recipe can express is one whole
 * pack, so the ingredient ends up in no recipe at all — invisible to HPP and to ingredient-shortfall
 * detection alike. The picker no longer offers `pack`, but ingredients created before that (and
 * legacy `kg`-as-base rows) still need moving.
 *
 * The dialog asks one question — "1 pack = how many grams?" — and previews the resulting stock, so
 * the factor is checkable before it is committed. The server rescales stock, cost, recipe lines and
 * the whole daily ledger in one transaction; total stock VALUE is unchanged, because nothing was
 * bought, sold or lost.
 */
function ConvertUnitDialog({
  session,
  ingredient,
  locale,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  useScrollLock()
  const { t } = useTranslation()
  const convert = useConvertIngredientUnit(session)
  const [unitChoice, setUnitChoice] = useState('kg')
  const [factorInput, setFactorInput] = useState('')

  const preview = previewConversion(unitChoice, factorInput, ingredient.stockQty)
  const touched = factorInput.trim() !== ''

  function handleSubmit() {
    if (!preview.ok) return
    convert.mutate(
      {
        id: ingredient.id,
        toUnit: preview.toUnit,
        toDisplayUnit: preview.toDisplayUnit,
        factor: preview.factor,
      },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogShell title={t('inventory.convertUnit.title', { name: ingredient.name })} onClose={onClose}>
      <p className="text-sm text-ink-3">
        {t('inventory.convertUnit.intro', { unit: ingredient.unit })}
      </p>

      <div className="mt-4">
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-3">
          {t('inventory.convertUnit.toUnitLabel')}
        </div>
        <div className="flex flex-col gap-2">
          {INGREDIENT_UNIT_GROUPS.map((group) => (
            <div key={group.key} className="flex flex-wrap items-center gap-1.5">
              <span className="w-14 shrink-0 text-[11px] text-ink-3">
                {t(`inventory.unitGroup.${group.key}` as Parameters<typeof t>[0])}
              </span>
              {group.units.map((u) => (
                <button
                  key={u}
                  type="button"
                  onClick={() => setUnitChoice(u)}
                  className={cn(
                    'rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition-colors',
                    unitChoice === u
                      ? 'border-brand-500 bg-emerald-tint text-brand-600'
                      : 'border-line bg-surface text-ink-2 hover:bg-hover',
                  )}
                >
                  {u}
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>

      <label className="mt-4 block">
        <span className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-ink-3">
          {t('inventory.convertUnit.factorLabel', { from: ingredient.unit, to: unitChoice })}
        </span>
        <input
          type="number"
          inputMode="numeric"
          step="1"
          min="1"
          value={factorInput}
          onChange={(e) => setFactorInput(e.target.value)}
          placeholder="1000"
          className="tnum h-11 w-full rounded-lg border border-line bg-surface px-3 text-right font-mono text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
      </label>

      {/* The preview is the whole safety mechanism: a mistyped factor is invisible as a number and
          obvious as a resulting stock figure. */}
      {touched ? (
        preview.ok ? (
          <p className="mt-2 text-xs text-ink-2">
            {t('inventory.convertUnit.preview', {
              before: `${formatShownQty(ingredient.stockQty, ingredient, locale)} ${shownUnit(ingredient)}`,
              after: `${new Intl.NumberFormat(locale).format(preview.newStockQty)} ${preview.toUnit}`,
            })}
          </p>
        ) : (
          <p className="mt-2 text-xs text-loss">
            {t(`inventory.convertUnit.errors.${preview.reason}` as Parameters<typeof t>[0])}
          </p>
        )
      ) : null}

      <p className="mt-3 rounded-lg bg-surface-2 p-2.5 text-xs text-ink-3">
        {t('inventory.convertUnit.note')}
      </p>

      {convert.isError ? (
        <p className="mt-2 text-xs text-loss">{t('inventory.convertUnit.errors.failed')}</p>
      ) : null}

      <div className="mt-5 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button onClick={handleSubmit} disabled={!preview.ok || convert.isPending}>
          {convert.isPending ? <Spinner className="size-4" /> : t('inventory.convertUnit.submit')}
        </Button>
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// Create / edit dialog (edit also hosts the two-step remove)
// ---------------------------------------------------------------------------

/** The 409 `ingredient-name-conflict` problem — a duplicate ACTIVE name at this outlet. */
function isNameConflict(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-name-conflict')
  )
}

/**
 * The 409 `ingredient-in-recipe` problem (ADR 0050 phase A) — deactivation refused because a
 * live menu-item recipe still references this ingredient. The server's `detail` NAMES the
 * referencing items, so the caller shows it verbatim rather than a generic message.
 */
function isIngredientInRecipe(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-in-recipe')
  )
}

/**
 * The 409 `ingredient-unit-change-blocked` problem (V46) — changing the BASE unit refused while
 * stock/value remains (no ratio relates pcs to grams, so rewriting it would silently reinterpret
 * the stock and poison the moving-average cost). Changing only the DISPLAY label (g → g + kg)
 * stays allowed — this only fires on a genuine base-unit swap (e.g. pcs → pack).
 */
function isUnitChangeBlocked(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-unit-change-blocked')
  )
}

function IngredientFormDialog({
  session,
  baseCurrency,
  ingredient,
  onClose,
}: {
  session: CompanySession
  baseCurrency: string
  ingredient: Ingredient | null
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const create = useCreateIngredient(session)
  const update = useUpdateIngredient(session)
  const deactivate = useDeactivateIngredient(session)

  const isCreate = ingredient == null
  const [name, setName] = useState(ingredient?.name ?? '')
  // The picker holds the CHOICE the user sees (g/kg/ml/liter/pcs/pack); it maps to a stored base
  // unit + display label via `unitSelectionToStored` on submit. Edit pre-selects the shown unit.
  const [unit, setUnit] = useState<string>(ingredient ? storedToUnitSelection(ingredient) : 'pcs')
  // Two ways to express cost, toggled on create (an owner buying from a vendor knows the TOTAL they
  // paid, not the per-unit — dividing by hand is the friction this removes). 'total' derives the
  // per-unit from the quantity below; 'unit' takes the per-unit directly (the old behaviour, and
  // the only mode when editing an existing item, which has no purchase quantity to divide by).
  const [costMode, setCostMode] = useState<'total' | 'unit'>('total')
  // Seed the cost field with the per-SHOWN-unit cost (per kg, derived exactly from the total value),
  // not the stored per-base cache — the field is labelled per satuan and satuan is the shown unit.
  const shownCostSeed = ingredient ? shownUnitCostMinor(ingredient) : null
  const initialCostInput =
    shownCostSeed != null
      ? minorToMajorInput(shownCostSeed, ingredient?.costCurrency ?? baseCurrency)
      : ''
  const [costInput, setCostInput] = useState(initialCostInput)
  const [totalInput, setTotalInput] = useState('')
  const [initialQty, setInitialQty] = useState('0')
  // V46 — the remembered pack-size DEFAULT (purchase lines pre-fill "Isi per kemasan" from this,
  // fully editable per line — see ingredientApi.ts's `Ingredient.packSize` doc). Seeded from the
  // per-SHOWN-unit value (edit), like the cost field above; interpreted against whatever unit is
  // CURRENTLY picked. F3 — a base-unit change clears this (and the cost fields) via the
  // `baseUnitChanged` effect below, so it never rides stale under a unit it was never entered in.
  const initialPackSizeInput =
    ingredient?.packSize != null ? String(toDisplayQty(ingredient.packSize, ingredient)) : ''
  const [packSizeInput, setPackSizeInput] = useState(initialPackSizeInput)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [nameError, setNameError] = useState<string | null>(null)
  const [packSizeError, setPackSizeError] = useState<string | null>(null)

  const busy = create.isPending || update.isPending || deactivate.isPending
  useBackDismiss(onClose, !busy)
  useScrollLock()
  const mutationError = create.error ?? update.error ?? deactivate.error

  // 'total' mode is only offered on create (the qty below is the divisor). Live-derive the per-unit
  // as the owner types, so they see exactly what will be booked before submitting.
  const useTotalMode = isCreate && costMode === 'total'
  const stored = unitSelectionToStored(unit)
  const factor = shownFactor(stored)
  // F3 (code review) — a base-unit change (compare STORED bases, e.g. pcs -> kg; a display-only
  // relabel like g -> kg is NOT a base change and must not trip this) invalidates the pack-size
  // default and per-unit cost: neither has a ratio to the new base, and the backend itself resets
  // `packSize`/`unitCostMinor`/`costCurrency` on a genuine base swap (V46 follow-up). The dialog
  // mirrors that: `baseUnitChanged` is true whenever the CURRENTLY picked unit's base differs from
  // the ingredient's ORIGINAL base (drives the note below + guards the "untouched" PATCH checks),
  // and the effect clears both fields the moment the base actually changes so a left-untouched
  // field never silently sends a value computed under the OLD shownFactor.
  const initialStoredUnit = ingredient ? ingredient.unit : null
  const baseUnitChanged = initialStoredUnit != null && stored.unit !== initialStoredUnit
  const lastFieldBaseRef = useRef(initialStoredUnit)
  useEffect(() => {
    if (lastFieldBaseRef.current != null && stored.unit !== lastFieldBaseRef.current) {
      setPackSizeInput('')
      setCostInput('')
      setTotalInput('')
    }
    lastFieldBaseRef.current = stored.unit
  }, [stored.unit])
  // Quantity is entered in the SHOWN unit (kg/liter accept decimals) and stored in the base unit.
  const baseQty = parseShownQtyInput(initialQty, stored) // base integer, or null (blank/invalid)
  const qtyDisplay = Number.parseFloat(initialQty)
  const qtyPositive = baseQty != null && baseQty > 0
  const totalMinor = totalInput.trim() === '' ? null : parseDiscountInput(totalInput, baseCurrency)
  // The per-BASE cost that gets stored (round(total / baseQty)), and the per-SHOWN cost shown live
  // in the hint (round(total / shown-qty)) — both from the exact total, never one scaled off the other.
  const derivedBaseCostMinor =
    totalMinor != null && baseQty != null && baseQty > 0 ? Math.round(totalMinor / baseQty) : null
  const derivedShownCostMinor =
    totalMinor != null && Number.isFinite(qtyDisplay) && qtyDisplay > 0
      ? Math.round(totalMinor / qtyDisplay)
      : null
  // V46 — the pack-size default, parsed against the CURRENTLY picked unit (kg/liter accept
  // decimals, exactly like `initialQty` — `parseShownQtyInput` both validates and rounds to a
  // whole BASE integer). `null` while blank (valid — no default) vs. genuinely unparsable/≤0 while
  // non-blank (invalid — `@Positive` server-side too).
  const packSizeTrimmed = packSizeInput.trim()
  const packSizeBase = packSizeTrimmed === '' ? null : parseShownQtyInput(packSizeInput, stored)
  const packSizeInvalid = packSizeTrimmed !== '' && (packSizeBase == null || packSizeBase <= 0)

  function handleSubmit() {
    if (!name.trim()) {
      setNameError(t('inventory.nameRequired'))
      return
    }
    if (packSizeInvalid) {
      setPackSizeError(t('inventory.packSizeInvalid'))
      return
    }
    // In 'unit' mode the cost is typed per SHOWN unit (per kg); divide by the factor to store it per
    // base unit (per g). 'total' mode already derives the per-base cost from the exact total paid.
    // On EDIT, an untouched cost field sends null (leave the stored value/cost exactly as-is) so a
    // plain rename never silently re-values through the per-shown⇄per-base rounding round-trip. F3 —
    // also gated on `!baseUnitChanged`: a base swap makes ANY string match against the OLD-base
    // seed coincidental at best, so it's never treated as "unchanged" once the base itself moved
    // (the effect above already blanked the field in the common case; this is the belt-and-braces).
    const costUnchanged = !isCreate && !baseUnitChanged && costInput.trim() === initialCostInput.trim()
    const costMinor = useTotalMode
      ? derivedBaseCostMinor
      : costUnchanged
        ? null
        : costInput.trim() === ''
          ? null
          : Math.round(parseDiscountInput(costInput, baseCurrency) / factor)
    if (ingredient) {
      // V46 — same "untouched leaves it alone" idiom as cost above, but PATCH's pack size has a
      // THIRD state (clear) the cost field doesn't: unchanged → omit both fields entirely; blank →
      // `clearPackSize: true` (the only way to remove a stored default); a real value → `packSize`.
      // F3 — same `!baseUnitChanged` gate as cost above.
      const packSizeUnchanged = !baseUnitChanged && packSizeTrimmed === initialPackSizeInput.trim()
      const packSizePatch = packSizeUnchanged
        ? {}
        : packSizeTrimmed === ''
          ? { clearPackSize: true }
          : { packSize: packSizeBase ?? undefined }
      update.mutate(
        {
          id: ingredient.id,
          name: name.trim(),
          unit: stored.unit,
          // '' explicitly CLEARS the display unit (switching kg→pcs/g); the server treats a blank as
          // "back to a base unit" and null as "leave unchanged" (PATCH), so send '' not null here —
          // else a stale display_unit='kg' with unit='pcs' would violate the pairing CHECK.
          displayUnit: stored.displayUnit ?? '',
          unitCostMinor: costMinor,
          costCurrency: costMinor != null ? baseCurrency : null,
          ...packSizePatch,
        },
        { onSuccess: onClose },
      )
    } else {
      create.mutate(
        {
          name: name.trim(),
          unit: stored.unit,
          displayUnit: stored.displayUnit,
          unitCostMinor: costMinor,
          costCurrency: costMinor != null ? baseCurrency : null,
          initialStockQty: baseQty ?? 0,
          packSize: packSizeBase,
        },
        { onSuccess: onClose },
      )
    }
  }

  return (
    <DialogShell
      title={ingredient ? t('inventory.editTitle') : t('inventory.addTitle')}
      onClose={onClose}
    >
      <div className="space-y-4">
        <Field label={t('inventory.nameLabel')} htmlFor="ing-name" error={nameError ?? undefined}>
          <TextInput
            id="ing-name"
            autoFocus
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (nameError) setNameError(null)
            }}
            placeholder={t('inventory.namePlaceholder')}
          />
        </Field>

        <Field label={t('inventory.unitLabel')} hint={t('inventory.unitHint')}>
          {/* Grouped by kind (weight / volume / count) so kg + liter slot in without a crowded row. */}
          <div className="space-y-2" role="radiogroup" aria-label={t('inventory.unitLabel')}>
            {INGREDIENT_UNIT_GROUPS.map((group) => (
              <div key={group.key} className="flex items-center gap-3">
                <span className="w-16 shrink-0 text-xs font-semibold text-ink-3">
                  {t(`inventory.unitGroup.${group.key}` as Parameters<typeof t>[0])}
                </span>
                <div className="flex flex-wrap gap-1.5">
                  {group.units.map((u) => {
                    const active = unit === u
                    return (
                      <button
                        key={u}
                        type="button"
                        role="radio"
                        aria-checked={active}
                        onClick={() => setUnit(u)}
                        className={cn(
                          'h-10 min-w-[3.25rem] rounded-lg px-3 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                          active
                            ? 'bg-emerald text-on-emerald'
                            : 'border border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
                        )}
                      >
                        {u}
                      </button>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
        </Field>

        {/* F3 — a base-unit change (not merely a display relabel) just cleared the pack-size/cost
            fields below (see `baseUnitChanged`'s doc); tell the operator why they're blank. */}
        {baseUnitChanged ? (
          <p
            className="rounded-xl bg-tint-info px-3.5 py-3 text-xs leading-relaxed text-ink-2"
            role="status"
          >
            {t('inventory.baseUnitChangedNote')}
          </p>
        ) : null}

        {/* Quantity first on create — in 'total' mode it is the divisor that turns the total paid
            into a per-unit cost, so it must be entered before the amount reads sensibly. */}
        {isCreate ? (
          <Field
            label={t(useTotalMode ? 'inventory.qtyBoughtLabel' : 'inventory.initialQtyLabel', {
              unit,
            })}
            htmlFor="ing-initial"
          >
            <TextInput
              id="ing-initial"
              type="number"
              min="0"
              step={allowsFraction(stored) ? 'any' : '1'}
              inputMode={allowsFraction(stored) ? 'decimal' : 'numeric'}
              value={initialQty}
              onChange={(e) => setInitialQty(e.target.value)}
              placeholder="0"
            />
          </Field>
        ) : null}

        {/* Cost — enter the vendor TOTAL (per-unit derived) or the per-unit directly. The toggle is
            create-only; editing an existing item has no purchase qty to divide by, so it stays
            per-unit. */}
        {isCreate ? (
          <Segmented<'total' | 'unit'>
            className="mb-2"
            fluid
            ariaLabel={t('inventory.costModeLabel')}
            value={costMode}
            onChange={setCostMode}
            options={[
              { value: 'total', label: t('inventory.costModeTotal') },
              { value: 'unit', label: t('inventory.costModeUnit') },
            ]}
          />
        ) : null}

        {useTotalMode ? (
          <Field
            label={t('inventory.totalCostLabel', { currency: baseCurrency })}
            htmlFor="ing-total"
            hint={
              derivedShownCostMinor != null
                ? t('inventory.receiveUnitPriceHint', {
                    price: formatMoney(derivedShownCostMinor, baseCurrency, locale),
                    unit,
                  })
                : totalMinor != null && !qtyPositive
                  ? t('inventory.totalNeedsQty')
                  : t('inventory.totalCostHint')
            }
          >
            <TextInput
              id="ing-total"
              type="number"
              min="0"
              inputMode="numeric"
              value={totalInput}
              onChange={(e) => setTotalInput(e.target.value)}
              placeholder={t('inventory.costPlaceholder')}
            />
          </Field>
        ) : (
          <Field
            label={t('inventory.costLabel', { currency: baseCurrency })}
            htmlFor="ing-cost"
            hint={t('inventory.costHint')}
          >
            <TextInput
              id="ing-cost"
              type="number"
              min="0"
              inputMode="numeric"
              value={costInput}
              onChange={(e) => setCostInput(e.target.value)}
              placeholder={t('inventory.costPlaceholder')}
            />
          </Field>
        )}

        {/* V46 — the remembered pack-size DEFAULT (optional). Entered in the SHOWN unit above
            (e.g. kg) and converted to BASE before sending; blank = no default. Purchase lines
            pre-fill "Isi per kemasan" from this but stay fully editable per line — brands differ,
            so this never writes back from a line, only from this dialog. */}
        <Field
          label={t('inventory.packSizeLabel', { unit })}
          htmlFor="ing-pack-size"
          hint={t('inventory.packSizeHint')}
          error={packSizeError ?? undefined}
        >
          <TextInput
            id="ing-pack-size"
            type="number"
            min="0"
            step={allowsFraction(stored) ? 'any' : '1'}
            inputMode={allowsFraction(stored) ? 'decimal' : 'numeric'}
            value={packSizeInput}
            onChange={(e) => {
              setPackSizeInput(e.target.value)
              if (packSizeError) setPackSizeError(null)
            }}
            placeholder={t('inventory.packSizePlaceholder')}
          />
        </Field>

        {mutationError ? (
          <div className="space-y-1" role="alert">
            {/* Code review F2 — the RFC-7807 `detail` string is developer diagnostics (ENGINEERING-
                STANDARDS §1.2 / rule 9), never rendered verbatim: every branch maps the stable
                `problem.type` to an i18n key. The unit-change key interpolates the specifics (item
                name, stock qty, from/to unit) from data this dialog already holds rather than
                parroting the server's own English sentence. */}
            <p className="text-xs text-loss">
              {isNameConflict(mutationError)
                ? t('inventory.nameTaken')
                : isIngredientInRecipe(mutationError)
                  ? t('recipe.ingredientInRecipeError')
                  : isUnitChangeBlocked(mutationError) && ingredient
                    ? t('inventory.unitChangeBlockedGeneric', {
                        name: ingredient.name,
                        qty: formatShownQty(ingredient.stockQty, ingredient, locale),
                        fromUnit: shownUnit(ingredient),
                        toUnit: shownUnit(stored),
                      })
                    : t('inventory.errorGeneric')}
            </p>
            {/* V46 — the fix-forward path: this is exactly what an owner hits fixing an item
                mis-created as pcs that should be weighed in kg. */}
            {isUnitChangeBlocked(mutationError) ? (
              <p className="text-xs text-ink-3">{t('inventory.unitChangeBlockedHint')}</p>
            ) : null}
          </div>
        ) : null}

        <div className="flex gap-2">
          <Button variant="outline" className="flex-1" onClick={onClose} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" onClick={handleSubmit} disabled={busy}>
            {busy ? <Spinner /> : ingredient ? t('common.save') : t('inventory.addAction')}
          </Button>
        </div>

        {ingredient ? (
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              if (!confirmRemove) {
                setConfirmRemove(true)
                return
              }
              deactivate.mutate(ingredient.id, { onSuccess: onClose })
            }}
            className="w-full rounded-lg py-2 text-center text-xs font-semibold text-loss transition-colors hover:bg-tint-loss focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-loss"
          >
            {confirmRemove ? t('inventory.removeConfirm') : t('inventory.removeAction')}
          </button>
        ) : null}
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// Receive (+delta) and set-absolute dialogs
// ---------------------------------------------------------------------------

function ReceiveDialog({
  session,
  ingredient,
  locale,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  useScrollLock()
  const { t } = useTranslation()
  const { roles, elevatedRoles } = useAuth()
  // ADR 0072 §5 — the priced Terima path is demoted (double-entry mitigation: the new company-
  // expense form is the only priced entry surface now). The hint pointing there is FINANCE-gated
  // (owner/accountant) so it never links a non-finance login to a route that would 403 for them.
  const financeOk = canFinance(effectiveRoles(roles, elevatedRoles))
  const add = useAddIngredientStock(session)
  const [amountInput, setAmountInput] = useState('')
  // The delta is typed in the SHOWN unit (kg/liter allow decimals; a negative value is a correction)
  // and sent to the API in the BASE unit. parseShownQtyInput forbids fractions/negatives, so this
  // path validates and converts by hand to keep the negative-correction affordance.
  const amountDisplay = Number.parseFloat(amountInput)
  const amountValid =
    Number.isFinite(amountDisplay) &&
    amountDisplay !== 0 &&
    (allowsFraction(ingredient) || Number.isInteger(amountDisplay))
  const amount = amountValid ? Math.round(amountDisplay * shownFactor(ingredient)) : Number.NaN
  const valid = amountValid && amount !== 0
  const isReceive = amountValid && amount > 0

  function handleSubmit() {
    if (!valid) return
    // ADR 0072 §5 — always the costless path now (no price input here — see the module doc). The
    // backend still accepts a priced call (backward compatible; InventoryPurchaseRecorded now feeds
    // the same `Ingredient.receive` machinery from the company-expense form instead).
    add.mutate({ id: ingredient.id, amount }, { onSuccess: onClose })
  }

  return (
    <DialogShell title={t('inventory.receiveTitle', { name: ingredient.name })} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-ink-3">
          {t('inventory.receiveHint', {
            qty: formatShownQty(ingredient.stockQty, ingredient, locale),
            unit: shownUnit(ingredient),
          })}
        </p>
        <Field label={t('inventory.receiveAmountLabel', { unit: shownUnit(ingredient) })} htmlFor="ing-recv">
          <TextInput
            id="ing-recv"
            type="number"
            step={allowsFraction(ingredient) ? 'any' : '1'}
            inputMode={allowsFraction(ingredient) ? 'decimal' : 'numeric'}
            autoFocus
            value={amountInput}
            onChange={(e) => setAmountInput(e.target.value)}
            placeholder="0"
          />
        </Field>
        {isReceive && financeOk ? (
          <p className="rounded-xl bg-tint-info px-3.5 py-3 text-xs leading-relaxed text-ink-2">
            {t('inventory.receivePricedHint')}{' '}
            <Link to="/expenses/record" className="font-semibold text-brand-700 hover:underline">
              {t('inventory.receivePricedHintLink')}
            </Link>
          </p>
        ) : null}
        {add.isError ? (
          <p className="text-xs text-loss" role="alert">
            {t('inventory.errorGeneric')}
          </p>
        ) : null}
        <Button className="w-full" disabled={!valid || add.isPending} onClick={handleSubmit}>
          {add.isPending ? <Spinner /> : t('inventory.receiveSubmit')}
        </Button>
      </div>
    </DialogShell>
  )
}

function SetQtyDialog({
  session,
  ingredient,
  locale,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  onClose: () => void
}) {
  useBackDismiss(onClose)
  useScrollLock()
  const { t } = useTranslation()
  const set = useSetIngredientStock(session)
  // Shown in the display unit (kg), parsed back to a base integer for the API.
  const [qtyInput, setQtyInput] = useState(String(toDisplayQty(ingredient.stockQty, ingredient)))
  const qtyBase = parseShownQtyInput(qtyInput, ingredient)
  const valid = qtyBase != null

  return (
    <DialogShell title={t('inventory.setTitle', { name: ingredient.name })} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-ink-3">
          {t('inventory.setHint', {
            qty: formatShownQty(ingredient.stockQty, ingredient, locale),
            unit: shownUnit(ingredient),
          })}
        </p>
        <Field label={t('inventory.setQtyLabel', { unit: shownUnit(ingredient) })} htmlFor="ing-setqty">
          <TextInput
            id="ing-setqty"
            type="number"
            min="0"
            step={allowsFraction(ingredient) ? 'any' : '1'}
            inputMode={allowsFraction(ingredient) ? 'decimal' : 'numeric'}
            autoFocus
            value={qtyInput}
            onChange={(e) => setQtyInput(e.target.value)}
            placeholder="0"
          />
        </Field>
        {set.isError ? (
          <p className="text-xs text-loss" role="alert">
            {t('inventory.errorGeneric')}
          </p>
        ) : null}
        <Button
          className="w-full"
          disabled={!valid || set.isPending}
          onClick={() =>
            qtyBase != null &&
            set.mutate({ id: ingredient.id, quantity: qtyBase }, { onSuccess: onClose })
          }
        >
          {set.isPending ? <Spinner /> : t('inventory.setSubmit')}
        </Button>
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// Minimal centered dialog shell (MenuManagement dialog idiom)
// ---------------------------------------------------------------------------

function DialogShell({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  return (
    // Phone: bottom-anchored sheet (the app's dialog idiom below sm); desktop stays centered.
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 backdrop-blur-sm sm:grid sm:place-items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-t-2xl border border-line bg-surface p-5 pb-[calc(1.25rem+var(--safe-area-inset-bottom,0px))] shadow-lg max-sm:max-w-full sm:rounded-card sm:pb-5">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-display text-lg font-semibold text-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
