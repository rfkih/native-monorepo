/**
 * RecipeDrawer — build/edit a menu item's recipe (BOM, ADR 0050 phase A): the BASE ingredient
 * lines that make up one portion, plus signed quantity DELTAS scoped to a modifier option (e.g.
 * "extra cheese" +20 g, "no cheese" -20 g). Rides the Native Console Web Drawer pattern
 * (EmployeeDetailDrawer): the menu list stays visible behind the panel.
 *
 * The headline HPP (Harga Pokok Penjualan — cost of goods) shown in the header is BASE lines
 * only, recomputed CLIENT-SIDE on every edit (Σ qty × unit cost) — a live preview; the server
 * recomputes authoritatively on Save, and THAT response is what later feeds the item's HPP chip
 * on the menu list (useHppSummary). Option deltas never feed the headline figure.
 *
 * Client-side guards mirror the server's rules (base qty > 0, delta ≠ 0, no duplicate
 * ingredient+scope pair) so a bad line is caught before the round trip; a 422 rejection from the
 * server still surfaces its `detail` text verbatim (same idiom as StocktakeSheet).
 *
 * Money rule (rule 8): every figure renders via formatMoney; quantities are plain integers.
 * Strings rule (rule 9): every label is an i18n key (recipe.*). A11y: every input carries a
 * label, first load is a skeleton (not a spinner) — spinners are reserved for the Save action.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChefHat, Plus, Trash2, TriangleAlert, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { FormSkeleton } from '@/components/ui/Skeleton'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney, formatPercent } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useIngredients, type Ingredient } from '@/features/inventory/ingredientApi'
import {
  allowsFraction,
  parseShownQtyInput,
  shownUnit,
  toBaseQty,
  toDisplayQty,
  type UnitBearing,
} from '@/features/inventory/lib/units'
import { useAdminModifierGroups, type MenuItem, type ModifierGroupResponse } from './api'
import {
  computeMarginRatio,
  useAutoLinkItem,
  usePutRecipe,
  useRecipe,
  type PutRecipeLineInput,
  type Recipe,
  type RecipeCompleteness,
} from './recipeApi'

const SELECT_CLASS =
  'h-11 w-full min-w-0 rounded-lg border border-line bg-surface px-3 text-[13.5px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

// ---------------------------------------------------------------------------
// Entry point — gates the drawer on the three queries it needs (recipe, the item's modifier
// groups, and the outlet's ingredient catalog) before mounting the editable form.
// ---------------------------------------------------------------------------

export function RecipeDrawer({
  session,
  item,
  locale,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const recipeQuery = useRecipe(session, item.id)
  const groupsQuery = useAdminModifierGroups(session, item.id)
  const ingredientsQuery = useIngredients(session)

  const failed = recipeQuery.isError || groupsQuery.isError || ingredientsQuery.isError
  const ready = !!recipeQuery.data && !!groupsQuery.data && !!ingredientsQuery.data

  return (
    <Drawer onClose={onClose} ariaLabel={t('recipe.drawerLabel', { name: item.name })}>
      {ready ? (
        <RecipeEditor
          // Re-seed the draft when the SERVER recipe changes (e.g. the 1-klik auto-link below
          // refetched it) — the editor's draft state initializes once per mount.
          key={recipeQuery.data!.lines.map((l) => l.id).join('|')}
          session={session}
          item={item}
          locale={locale}
          recipe={recipeQuery.data!}
          groups={groupsQuery.data!}
          ingredients={ingredientsQuery.data!}
          onClose={onClose}
        />
      ) : (
        <>
          <div className="flex flex-none items-center justify-between gap-3 border-b border-line px-5 pb-4 pt-5">
            <h2 className="flex min-w-0 items-center gap-2 truncate font-display text-[17px] font-bold text-ink">
              <ChefHat className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
              <span className="truncate">{item.name}</span>
            </h2>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('common.close')}
              className="grid size-9 shrink-0 place-items-center rounded-[10px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
            >
              <X className="size-[18px]" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
            {failed ? (
              <div className="flex flex-col items-center gap-2 py-10 text-center">
                <TriangleAlert className="size-5 text-loss" aria-hidden="true" />
                <p className="text-sm text-loss">{t('recipe.loadError')}</p>
              </div>
            ) : (
              <FormSkeleton fields={4} />
            )}
          </div>
        </>
      )}
    </Drawer>
  )
}

// ---------------------------------------------------------------------------
// Draft line model — a client-only editable row. `key` is a stable React key (the server line id
// for a pre-existing row, a fresh uuid for one added in this session); it is NEVER sent to the
// server — only ingredientId/modifierOptionId/qtyPerPortion are (see PutRecipeLineInput).
// ---------------------------------------------------------------------------

interface DraftLine {
  key: string
  ingredientId: string
  modifierOptionId: string | null
  qty: string
}

function newLine(modifierOptionId: string | null): DraftLine {
  return { key: crypto.randomUUID(), ingredientId: '', modifierOptionId, qty: '' }
}

/** The subset of an ingredient's fields this drawer needs, unified for BOTH the active picker
 *  catalog and any ingredient already on the recipe that has since gone inactive (excluded from
 *  the active picker but still shown here from the recipe response's own cost snapshot). */
interface IngredientLike {
  name: string
  unit: string
  /** The unit SHOWN to the user (kg/liter) when it sits above the base; null = the base unit
   *  itself. A pre-existing-but-inactive recipe line has no catalog entry to read this from —
   *  see the `recipe.lines` fallback below, which treats it as absent (factor 1). */
  displayUnit: string | null
  unitCostMinor: number | null
  costCurrency: string | null
  active: boolean
}

/**
 * Parses a recipe line's typed quantity — in the ingredient's SHOWN unit — into a signed base
 * INTEGER. `parseShownQtyInput` only accepts non-negative magnitudes (it is shared with the
 * stocktake counted-qty input, which is never negative), so a modifier-option DELTA line (which
 * may be negative, e.g. "no cheese" = -20 g) strips a leading '-' before parsing and reapplies
 * the sign to the parsed base value. Returns `null` when the typed text isn't a valid quantity
 * for this ingredient (wrong shape, or a fraction on a base-unit ingredient).
 */
function parseLineQty(raw: string, ing: UnitBearing): number | null {
  const trimmed = raw.trim()
  const negative = trimmed.startsWith('-')
  const magnitude = negative ? trimmed.slice(1) : trimmed
  const base = parseShownQtyInput(magnitude, ing)
  if (base == null) return null
  return negative ? -base : base
}

function computeHppPreview(
  baseLines: DraftLine[],
  ingredientById: Map<string, IngredientLike>,
): { hppMinor: number | null; currency: string | null; completeness: RecipeCompleteness } {
  if (baseLines.length === 0) return { hppMinor: null, currency: null, completeness: 'COMPLETE' }
  let sum = 0
  let currency: string | null = null
  let missingCost = false
  let mismatch = false
  for (const line of baseLines) {
    const ing = line.ingredientId ? ingredientById.get(line.ingredientId) : undefined
    if (!ing || ing.unitCostMinor == null || ing.costCurrency == null) {
      missingCost = true
      continue
    }
    if (currency == null) currency = ing.costCurrency
    else if (currency !== ing.costCurrency) mismatch = true
    // qty is typed in the SHOWN unit (kg may be fractional) — unitCostMinor is PER-BASE, so
    // convert to the base quantity (grams) BEFORE multiplying; never scale a per-base cost by a
    // fractional kg value.
    const qty = Number(line.qty)
    if (Number.isFinite(qty)) sum += toBaseQty(qty, ing) * ing.unitCostMinor
  }
  return {
    hppMinor: currency != null ? sum : null,
    currency,
    completeness: mismatch ? 'CURRENCY_MISMATCH' : missingCost ? 'MISSING_COST' : 'COMPLETE',
  }
}

// ---------------------------------------------------------------------------
// Editor — mounted only once recipe + groups + ingredients have all loaded, so its draft state
// initializes from real data with a lazy useState initializer (no effect-driven sync).
// ---------------------------------------------------------------------------

function RecipeEditor({
  session,
  item,
  locale,
  recipe,
  groups,
  ingredients,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  locale: string
  recipe: Recipe
  groups: ModifierGroupResponse[]
  ingredients: Ingredient[]
  onClose: () => void
}) {
  const { t } = useTranslation()
  const putRecipe = usePutRecipe(session)
  const autoLink = useAutoLinkItem(session)

  // Built BEFORE the draft state so the initial qty seed below can convert each line's
  // BASE qtyPerPortion into its ingredient's SHOWN unit (e.g. 1500 g -> "1.5" for a kg item).
  const ingredientById = new Map<string, IngredientLike>()
  for (const ing of ingredients) {
    ingredientById.set(ing.id, {
      name: ing.name,
      unit: ing.unit,
      displayUnit: ing.displayUnit,
      unitCostMinor: ing.unitCostMinor,
      costCurrency: ing.costCurrency,
      active: ing.active,
    })
  }
  for (const l of recipe.lines) {
    if (!ingredientById.has(l.ingredientId)) {
      // No catalog entry (ingredient gone inactive) — the recipe response has no displayUnit of
      // its own, so this falls back to the base unit as-is (factor 1, see `lib/units.ts`).
      ingredientById.set(l.ingredientId, {
        name: l.ingredientName,
        unit: l.unit,
        displayUnit: null,
        unitCostMinor: l.unitCostMinor,
        costCurrency: l.costCurrency,
        active: l.ingredientActive,
      })
    }
  }

  const [lines, setLines] = useState<DraftLine[]>(() =>
    recipe.lines.map((l) => {
      const ing = ingredientById.get(l.ingredientId)
      const shownQty = ing ? toDisplayQty(l.qtyPerPortion, ing) : l.qtyPerPortion
      return {
        key: l.id,
        ingredientId: l.ingredientId,
        modifierOptionId: l.modifierOptionId,
        qty: String(shownQty),
      }
    }),
  )

  const pickableIngredients = [...ingredientById.entries()]
    .map(([id, ing]) => ({ id, ...ing }))
    .sort((a, b) => a.name.localeCompare(b.name, locale))

  const baseLines = lines.filter((l) => l.modifierOptionId === null)
  const groupsWithOptions = groups.filter((g) => g.options.length > 0)

  function updateLine(key: string, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }
  function removeLine(key: string) {
    setLines((prev) => prev.filter((l) => l.key !== key))
  }

  // ---- Validation — mirrors the server's rules (see recipeApi.ts docs) --------------------
  function lineError(line: DraftLine): string | null {
    if (!line.ingredientId) return t('recipe.errors.ingredientRequired')
    // Defensive fallback (ingredient not resolvable) — treat as a base unit, factor 1.
    const ing: UnitBearing = ingredientById.get(line.ingredientId) ?? { unit: '', displayUnit: null }
    // Typed in the ingredient's SHOWN unit — a fraction is only valid for kg/liter (parseLineQty
    // rejects it otherwise); the base qty this resolves to is what actually gets saved/summed.
    const qty = parseLineQty(line.qty, ing)
    if (qty == null) return t('recipe.errors.qtyInteger')
    if (line.modifierOptionId === null) {
      if (qty <= 0) return t('recipe.errors.baseQtyPositive')
    } else if (qty === 0) {
      return t('recipe.errors.deltaNonZero')
    }
    const dupe = lines.some(
      (o) =>
        o.key !== line.key &&
        o.ingredientId === line.ingredientId &&
        o.modifierOptionId === line.modifierOptionId,
    )
    if (dupe) return t('recipe.errors.duplicateIngredient')
    return null
  }

  const lineErrors = new Map(lines.map((l) => [l.key, lineError(l)]))
  const hasErrors = lines.some((l) => lineErrors.get(l.key) != null)

  // ---- Live HPP preview (base lines only) ------------------------------------------------
  const preview = computeHppPreview(baseLines, ingredientById)
  const margin = computeMarginRatio(item.priceMinor, item.currency, preview.hppMinor, preview.currency)

  function handleSave() {
    if (hasErrors) return
    const body: PutRecipeLineInput[] = lines.map((l) => {
      const ing: UnitBearing = ingredientById.get(l.ingredientId) ?? { unit: '', displayUnit: null }
      // Convert the SHOWN-unit input back to the BASE integer the server stores — `hasErrors`
      // already guarantees every line parses (parseLineQty null-check in `lineError` above).
      return {
        ingredientId: l.ingredientId,
        modifierOptionId: l.modifierOptionId,
        qtyPerPortion: parseLineQty(l.qty, ing) ?? 0,
      }
    })
    putRecipe.mutate({ itemId: item.id, lines: body }, { onSuccess: onClose })
  }

  const saveError = putRecipe.error
  const saveErrorMessage: string | null = saveError
    ? saveError instanceof ApiError && (saveError.problem?.detail || saveError.problem?.title)
      ? saveError.problem.detail || saveError.problem.title || t('recipe.saveErrorGeneric')
      : t('recipe.saveErrorGeneric')
    : null

  return (
    <>
      {/* Header — live HPP + margin, recomputed from the current draft on every keystroke. */}
      <div className="flex flex-none items-start justify-between gap-3 border-b border-line px-5 pb-4 pt-5">
        <div className="min-w-0 flex-1">
          <h2 className="flex items-center gap-2 truncate font-display text-[17px] font-bold text-ink">
            <ChefHat className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
            <span className="truncate">{item.name}</span>
          </h2>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12.5px]">
            <span className="text-ink-3">
              {t('recipe.hppLabel')}{' '}
              <span className="tnum font-mono font-semibold text-ink">
                {preview.hppMinor != null && preview.currency != null
                  ? formatMoney(preview.hppMinor, preview.currency, locale)
                  : t('recipe.hppNone')}
              </span>
            </span>
            <span className="text-ink-3">
              {t('recipe.marginLabel')}{' '}
              <span className="tnum font-mono font-semibold text-ink">
                {margin != null ? formatPercent(margin, locale) : '—'}
              </span>
            </span>
          </div>
          {baseLines.length > 0 && preview.completeness !== 'COMPLETE' ? (
            <Badge tone="amber" className="mt-1.5">
              {preview.completeness === 'CURRENCY_MISMATCH'
                ? t('recipe.completenessCurrencyMismatch')
                : t('recipe.completenessMissingCost')}
            </Badge>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-9 shrink-0 place-items-center rounded-[10px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          <X className="size-[18px]" />
        </button>
      </div>

      {/* Body */}
      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
        <SectionHeading>{t('recipe.baseSection')}</SectionHeading>
        <p className="mt-1 text-xs leading-relaxed text-ink-3">{t('recipe.baseSectionHint')}</p>
        <div className="mt-2.5 flex flex-col gap-2.5">
          {baseLines.length === 0 ? (
            <div className="rounded-xl border border-dashed border-line px-3 py-2.5">
              <p className="text-sm text-ink-3">{t('recipe.baseEmpty')}</p>
              {/* 1-klik "Lacak stok": mint a same-named 1:1 pcs ingredient + base line server-side
                  (no-op if a recipe exists). The refetched recipe re-keys the editor above. */}
              <button
                type="button"
                disabled={autoLink.isPending}
                onClick={() => autoLink.mutate(item.id)}
                className="mt-2 flex h-9 w-full items-center justify-center gap-1.5 rounded-[10px] border border-emerald-line bg-emerald-tint/40 text-[12.5px] font-semibold text-emerald-2 transition-colors hover:bg-emerald-tint disabled:opacity-60"
              >
                {autoLink.isPending ? t('recipe.autoLinkRunning') : t('recipe.autoLinkAction')}
              </button>
              {autoLink.isError ? (
                <p className="mt-1.5 text-xs text-loss">
                  {autoLink.error instanceof ApiError &&
                  typeof autoLink.error.problem?.type === 'string' &&
                  autoLink.error.problem.type.includes('recipe-auto-link-blocked')
                    ? t('recipe.autoLinkBlocked')
                    : t('recipe.autoLinkError')}
                </p>
              ) : null}
            </div>
          ) : (
            baseLines.map((line) => (
              <RecipeLineRow
                key={line.key}
                line={line}
                ingredients={pickableIngredients}
                error={lineErrors.get(line.key) ?? null}
                signed={false}
                onChange={(patch) => updateLine(line.key, patch)}
                onRemove={() => removeLine(line.key)}
              />
            ))
          )}
          <button
            type="button"
            onClick={() => setLines((prev) => [...prev, newLine(null)])}
            className="mt-0.5 flex h-10 w-full items-center justify-center gap-1.5 rounded-[11px] border border-dashed border-line-strong text-[12.5px] font-semibold text-emerald-2 transition-colors hover:border-brand-300 hover:bg-emerald-tint"
          >
            <Plus className="size-3.5" />
            {t('recipe.addLine')}
          </button>
        </div>
        <p className="mt-2.5 text-xs leading-relaxed text-ink-3">{t('recipe.roundingHint')}</p>

        {groupsWithOptions.length > 0 ? (
          <>
            <SectionHeading className="mt-6">{t('recipe.optionSection')}</SectionHeading>
            <p className="mt-1 text-xs leading-relaxed text-ink-3">{t('recipe.optionSectionHint')}</p>
            <div className="mt-2.5 flex flex-col gap-4">
              {groupsWithOptions.map((group) => (
                <div key={group.id}>
                  <div className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-ink-3">
                    {group.name}
                  </div>
                  <div className="mt-1.5 flex flex-col gap-3">
                    {group.options.map((option) => {
                      const optionLines = lines.filter((l) => l.modifierOptionId === option.id)
                      return (
                        <div key={option.id} className="rounded-[14px] border border-line p-3">
                          <div className="text-[12.5px] font-semibold text-ink">{option.name}</div>
                          <div className="mt-2 flex flex-col gap-2">
                            {optionLines.map((line) => (
                              <RecipeLineRow
                                key={line.key}
                                line={line}
                                ingredients={pickableIngredients}
                                error={lineErrors.get(line.key) ?? null}
                                signed
                                onChange={(patch) => updateLine(line.key, patch)}
                                onRemove={() => removeLine(line.key)}
                              />
                            ))}
                            <button
                              type="button"
                              onClick={() =>
                                setLines((prev) => [...prev, newLine(option.id)])
                              }
                              className="flex h-9 w-full items-center justify-center gap-1.5 rounded-[10px] border border-dashed border-line-strong text-[12px] font-semibold text-emerald-2 transition-colors hover:border-brand-300 hover:bg-emerald-tint"
                            >
                              <Plus className="size-3" />
                              {t('recipe.addDelta')}
                            </button>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>
          </>
        ) : null}
      </div>

      {/* Footer */}
      <div className="flex flex-none flex-col gap-2 border-t border-line px-5 py-4">
        {saveErrorMessage ? (
          <p className="text-xs text-loss" role="alert">
            {saveErrorMessage}
          </p>
        ) : null}
        <div className="flex gap-2.5">
          <Button
            type="button"
            variant="outline"
            className="h-11 flex-1"
            onClick={onClose}
            disabled={putRecipe.isPending}
          >
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            className="h-11 flex-1"
            onClick={handleSave}
            disabled={hasErrors || putRecipe.isPending}
          >
            {putRecipe.isPending ? <Spinner /> : t('recipe.save')}
          </Button>
        </div>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// One editable line — an ingredient select, an integer qty input (signed for option deltas), and
// a remove button. Inline error + inactive-ingredient warning render below the row.
// ---------------------------------------------------------------------------

function RecipeLineRow({
  line,
  ingredients,
  error,
  signed,
  onChange,
  onRemove,
}: {
  line: DraftLine
  ingredients: { id: string; name: string; unit: string; displayUnit: string | null; active: boolean }[]
  error: string | null
  signed: boolean
  onChange: (patch: Partial<DraftLine>) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const selected = ingredients.find((i) => i.id === line.ingredientId)
  const selectedUnitLabel = selected ? shownUnit(selected) : ''
  return (
    <div>
      <div className="flex items-center gap-2">
        <select
          aria-label={t('recipe.ingredientLabel')}
          value={line.ingredientId}
          onChange={(e) => onChange({ ingredientId: e.target.value })}
          className={cn(SELECT_CLASS, error && !line.ingredientId && 'border-loss/60')}
        >
          <option value="" disabled>
            {t('recipe.ingredientPlaceholder')}
          </option>
          {ingredients.map((ing) => (
            <option key={ing.id} value={ing.id}>
              {ing.name}
            </option>
          ))}
        </select>
        <input
          aria-label={
            signed
              ? t('recipe.deltaQtyLabel', { unit: selectedUnitLabel })
              : t('recipe.qtyLabel', { unit: selectedUnitLabel })
          }
          type="number"
          step={selected && allowsFraction(selected) ? 'any' : '1'}
          value={line.qty}
          onChange={(e) => onChange({ qty: e.target.value })}
          placeholder={signed ? '±0' : '0'}
          className={cn(
            'tnum h-11 w-[74px] shrink-0 rounded-lg border border-line bg-surface px-2 text-right font-mono text-sm text-ink transition-colors',
            'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
            error && line.ingredientId && 'border-loss/60',
          )}
        />
        <button
          type="button"
          onClick={onRemove}
          aria-label={t('recipe.removeLine')}
          className="grid size-11 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-tint-loss hover:text-loss"
        >
          <Trash2 className="size-4" aria-hidden="true" />
        </button>
      </div>
      {selected && !selected.active ? (
        <p className="mt-1 text-[11px] text-amber-2">{t('recipe.ingredientInactiveWarning')}</p>
      ) : null}
      {error ? (
        <p className="mt-1 text-[11px] text-loss" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}

function SectionHeading({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn('text-[10.5px] font-bold uppercase tracking-[0.08em] text-ink-3', className)}>
      {children}
    </div>
  )
}
