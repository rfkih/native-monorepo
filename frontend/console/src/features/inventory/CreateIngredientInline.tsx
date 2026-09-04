/**
 * CreateIngredientInline — the "+ Tambah bahan baru" mini-form shared by
 * `features/expenses/NewCompanyExpense.tsx`'s INVENTORY-mode line rows and
 * `features/ap/NewBill.tsx`'s Persediaan ingredient linkage (owner request: a purchase of a
 * brand-new bahan must never require a detour to /inventory first).
 *
 * Reuses the EXISTING `POST /api/v1/ingredients` (`ingredientApi.ts`'s `useCreateIngredient` — the
 * very hook `IngredientManagement`'s own create dialog uses, never duplicated) and mirrors that
 * dialog's create-mode fields exactly (name + the `INGREDIENT_UNIT_GROUPS` unit picker), but
 * DELIBERATELY sends no `initialStockQty`/`unitCostMinor`/`costCurrency` — the purchase that
 * follows values a brand-new (uncosted) ingredient at ITS OWN price via the moving-average
 * machinery (ADR 0056); pre-setting a cost here would pre-empt that.
 *
 * `/api/v1/ingredients` is POS_ROLES at the gateway (same as every other ingredient endpoint) — a
 * pure-accountant login 403s; this surfaces the RFC-7807 `detail` rather than attempting a
 * workaround (a known, accepted gap — see the company-expense/AP-bill forms' own role-gate notes).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { DialogOverlay } from '@/features/org/parts'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { INGREDIENT_UNIT_GROUPS, useCreateIngredient, type Ingredient } from './ingredientApi'
import { findExistingIngredientByName, parseNewIngredientDraft } from './lib/createIngredientDraft'

/** The 409 `ingredient-name-conflict` problem — a duplicate ACTIVE name at this outlet (mirrors
 *  `IngredientManagement.tsx`'s identically-named guard). */
function isNameConflict(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-name-conflict')
  )
}

export function CreateIngredientInline({
  session,
  existingIngredients,
  initialName,
  onClose,
  onCreated,
  onSelectExisting,
}: {
  /** The target outlet's session — `session.businessId` MUST already be the chosen outlet (the
   *  caller builds this via `{ ...company, businessId: chosenOutletId }`, mirroring `OutletGate`'s
   *  own idiom); `useCreateIngredient` posts to exactly that outlet. */
  session: CompanySession
  /** The outlet's currently loaded catalog — used only for the live/409 "select existing instead"
   *  match; never sent anywhere. */
  existingIngredients: readonly Ingredient[]
  /** Prefills the name field — e.g. NewBill.tsx's ingredient combobox opens this with whatever the
   *  operator already typed that matched nothing, so they don't retype it. Optional; defaults to
   *  blank (IngredientManagement's own create-flow entry point). */
  initialName?: string
  onClose: () => void
  /** Called with the newly created ingredient — the caller both auto-selects it on its line AND
   *  refetches its own outlet-scoped ingredient list query. */
  onCreated: (ingredient: Ingredient) => void
  onSelectExisting: (ingredient: Ingredient) => void
}) {
  const { t } = useTranslation()
  const create = useCreateIngredient(session)
  const [name, setName] = useState(initialName ?? '')
  const [unitChoice, setUnitChoice] = useState('pcs')
  const [nameError, setNameError] = useState<string | null>(null)

  const conflict = isNameConflict(create.error)
  const matchingExisting = findExistingIngredientByName(name, existingIngredients)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsed = parseNewIngredientDraft(session.businessId, { name, unitChoice })
    if (!parsed) {
      setNameError(t('inventory.nameRequired'))
      return
    }
    setNameError(null)
    create.mutate(
      { name: parsed.name, unit: parsed.unit, displayUnit: parsed.displayUnit },
      {
        onSuccess: (created) => {
          if (created) onCreated(created)
        },
      },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('inventoryPicker.createTitle')}
        </h2>
        <p className="text-xs leading-relaxed text-ink-3">{t('inventoryPicker.createHint')}</p>

        <Field label={t('inventory.nameLabel')} htmlFor="new-ing-name" error={nameError ?? undefined}>
          <TextInput
            id="new-ing-name"
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
          <div className="space-y-2" role="radiogroup" aria-label={t('inventory.unitLabel')}>
            {INGREDIENT_UNIT_GROUPS.map((group) => (
              <div key={group.key} className="flex items-center gap-3">
                <span className="w-16 shrink-0 text-xs font-semibold text-ink-3">
                  {t(`inventory.unitGroup.${group.key}` as Parameters<typeof t>[0])}
                </span>
                <div className="flex flex-wrap gap-1.5">
                  {group.units.map((u) => {
                    const active = unitChoice === u
                    return (
                      <button
                        key={u}
                        type="button"
                        role="radio"
                        aria-checked={active}
                        onClick={() => setUnitChoice(u)}
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

        {matchingExisting && !create.isError ? (
          <p className="rounded-xl bg-tint-info px-3.5 py-2.5 text-xs leading-relaxed text-ink-2">
            {t('inventoryPicker.matchesExisting', { name: matchingExisting.name })}{' '}
            <button
              type="button"
              onClick={() => onSelectExisting(matchingExisting)}
              className="font-semibold text-brand-700 hover:underline"
            >
              {t('inventoryPicker.selectInstead')}
            </button>
          </p>
        ) : null}

        {create.isError ? (
          <div className="space-y-1.5 text-xs text-loss" role="alert">
            <p>
              {create.error instanceof ApiError && create.error.problem?.detail
                ? create.error.problem.detail
                : t('inventoryPicker.createError')}
            </p>
            {conflict && matchingExisting ? (
              <button
                type="button"
                onClick={() => onSelectExisting(matchingExisting)}
                className="font-semibold text-brand-700 hover:underline"
              >
                {t('inventoryPicker.selectInstead')}
              </button>
            ) : null}
          </div>
        ) : null}

        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            className="flex-1"
            onClick={onClose}
            disabled={create.isPending}
          >
            {t('common.cancel')}
          </Button>
          <Button type="submit" className="flex-1" disabled={create.isPending}>
            {create.isPending ? <Spinner /> : t('inventoryPicker.createSubmit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
