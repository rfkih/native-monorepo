/**
 * `/expenses/record` — "Catat pengeluaran" (ADR 0072 P3): the ONE-SUBMIT company-expense input
 * that records money AND, for an ingredient purchase, stock — the console half of the ADR.
 * Owner/accountant only (the route is gated `financeAllowed` in App.tsx, same as every other
 * detailed back-office surface).
 *
 * Two modes, one submit:
 *  - "Kategori" (GENERAL) — a category expense (rent, utilities, general…), posting by the
 *    category's `glHint` (mirrors the employee expense-claim categories, `./api`'s `useCategories`).
 *  - "Belanja bahan" (INVENTORY) — an ingredient purchase: pick the outlet, add one row per
 *    ingredient (quantity in its DISPLAY unit, converted to the BASE unit via
 *    `features/inventory/lib/units.ts` — the Terima dialog's own conversion), and the total paid
 *    per line. The server SUMS the lines as the amount; this form's total is a live preview only.
 *
 * The outlet is required for BOTH modes (finance-service rejects a missing/unknown `businessId`
 * with 422) — picked once via `features/org/api.ts`'s `useOrgUnits` (accountant-readable, unlike
 * the POS-only `/api/v1/outlets`).
 */
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Info, Plus, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { useOrgUnits } from '@/features/org/api'
import { useCategories } from './api'
import { ApiError, apiFetch } from '@/lib/api'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import {
  allowsFraction,
  formatShownQty,
  shownUnit,
  toDisplayQty,
  type UnitBearing,
} from '@/features/inventory/lib/units'
import type { Ingredient } from '@/features/inventory/ingredientApi'
import { CreateIngredientInline } from '@/features/inventory/CreateIngredientInline'
import {
  inventoryLinesTotalMinor,
  parseGeneralExpense,
  parseInventoryExpense,
  parseInventoryLine,
  parsePackedQtyBase,
  type InventoryLineDraft,
} from './lib/companyExpenseForm'
import { useRecordCompanyExpense, type CompanyExpenseKind } from './companyExpenseApi'

/**
 * Today's date in the DEVICE's LOCAL calendar (YYYY-MM-DD) — feeds the "record another"/initial
 * `dateInput` default, which `dateOnlyToInstant` (`./lib/companyExpenseForm.ts`) then reads as
 * LOCAL midnight. `toISOString().slice(0, 10)` would read the UTC calendar day instead: in
 * Asia/Jakarta (UTC+7), any local time before 07:00 is still "yesterday" in UTC, so the sealed-
 * period check (this date drives the GL period) would silently default to the wrong day. `en-CA`
 * formats as `YYYY-MM-DD` (mirrors `features/inventory/ingredientApi.ts`'s `usageDayKey` trick),
 * with NO `timeZone` override so it reads the runtime's own local zone, not a hardcoded one.
 */
function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

function emptyLine(): InventoryLineDraft {
  return {
    key: crypto.randomUUID(),
    ingredientId: '',
    ingredientName: '',
    qtyInput: '',
    totalInput: '',
    receiptNameDiffers: false,
    receiptDescriptionInput: '',
    packSizeInput: '',
  }
}

/**
 * V46 — the SHOWN-unit text a line's "Isi per kemasan" pre-fills to once `ingredient` is picked:
 * `ingredient.packSize` (BASE units) divided by `shownFactor`, or `''` when the ingredient has no
 * remembered default. Mirrors `IngredientManagement.tsx`'s `SetQtyDialog`'s
 * `String(toDisplayQty(...))` idiom exactly (and `features/ap/NewBill.tsx`'s identically-named
 * helper).
 */
function packSizeToShownInput(ingredient: Ingredient): string {
  return ingredient.packSize != null ? String(toDisplayQty(ingredient.packSize, ingredient)) : ''
}

/**
 * The outlet's ingredient catalog, scoped to THIS form (not `ingredientApi.ts`'s `useIngredients`,
 * which has no `enabled` gate — this form must not fire the query before an outlet is chosen).
 * Same route/bearer as the POS picker (`/api/v1/ingredients`, POS_ROLES, outlet bearer).
 */
function useIngredientsForOutlet(params: {
  companyId: string
  actor: string
  businessId: string
  enabled: boolean
}) {
  const { companyId, actor, businessId, enabled } = params
  return useQuery({
    enabled: enabled && !!businessId,
    queryKey: ['companyExpenseIngredients', companyId, businessId],
    queryFn: async () => {
      const result = await apiFetch<Ingredient[]>('/api/v1/ingredients', {
        tenant: { companyId, actor },
        query: { businessId },
      })
      return result ?? []
    },
  })
}

export function NewCompanyExpense() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const navigate = useNavigate()
  const locale = localeOf(i18n.language)

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const currency = company?.baseCurrency ?? ''

  const [mode, setMode] = useState<CompanyExpenseKind>('GENERAL')
  const [businessId, setBusinessId] = useState(company?.businessId ?? '')
  const [categoryId, setCategoryId] = useState('')
  const [description, setDescription] = useState('')
  const [amountInput, setAmountInput] = useState('')
  const [dateInput, setDateInput] = useState(todayIso())
  const [lineDrafts, setLineDrafts] = useState<InventoryLineDraft[]>([emptyLine()])

  // A new outlet invalidates every picked ingredient row (a different outlet's catalog) — reset to
  // one blank row rather than leaving stale ids that would silently fail to resolve. Adjusted
  // DURING render (the React-endorsed "adjusting state when a prop changes" pattern), not inside a
  // `useEffect`, which would cause an extra cascading render for the same result.
  const [linesResetForOutlet, setLinesResetForOutlet] = useState(businessId)
  if (businessId !== linesResetForOutlet) {
    setLinesResetForOutlet(businessId)
    setLineDrafts([emptyLine()])
  }

  // ADR 0072 — minted ONCE per submit ATTEMPT-SET (a `useRef`, never inside the mutation itself),
  // so a lost-response retry replays instead of double-recording the GL posting / stock receive.
  // "Record another" mints a FRESH key — a new submit is a new attempt-set.
  const idempotencyKeyRef = useRef(crypto.randomUUID())

  const orgUnits = useOrgUnits({ companyId, actor, enabled: !!company })
  const categories = useCategories({ companyId, actor, enabled: !!company && mode === 'GENERAL' })
  const ingredientsQuery = useIngredientsForOutlet({
    companyId,
    actor,
    businessId,
    enabled: mode === 'INVENTORY',
  })
  const mutation = useRecordCompanyExpense({ companyId, actor })

  if (!company) {
    return (
      <EmptyState
        title={t('expenses.record.noCompany')}
        hint={t('expenses.record.noCompanyHint')}
      />
    )
  }

  const outlets = (orgUnits.data ?? []).filter((u) => u.active)
  const ingredients = ingredientsQuery.data ?? []
  const ingredientOf = (id: string): UnitBearing | null =>
    ingredients.find((i) => i.id === id) ?? null
  // Owner request — "+ Tambah bahan baru": the session `useCreateIngredient` posts against, scoped
  // to the CHOSEN outlet (mirrors `OutletGate`'s `{ ...company, businessId }` idiom exactly; only
  // meaningful once `businessId` is set — the trigger stays disabled until then).
  const ingredientCreateSession: CompanySession = { ...company, businessId }

  const selectedCategory = categories.data?.find((c) => c.id === categoryId) ?? null

  const parsedGeneral =
    mode === 'GENERAL' && selectedCategory
      ? parseGeneralExpense(
          { businessId, glHint: selectedCategory.glHint, description, amountInput, occurredAt: dateInput },
          currency,
        )
      : null
  const parsedInventory =
    mode === 'INVENTORY'
      ? parseInventoryExpense(businessId, description, dateInput, lineDrafts, ingredientOf, currency)
      : null

  const canSubmit =
    !mutation.isPending &&
    (mode === 'GENERAL' ? parsedGeneral != null : parsedInventory != null)

  const inventoryTotalMinor = inventoryLinesTotalMinor(lineDrafts, ingredientOf, currency)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (mode === 'GENERAL') {
      if (!parsedGeneral) return
      mutation.mutate({
        kind: 'GENERAL',
        businessId: parsedGeneral.businessId,
        glHint: parsedGeneral.glHint,
        description: parsedGeneral.description,
        amountMinor: parsedGeneral.amountMinor,
        currency,
        occurredAt: parsedGeneral.occurredAt,
        idempotencyKey: idempotencyKeyRef.current,
      })
    } else {
      if (!parsedInventory) return
      mutation.mutate({
        kind: 'INVENTORY',
        businessId: parsedInventory.businessId,
        description: parsedInventory.description,
        currency,
        occurredAt: parsedInventory.occurredAt,
        lines: parsedInventory.lines,
        idempotencyKey: idempotencyKeyRef.current,
      })
    }
  }

  function recordAnother() {
    idempotencyKeyRef.current = crypto.randomUUID()
    mutation.reset()
    setDescription('')
    setAmountInput('')
    setDateInput(todayIso())
    setLineDrafts([emptyLine()])
  }

  if (mutation.isSuccess && mutation.data) {
    return (
      <div className="mx-auto flex max-w-[680px] flex-col gap-[18px]">
        <Card className="p-8 text-center">
          <h1 className="font-display text-xl font-semibold text-ink">
            {t('expenses.record.success.title')}
          </h1>
          <p className="mt-2 text-sm text-ink-3">
            {t('expenses.record.success.body', { id: mutation.data.id })}
          </p>
          {mode === 'INVENTORY' ? (
            <p className="mt-4 flex items-center justify-center gap-2 rounded-xl bg-tint-info px-3.5 py-3 text-sm text-ink-2">
              <Info className="size-4 shrink-0 text-info" aria-hidden="true" />
              {t('expenses.record.success.stockNote')}
            </p>
          ) : null}
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            <Button type="button" variant="outline" onClick={() => navigate('/expenses')}>
              {t('expenses.record.success.backToList')}
            </Button>
            <Button type="button" onClick={recordAnother}>
              {t('expenses.record.success.recordAnother')}
            </Button>
          </div>
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto flex max-w-[820px] flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('expenses.record.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('expenses.record.subtitle')}</p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-5">
        <Card className="p-6">
          <Segmented<CompanyExpenseKind>
            fluid
            ariaLabel={t('expenses.record.modeLabel')}
            value={mode}
            onChange={setMode}
            options={[
              { value: 'GENERAL', label: t('expenses.record.modeGeneral') },
              { value: 'INVENTORY', label: t('expenses.record.modeInventory') },
            ]}
          />

          <div className="mt-4">
            <Field label={t('expenses.record.outletLabel')} htmlFor="ce-outlet">
              {orgUnits.isLoading ? (
                <Skeleton className="h-[52px] rounded-xl" />
              ) : outlets.length === 0 ? (
                <p className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm text-ink-3">
                  {t('expenses.record.noOutlets')}
                </p>
              ) : (
                <Select
                  id="ce-outlet"
                  value={businessId}
                  onChange={(e) => setBusinessId(e.target.value)}
                  required
                >
                  <option value="" disabled>
                    {t('expenses.record.outletPlaceholder')}
                  </option>
                  {outlets.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>
        </Card>

        {mode === 'GENERAL' ? (
          <Card className="p-6">
            <div className="flex flex-col gap-4">
              <Field label={t('expenses.record.general.categoryLabel')} htmlFor="ce-category">
                {categories.isLoading ? (
                  <Skeleton className="h-[52px] rounded-xl" />
                ) : (categories.data ?? []).length === 0 ? (
                  <p className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm text-ink-3">
                    {t('expenses.record.general.categoriesEmpty')}
                  </p>
                ) : (
                  <Select
                    id="ce-category"
                    value={categoryId}
                    onChange={(e) => setCategoryId(e.target.value)}
                    required
                  >
                    <option value="" disabled>
                      {t('expenses.record.general.categoryPlaceholder')}
                    </option>
                    {(categories.data ?? []).map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field
                  label={t('expenses.record.general.amountLabel', { currency })}
                  htmlFor="ce-amount"
                >
                  <TextInput
                    id="ce-amount"
                    type="number"
                    min="0"
                    inputMode="numeric"
                    value={amountInput}
                    onChange={(e) => setAmountInput(e.target.value)}
                    placeholder="0"
                    required
                  />
                </Field>
                <Field label={t('expenses.record.dateLabel')} htmlFor="ce-date-general">
                  <TextInput
                    id="ce-date-general"
                    type="date"
                    value={dateInput}
                    onChange={(e) => setDateInput(e.target.value)}
                    max={todayIso()}
                  />
                </Field>
              </div>

              <Field label={t('expenses.record.descriptionLabel')} htmlFor="ce-description-general">
                <TextInput
                  id="ce-description-general"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('expenses.record.descriptionPlaceholder')}
                  maxLength={500}
                  required
                />
              </Field>
            </div>
          </Card>
        ) : (
          <>
            <Card className="p-6">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label={t('expenses.record.descriptionLabel')} htmlFor="ce-description-inv">
                  <TextInput
                    id="ce-description-inv"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder={t('expenses.record.inventory.descriptionPlaceholder')}
                    maxLength={500}
                    required
                  />
                </Field>
                <Field label={t('expenses.record.dateLabel')} htmlFor="ce-date-inv">
                  <TextInput
                    id="ce-date-inv"
                    type="date"
                    value={dateInput}
                    onChange={(e) => setDateInput(e.target.value)}
                    max={todayIso()}
                  />
                </Field>
              </div>
            </Card>

            <Card className="p-6">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  {t('expenses.record.inventory.lines')}
                </h2>
              </div>

              {!businessId ? (
                <p className="text-sm text-ink-3">{t('expenses.record.inventory.pickOutletFirst')}</p>
              ) : ingredientsQuery.isLoading ? (
                <ListSkeleton rows={2} />
              ) : ingredientsQuery.isError ? (
                <p className="text-sm text-loss">{t('expenses.record.inventory.ingredientsError')}</p>
              ) : (
                <>
                  {/* Owner request — a brand-new bahan no longer blocks the form: the picker below
                      always renders (even with an empty catalog) so "+ Tambah bahan baru" can seed
                      the outlet's very first ingredient inline. */}
                  {ingredients.length === 0 ? (
                    <p className="mb-3 text-sm text-ink-3">{t('expenses.record.inventory.noIngredients')}</p>
                  ) : null}
                  <IngredientLineRows
                    lines={lineDrafts}
                    onChange={setLineDrafts}
                    ingredients={ingredients}
                    currency={currency}
                    locale={locale}
                    createSession={ingredientCreateSession}
                    refetchIngredients={() => void ingredientsQuery.refetch()}
                  />
                </>
              )}
            </Card>

            <Card className="p-6">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-ink">{t('expenses.record.inventory.total')}</span>
                <span className="tnum font-mono text-lg font-semibold text-ink">
                  {formatMoney(inventoryTotalMinor, currency, locale)}
                </span>
              </div>
              <p className="mt-2 text-xs text-ink-3">{t('expenses.record.inventory.totalNote')}</p>
            </Card>
          </>
        )}

        {mutation.isError ? (
          <p className="text-sm text-loss" role="alert">
            {mutation.error instanceof ApiError && mutation.error.problem?.detail
              ? mutation.error.problem.detail
              : t('expenses.record.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={() => navigate('/expenses')}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? <Spinner /> : t('expenses.record.submit')}
          </Button>
        </div>
      </form>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Ingredient line rows (INVENTORY mode)
// ---------------------------------------------------------------------------

function IngredientLineRows({
  lines,
  onChange,
  ingredients,
  currency,
  locale,
  createSession,
  refetchIngredients,
}: {
  lines: InventoryLineDraft[]
  onChange: (lines: InventoryLineDraft[]) => void
  ingredients: Ingredient[]
  currency: string
  locale: string
  /** Owner request — "+ Tambah bahan baru": scoped to the chosen outlet (`session.businessId`). */
  createSession: CompanySession
  refetchIngredients: () => void
}) {
  const { t } = useTranslation()
  // Which line's "+ Tambah bahan baru" mini-form is open — at most one at a time.
  const [creatingForKey, setCreatingForKey] = useState<string | null>(null)

  function updateLine(key: string, patch: Partial<InventoryLineDraft>) {
    onChange(lines.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }
  function addLine() {
    onChange([...lines, emptyLine()])
  }
  function removeLine(key: string) {
    onChange(lines.length > 1 ? lines.filter((l) => l.key !== key) : lines)
  }
  /**
   * Selects `ingredient` on `key`'s line (from either a fresh create or the "select existing
   * instead" 409 recovery) and clears its qty — a different ingredient may carry a different
   * unit/factor. V46 — ALSO pre-fills "Isi per kemasan" from the ingredient's remembered
   * `packSize` default (SHOWN unit; blank when there is none), replacing whatever was there for
   * the PREVIOUS ingredient — the line stays fully editable afterwards and this never writes back.
   */
  function selectIngredient(key: string, ingredient: Ingredient) {
    updateLine(key, {
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
      qtyInput: '',
      packSizeInput: packSizeToShownInput(ingredient),
    })
  }

  return (
    <div className="flex flex-col gap-3">
      {lines.map((line, idx) => {
        const ingredient = ingredients.find((i) => i.id === line.ingredientId) ?? null
        const parsed = parseInventoryLine(line, ingredient, currency)
        // The "packs × size = result" typo safety net — computed independently of `parsed` (which
        // also needs a valid total) so the readback appears as soon as qty/pack size resolve.
        const packed = ingredient ? parsePackedQtyBase(line.qtyInput, line.packSizeInput, ingredient) : null
        // F4 (code review) — true only while the field still holds EXACTLY the value it was
        // pre-filled with from the ingredient's remembered pack-size default (see `selectIngredient`
        // below); a coincidental match after manual edits is a harmless false positive, not a real one.
        const packSizeIsDefault =
          !!ingredient &&
          ingredient.packSize != null &&
          line.packSizeInput.trim() !== '' &&
          line.packSizeInput === packSizeToShownInput(ingredient)
        return (
          <div
            key={line.key}
            className="grid grid-cols-1 items-end gap-2.5 rounded-xl border border-line p-3 sm:grid-cols-[1fr_110px_140px_140px_auto]"
          >
            <Field label={t('expenses.record.inventory.ingredientLabel')} htmlFor={`ce-line-ing-${line.key}`}>
              <Select
                id={`ce-line-ing-${line.key}`}
                value={line.ingredientId}
                onChange={(e) => {
                  const chosen = ingredients.find((i) => i.id === e.target.value)
                  updateLine(line.key, {
                    ingredientId: e.target.value,
                    ingredientName: chosen?.name ?? '',
                    // A new ingredient may have a different unit/factor — the previously typed
                    // quantity would silently mean something else, so it is cleared on swap.
                    qtyInput: '',
                    // V46 — pre-fills "Isi per kemasan" from the newly picked ingredient's
                    // remembered default (blank when it has none); stays fully editable.
                    packSizeInput: chosen ? packSizeToShownInput(chosen) : '',
                  })
                }}
              >
                <option value="" disabled>
                  {t('expenses.record.inventory.ingredientPlaceholder')}
                </option>
                {ingredients.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.name}
                  </option>
                ))}
              </Select>
              {/* Owner request — a brand-new bahan is created inline, no /inventory detour. Needs
                  a chosen outlet (creation posts against `createSession.businessId`). */}
              <button
                type="button"
                onClick={() => setCreatingForKey(line.key)}
                disabled={!createSession.businessId}
                className="mt-1.5 inline-flex items-center gap-1 text-xs font-semibold text-brand-700 hover:underline disabled:cursor-not-allowed disabled:text-ink-3 disabled:no-underline disabled:hover:no-underline"
              >
                <Plus className="size-3.5" aria-hidden="true" />
                {t('inventoryPicker.addNew')}
              </button>
            </Field>
            <Field
              label={
                line.packSizeInput.trim() !== ''
                  ? t('inventoryPicker.qtyPacksLabel')
                  : t('expenses.record.inventory.qtyLabel', {
                      unit: ingredient ? shownUnit(ingredient) : '',
                    })
              }
              htmlFor={`ce-line-qty-${line.key}`}
            >
              <TextInput
                id={`ce-line-qty-${line.key}`}
                type="number"
                min="0"
                step={
                  line.packSizeInput.trim() === '' && ingredient && allowsFraction(ingredient)
                    ? 'any'
                    : '1'
                }
                inputMode={
                  line.packSizeInput.trim() === '' && ingredient && allowsFraction(ingredient)
                    ? 'decimal'
                    : 'numeric'
                }
                value={line.qtyInput}
                onChange={(e) => updateLine(line.key, { qtyInput: e.target.value })}
                placeholder="0"
                disabled={!ingredient}
              />
            </Field>
            <Field label={t('expenses.record.inventory.totalLabel', { currency })} htmlFor={`ce-line-total-${line.key}`}>
              <TextInput
                id={`ce-line-total-${line.key}`}
                type="number"
                min="0"
                inputMode="numeric"
                value={line.totalInput}
                onChange={(e) => updateLine(line.key, { totalInput: e.target.value })}
                placeholder="0"
              />
            </Field>
            <Field label={t('expenses.record.inventory.lineValue')}>
              <p className="tnum flex h-[52px] items-center rounded-xl border border-line bg-paper px-3.5 font-mono text-sm text-ink">
                {formatMoney(parsed?.valueMinor ?? 0, currency, locale)}
              </p>
            </Field>
            <button
              type="button"
              aria-label={t('expenses.record.inventory.removeLine', { n: idx + 1 })}
              onClick={() => removeLine(line.key)}
              disabled={lines.length === 1}
              className="grid size-10 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-tint-loss hover:text-loss disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Trash2 className="size-4" />
            </button>

            {/* Owner request (same-day follow-up) — a vendor sells by the PACK while inventory
                counts CONTENTS (e.g. a receipt says "TORTILLA 1 PCS" for a pack of 20 individual
                tortillas). Optional; blank keeps today's plain display-unit quantity. Deliberately
                separate from features/inventory/lib/units.ts's fixed 1000× kg/g family. */}
            <div className="sm:col-span-5 grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field
                label={t('inventoryPicker.packSizeLabel')}
                htmlFor={`ce-line-pack-${line.key}`}
                // F4 (code review) — while the value is still exactly the ingredient's remembered
                // default, say so instead of the generic hint, so clearing it (to switch back to a
                // per-unit purchase) is discoverable.
                hint={t(
                  packSizeIsDefault ? 'inventoryPicker.packSizeDefaultHint' : 'inventoryPicker.packSizeHint',
                )}
              >
                <TextInput
                  id={`ce-line-pack-${line.key}`}
                  type="number"
                  min="0"
                  // E3 — the pack SIZE follows the ingredient's own display-unit rule, exactly
                  // like the qty field above (decimal for kg/liter, e.g. "2.5" kg/pack; whole for
                  // pcs/pack) — never forced whole regardless of unit.
                  step={ingredient && allowsFraction(ingredient) ? 'any' : '1'}
                  inputMode={ingredient && allowsFraction(ingredient) ? 'decimal' : 'numeric'}
                  value={line.packSizeInput}
                  onChange={(e) => updateLine(line.key, { packSizeInput: e.target.value })}
                  placeholder={t('inventoryPicker.packSizePlaceholder')}
                  disabled={!ingredient}
                />
              </Field>
              {/* The typo safety net — always visible once a pack size is entered, so a scale
                  error (e.g. "200" instead of "20") is obvious BEFORE submit. Every number (packs,
                  the entered pack size, the result) goes through Intl — rule 9. */}
              {line.packSizeInput.trim() !== '' ? (
                <div className="flex items-end pb-3">
                  {ingredient && packed && packed.packs != null ? (
                    <p className="text-sm font-semibold text-emerald-2">
                      {t('inventoryPicker.packResultLine', {
                        packs: new Intl.NumberFormat(locale).format(packed.packs),
                        packSize: formatShownQty(packed.qtyBase / packed.packs, ingredient, locale),
                        result: formatShownQty(packed.qtyBase, ingredient, locale),
                        unit: shownUnit(ingredient),
                      })}
                    </p>
                  ) : (
                    <p className="text-xs text-loss">{t('inventoryPicker.packInvalid')}</p>
                  )}
                </div>
              ) : null}
            </div>

            {/* Owner request (same-day follow-up) — "Nama di nota berbeda": a supplier's invoice
                often writes its own product name (e.g. "AYAM BROILER FROZEN 1KG") that doesn't
                match the inventory item name ("Ayam fillet"). Mirrors NewBill.tsx's AP-bill
                toggle exactly. Disabled until an ingredient IS linked. */}
            <label className="flex cursor-pointer items-center gap-2 sm:col-span-5">
              <input
                type="checkbox"
                checked={line.receiptNameDiffers}
                disabled={!line.ingredientId}
                onChange={() =>
                  // BOTH directions prefill receiptDescriptionInput with the ingredient's name:
                  // turning ON starts from an edit, not a blank; turning OFF discards the
                  // independent receipt wording (it's ignored on the wire either way once off).
                  updateLine(line.key, {
                    receiptNameDiffers: !line.receiptNameDiffers,
                    receiptDescriptionInput: line.ingredientName,
                  })
                }
                className="size-4 accent-emerald disabled:cursor-not-allowed"
              />
              <span className="text-xs font-medium text-ink-2">
                {t('inventoryPicker.receiptNameDiffersLabel')}
              </span>
              <span className="text-xs text-ink-3">{t('inventoryPicker.receiptNameDiffersHint')}</span>
            </label>

            {line.receiptNameDiffers ? (
              <div className="sm:col-span-5">
                <Field
                  label={t('inventoryPicker.receiptDescriptionLabel')}
                  htmlFor={`ce-line-receipt-desc-${line.key}`}
                  hint={t('inventoryPicker.receiptDescriptionHint')}
                >
                  <TextInput
                    id={`ce-line-receipt-desc-${line.key}`}
                    value={line.receiptDescriptionInput}
                    onChange={(e) =>
                      updateLine(line.key, { receiptDescriptionInput: e.target.value })
                    }
                  />
                </Field>
              </div>
            ) : null}
          </div>
        )
      })}
      <Button type="button" variant="outline" size="sm" onClick={addLine} className="self-start">
        <Plus className="size-4" />
        {t('expenses.record.inventory.addLine')}
      </Button>

      {creatingForKey ? (
        <CreateIngredientInline
          session={createSession}
          existingIngredients={ingredients}
          onClose={() => setCreatingForKey(null)}
          onCreated={(created) => {
            refetchIngredients()
            selectIngredient(creatingForKey, created)
            setCreatingForKey(null)
          }}
          onSelectExisting={(existing) => {
            selectIngredient(creatingForKey, existing)
            setCreatingForKey(null)
          }}
        />
      ) : null}
    </div>
  )
}
