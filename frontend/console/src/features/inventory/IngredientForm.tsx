/**
 * IngredientForm — create and edit, as a screen of its own (phone: `/inventory/new` and
 * `/inventory/:id/edit`; desktop: the two-pane rail). Edit also hosts the two-step remove.
 *
 * The rules are the ones the old dialog enforced, unchanged:
 * - the picker holds the CHOICE the user sees (g/kg/ml/liter/pcs); `unitSelectionToStored` maps it
 *   to a stored base unit + display label on submit. `pack` is no longer offered (a pack is a
 *   purchase container — see ingredientApi.ts) — pick the fine unit and set the pack size.
 * - cost is optional, entered in MAJOR units of the base currency, converted exponent-aware via
 *   `lib/moneyInput.ts` (rule 8; a field that cannot be read blocks the save, never saves 0). On CREATE it can be the vendor TOTAL (per-unit derived from the
 *   quantity) or the per-unit; on EDIT only per-unit (there is no purchase quantity to divide by).
 * - F3 — a genuine BASE-unit change (pcs → g, not a display relabel g → kg) clears the cost and
 *   pack-size fields, because neither has a ratio to the new base and the backend resets both.
 * - on EDIT an untouched field sends nothing (PATCH semantics), so a plain rename never re-values
 *   the stock through the per-shown ⇄ per-base rounding round-trip.
 *
 * Server refusals map to their stable RFC-7807 `type`, never the `detail` string (code review F2):
 * name conflict, `ingredient-in-recipe` (the console shows the GENERIC message and points at Menu &
 * prices — the server names the items in `detail`, but that string is developer diagnostics), and
 * `ingredient-unit-change-blocked` with its fix-forward action ("Set quantity to 0").
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Info, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { TextInput } from '@/components/ui/Field'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { minorToMajorInput } from '@/features/pos/lib/registerFloat'
import { MicroLabel } from './InventoryChrome'
import {
  INGREDIENT_UNIT_GROUPS,
  useCreateIngredient,
  useDeactivateIngredient,
  useUpdateIngredient,
  type Ingredient,
} from './ingredientApi'
import { parseMoneyInput, sanitizeMoneyInput } from './lib/moneyInput'
import {
  allowsFraction,
  formatShownQty,
  parseShownQtyInput,
  sanitizeShownQtyInput,
  shownFactor,
  shownQtyInputValue,
  shownUnit,
  shownUnitCostMinor,
  storedToUnitSelection,
  unitSelectionToStored,
} from './lib/units'

/** The 409 `ingredient-name-conflict` problem — a duplicate ACTIVE name at this outlet. */
function isNameConflict(err: unknown): boolean {
  return problemTypeIncludes(err, 'ingredient-name-conflict')
}

/** The 409 `ingredient-in-recipe` problem (ADR 0050 phase A) — deactivation refused because a
 *  live menu-item recipe still references this ingredient. */
function isIngredientInRecipe(err: unknown): boolean {
  return problemTypeIncludes(err, 'ingredient-in-recipe')
}

/** The 409 `ingredient-unit-change-blocked` problem (V46) — the BASE unit would change while
 *  stock/value remains. A display-only relabel (g → g + kg) never trips it. */
function isUnitChangeBlocked(err: unknown): boolean {
  return problemTypeIncludes(err, 'ingredient-unit-change-blocked')
}

function problemTypeIncludes(err: unknown, needle: string): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes(needle)
  )
}

const FIELD = 'tnum h-12 w-full rounded-[14px] px-3.5 font-mono text-base font-semibold'

export function IngredientForm({
  session,
  baseCurrency,
  ingredient,
  variant,
  onSaved,
  onRemoved,
  onCancel,
  onSetZero,
}: {
  session: CompanySession
  baseCurrency: string
  ingredient: Ingredient | null
  variant: 'page' | 'rail'
  /** The saved row — `null` only if the server answered without a body (it never should). */
  onSaved: (saved: Ingredient | null) => void
  onRemoved?: () => void
  onCancel: () => void
  /** The unit-change-blocked fix-forward: open the set-quantity keypad on this ingredient. */
  onSetZero?: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const navigate = useNavigate()
  const create = useCreateIngredient(session)
  const update = useUpdateIngredient(session)
  const deactivate = useDeactivateIngredient(session)

  const isCreate = ingredient == null
  const [name, setName] = useState(ingredient?.name ?? '')
  const [unit, setUnit] = useState<string>(ingredient ? storedToUnitSelection(ingredient) : 'pcs')
  const [costMode, setCostMode] = useState<'total' | 'unit'>('total')
  // Seed the cost field with the per-SHOWN-unit cost (per kg, derived exactly from the total
  // value), not the stored per-base cache — the field is labelled per satuan and satuan is shown.
  const shownCostSeed = ingredient ? shownUnitCostMinor(ingredient) : null
  const initialCostInput =
    shownCostSeed != null
      ? minorToMajorInput(shownCostSeed, ingredient?.costCurrency ?? baseCurrency)
      : ''
  const [costInput, setCostInput] = useState(initialCostInput)
  const [totalInput, setTotalInput] = useState('')
  const [initialQty, setInitialQty] = useState('')
  const initialPackSizeInput =
    ingredient?.packSize != null ? shownQtyInputValue(ingredient.packSize, ingredient, locale) : ''
  const [packSizeInput, setPackSizeInput] = useState(initialPackSizeInput)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [nameError, setNameError] = useState(false)
  const [packSizeError, setPackSizeError] = useState(false)
  const [costError, setCostError] = useState(false)

  const busy = create.isPending || update.isPending || deactivate.isPending
  const mutationError = create.error ?? update.error ?? deactivate.error

  const useTotalMode = isCreate && costMode === 'total'
  const stored = unitSelectionToStored(unit)
  const factor = shownFactor(stored)
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

  // Quantities are typed in the SHOWN unit (kg/liter accept decimals, either separator) and
  // stored in the base unit. `parseShownQtyInput` validates and rounds to a whole base integer.
  const baseQty = parseShownQtyInput(initialQty, stored)
  const qtyDisplay = Number.parseFloat(initialQty.replace(',', '.'))
  const qtyPositive = baseQty != null && baseQty > 0
  // Money fields are keystroke-filtered (`sanitizeMoneyInput`), so a non-blank value that still
  // fails to parse is a lone separator — flagged on submit rather than saved as a free ingredient.
  const totalMinor = parseMoneyInput(totalInput, baseCurrency)
  const totalInvalid = totalInput.trim() !== '' && totalMinor == null
  const costParsedMinor = parseMoneyInput(costInput, baseCurrency)
  const costInvalid = costInput.trim() !== '' && costParsedMinor == null
  const derivedBaseCostMinor =
    totalMinor != null && baseQty != null && baseQty > 0 ? Math.round(totalMinor / baseQty) : null
  const derivedShownCostMinor =
    totalMinor != null && Number.isFinite(qtyDisplay) && qtyDisplay > 0
      ? Math.round(totalMinor / qtyDisplay)
      : null
  const packSizeTrimmed = packSizeInput.trim()
  const packSizeBase = packSizeTrimmed === '' ? null : parseShownQtyInput(packSizeInput, stored)
  const packSizeInvalid = packSizeTrimmed !== '' && (packSizeBase == null || packSizeBase <= 0)

  function handleSubmit() {
    if (!name.trim()) {
      setNameError(true)
      return
    }
    if (packSizeInvalid) {
      setPackSizeError(true)
      return
    }
    if (useTotalMode ? totalInvalid : costInvalid) {
      setCostError(true)
      return
    }
    const costUnchanged =
      !isCreate && !baseUnitChanged && costInput.trim() === initialCostInput.trim()
    const costMinor = useTotalMode
      ? derivedBaseCostMinor
      : costUnchanged
        ? null
        : costParsedMinor == null
          ? null
          : Math.round(costParsedMinor / factor)
    if (ingredient) {
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
          // '' explicitly CLEARS the display unit; the server reads null as "leave unchanged".
          displayUnit: stored.displayUnit ?? '',
          unitCostMinor: costMinor,
          costCurrency: costMinor != null ? baseCurrency : null,
          ...packSizePatch,
        },
        { onSuccess: onSaved },
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
        { onSuccess: onSaved },
      )
    }
  }

  // The error block: a title, why, and — when there is one — the way out.
  let error: { title: string; hint: string; action?: { label: string; go: () => void } } | null =
    null
  if (mutationError) {
    if (isNameConflict(mutationError)) {
      error = { title: t('inventory.nameTaken'), hint: t('inventory.form.errNameHint') }
    } else if (isIngredientInRecipe(mutationError)) {
      error = {
        title: t('inventory.form.errRecipeTitle'),
        hint: t('inventory.form.errRecipeHint'),
        action: { label: t('inventory.form.errRecipeAction'), go: () => navigate('/menu') },
      }
    } else if (isUnitChangeBlocked(mutationError) && ingredient) {
      error = {
        title: t('inventory.form.errUnitTitle', {
          name: ingredient.name,
          from: shownUnit(ingredient),
          to: shownUnit(stored),
        }),
        hint: t('inventory.form.errUnitHint', {
          qty: formatShownQty(ingredient.stockQty, ingredient, locale),
          from: shownUnit(ingredient),
          to: shownUnit(stored),
        }),
        action: onSetZero ? { label: t('inventory.form.errUnitAction'), go: onSetZero } : undefined,
      }
    } else {
      error = { title: t('inventory.errorGeneric'), hint: '' }
    }
  }

  const rail = variant === 'rail'

  return (
    <div
      className={cn(
        'flex flex-col gap-[18px]',
        rail ? 'px-[22px] pb-7 pt-5' : 'mx-auto max-w-[640px] px-4 pb-12 pt-3',
      )}
    >
      {rail ? (
        <div className="font-display text-lg font-bold leading-tight tracking-[-0.02em] text-ink">
          {isCreate ? t('inventory.addTitle') : t('inventory.editTitle')}
        </div>
      ) : null}

      <div>
        <MicroLabel>{t('inventory.nameLabel')}</MicroLabel>
        <TextInput
          className="mt-[7px] h-12 rounded-[14px] text-base font-medium"
          autoFocus={!rail}
          value={name}
          aria-invalid={nameError || undefined}
          onChange={(e) => {
            setName(e.target.value)
            if (nameError) setNameError(false)
          }}
          placeholder={t('inventory.namePlaceholder')}
        />
        {nameError ? (
          <p className="mt-1.5 text-xs text-loss">{t('inventory.nameRequired')}</p>
        ) : null}
      </div>

      <div>
        <MicroLabel>{t('inventory.unitLabel')}</MicroLabel>
        <div
          className="mt-[9px] flex flex-col gap-2"
          role="radiogroup"
          aria-label={t('inventory.unitLabel')}
        >
          {INGREDIENT_UNIT_GROUPS.map((group) => (
            <div key={group.key} className="flex items-center gap-2.5">
              <span className="w-14 shrink-0 text-[11px] font-semibold text-ink-3">
                {t(`inventory.unitGroup.${group.key}`)}
              </span>
              <div className="flex flex-1 flex-wrap gap-[7px]">
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
                        'h-[42px] min-w-[54px] rounded-xl px-3.5 text-sm font-bold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                        active
                          ? 'bg-emerald text-on-emerald'
                          : 'border border-line bg-surface text-ink-2 hover:bg-hover',
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
        <p className="mt-[9px] text-[11.5px] leading-relaxed text-ink-400 text-pretty">
          {t('inventory.form.unitNote')}
        </p>
      </div>

      {baseUnitChanged && ingredient ? (
        <div
          className="flex items-start gap-2.5 rounded-[14px] bg-tint-info px-3.5 py-[13px]"
          role="status"
        >
          <Info className="mt-0.5 size-4 shrink-0 text-info" aria-hidden="true" />
          <span className="flex-1 text-xs leading-relaxed text-ink-2">
            {t('inventory.form.baseChangedNote', { from: ingredient.unit, to: stored.unit })}
          </span>
        </div>
      ) : null}

      {isCreate ? (
        <>
          <div>
            <MicroLabel>
              {useTotalMode ? t('inventory.form.qtyBought') : t('inventory.form.startingQty')}
            </MicroLabel>
            <div className="relative mt-[7px]">
              <input
                type="text"
                inputMode={allowsFraction(stored) ? 'decimal' : 'numeric'}
                value={initialQty}
                onChange={(e) => setInitialQty(sanitizeShownQtyInput(e.target.value))}
                placeholder="0"
                aria-label={
                  useTotalMode ? t('inventory.form.qtyBought') : t('inventory.form.startingQty')
                }
                className={cn(
                  FIELD,
                  'border border-line bg-surface pr-14 text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
                )}
              />
              <span className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-xs font-semibold text-ink-3">
                {unit}
              </span>
            </div>
          </div>
          <Segmented<'total' | 'unit'>
            fluid
            ariaLabel={t('inventory.costModeLabel')}
            value={costMode}
            onChange={setCostMode}
            options={[
              { value: 'total', label: t('inventory.costModeTotal') },
              { value: 'unit', label: t('inventory.costModeUnit') },
            ]}
          />
        </>
      ) : null}

      <div>
        <MicroLabel>
          {useTotalMode
            ? t('inventory.form.costTotalLabel')
            : t('inventory.form.costUnitLabel', { unit })}
        </MicroLabel>
        <div className="relative mt-[7px]">
          <input
            type="text"
            inputMode="decimal"
            value={useTotalMode ? totalInput : costInput}
            aria-invalid={costError || undefined}
            onChange={(e) => {
              const next = sanitizeMoneyInput(e.target.value, baseCurrency)
              if (useTotalMode) setTotalInput(next)
              else setCostInput(next)
              if (costError) setCostError(false)
            }}
            placeholder={t('inventory.costPlaceholder')}
            aria-label={
              useTotalMode
                ? t('inventory.form.costTotalLabel')
                : t('inventory.form.costUnitLabel', { unit })
            }
            className={cn(
              FIELD,
              'border border-line bg-surface pr-14 text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
            )}
          />
          <span className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-xs font-semibold text-ink-3">
            {baseCurrency}
          </span>
        </div>
        <p
          className={cn(
            'mt-[7px] text-[11.5px] font-medium leading-relaxed text-pretty',
            costError
              ? 'text-loss'
              : useTotalMode && derivedShownCostMinor != null
                ? 'text-profit-ink'
                : baseUnitChanged
                  ? 'text-amber'
                  : 'text-ink-3',
          )}
        >
          {costError
            ? t('inventory.form.costInvalid')
            : useTotalMode
              ? derivedShownCostMinor != null
                ? t('inventory.form.costDerived', {
                    price: formatMoney(derivedShownCostMinor, baseCurrency, locale),
                    unit,
                  })
                : totalMinor != null && !qtyPositive
                  ? t('inventory.totalNeedsQty')
                  : t('inventory.totalCostHint')
              : baseUnitChanged
                ? t('inventory.form.costCleared')
                : t('inventory.form.costOptional')}
        </p>
      </div>

      <div>
        <MicroLabel>{t('inventory.form.packLabel')}</MicroLabel>
        <div className="relative mt-[7px]">
          <input
            type="text"
            inputMode={allowsFraction(stored) ? 'decimal' : 'numeric'}
            value={packSizeInput}
            aria-invalid={packSizeError || undefined}
            onChange={(e) => {
              setPackSizeInput(sanitizeShownQtyInput(e.target.value))
              if (packSizeError) setPackSizeError(false)
            }}
            placeholder={t('inventory.packSizePlaceholder')}
            aria-label={t('inventory.form.packLabel')}
            className={cn(
              FIELD,
              'border border-line bg-surface pr-14 text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
            )}
          />
          <span className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-xs font-semibold text-ink-3">
            {unit}
          </span>
        </div>
        <p
          className={cn(
            'mt-[7px] text-[11.5px] leading-relaxed text-pretty',
            packSizeError ? 'text-loss' : 'text-ink-400',
          )}
        >
          {packSizeError ? t('inventory.packSizeInvalid') : t('inventory.form.packHint')}
        </p>
      </div>

      {error ? (
        <div className="flex flex-col gap-1.5 rounded-[14px] bg-tint-loss p-3.5" role="alert">
          <div className="flex items-start gap-[9px]">
            <TriangleAlert
              className="mt-0.5 size-4 shrink-0 text-loss"
              strokeWidth={2}
              aria-hidden="true"
            />
            <span className="flex-1 text-xs font-semibold leading-snug text-loss">
              {error.title}
            </span>
          </div>
          {error.hint ? (
            <p className="pl-[25px] text-xs leading-relaxed text-loss-ink text-pretty">
              {error.hint}
            </p>
          ) : null}
          {error.action ? (
            <div className="pl-[25px] pt-0.5">
              <Button size="sm" onClick={error.action.go}>
                {error.action.label}
              </Button>
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="flex gap-2">
        <Button
          variant="outline"
          size="xl"
          className="shrink-0 px-5"
          onClick={onCancel}
          disabled={busy}
        >
          {t('common.cancel')}
        </Button>
        <Button size="xl" className="flex-1" onClick={handleSubmit} disabled={busy}>
          {busy && !deactivate.isPending ? (
            <Spinner />
          ) : isCreate ? (
            t('inventory.addAction')
          ) : (
            t('common.save')
          )}
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
            deactivate.mutate(ingredient.id, { onSuccess: () => onRemoved?.() })
          }}
          className={cn(
            'min-h-11 rounded-xl px-3 text-xs font-bold text-loss-ink transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-loss',
            confirmRemove ? 'bg-tint-loss' : 'hover:bg-tint-loss',
          )}
        >
          {confirmRemove
            ? t('inventory.form.removeTwice', { name: ingredient.name })
            : t('inventory.removeAction')}
        </button>
      ) : null}
    </div>
  )
}
