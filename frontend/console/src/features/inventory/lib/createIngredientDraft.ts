/**
 * createIngredientDraft.ts — pure parse/validate logic for the "+ Tambah bahan baru" inline
 * mini-form (owner request: buying a brand-new ingredient must never require a detour to
 * /inventory first) shared by `features/expenses/NewCompanyExpense.tsx`'s ingredient line rows and
 * `features/ap/NewBill.tsx`'s Persediaan linkage picker — see `../CreateIngredientInline.tsx`.
 * Extracted so it is unit-testable without rendering (mirrors `./units.ts`'s pure-module style).
 *
 * Deliberately produces NO stock/cost fields — the purchase that follows (the company-expense line
 * or the bill line) values a brand-new, uncosted ingredient at ITS OWN price via the moving-average
 * machinery (ADR 0056); duplicating a cost here would pre-empt that.
 */
import { unitSelectionToStored } from './units'

export interface NewIngredientDraft {
  name: string
  /** A picker CHOICE (g/kg/ml/liter/pcs/pack) — same `INGREDIENT_UNIT_GROUPS` the full
   *  IngredientManagement create dialog offers, mapped to the stored base unit + display label via
   *  `unitSelectionToStored`. */
  unitChoice: string
}

export interface ParsedNewIngredient {
  businessId: string
  name: string
  unit: string
  displayUnit: string | null
}

/**
 * Validates + parses the mini-form into `useCreateIngredient`'s input shape, or `null` when not
 * submittable: an outlet and a non-empty name are both required (mirrors
 * `IngredientFormDialog`'s create-mode `nameRequired` check exactly).
 */
export function parseNewIngredientDraft(
  businessId: string,
  draft: NewIngredientDraft,
): ParsedNewIngredient | null {
  const name = draft.name.trim()
  if (!businessId || !name) return null
  const stored = unitSelectionToStored(draft.unitChoice)
  return { businessId, name, unit: stored.unit, displayUnit: stored.displayUnit }
}

/**
 * Case-insensitive, whitespace-trimmed match of `name` against the outlet's current catalog — used
 * to offer "select the existing one instead" both live (as the operator types) and after a 409
 * `ingredient-name-conflict` (the per-outlet active-name unique index — a race is always possible
 * even when nothing matched at typing time).
 */
export function findExistingIngredientByName<T extends { name: string }>(
  name: string,
  ingredients: readonly T[],
): T | null {
  const trimmed = name.trim().toLowerCase()
  if (!trimmed) return null
  return ingredients.find((i) => i.name.trim().toLowerCase() === trimmed) ?? null
}
