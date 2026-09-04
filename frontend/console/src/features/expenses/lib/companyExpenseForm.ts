/**
 * companyExpenseForm.ts — pure parse/validate logic for `NewCompanyExpense.tsx`'s two submit
 * shapes (ADR 0072 P3: GENERAL category expense vs. INVENTORY ingredient purchase), extracted so
 * it is unit-testable without rendering (mirrors `features/inventory/lib/units.ts`'s pure-module
 * style). Money parsing reuses `features/pos/lib/discountInput.ts`'s major-input→minor-units
 * convention (rule 8: an invalid/empty/negative input parses to 0, never throws); ingredient
 * quantity parsing reuses `features/inventory/lib/units.ts`'s display-unit→base-unit conversion —
 * an ingredient purchase line is always entered in the ingredient's DISPLAY unit, exactly like the
 * Terima ("receive") dialog.
 */
import { parseDiscountInput } from '@/features/pos/lib/discountInput'
import { parseShownQtyInput, type UnitBearing } from '@/features/inventory/lib/units'
import type { CompanyExpenseLineInput } from '../companyExpenseApi'

// ---------------------------------------------------------------------------
// GENERAL mode
// ---------------------------------------------------------------------------

export interface GeneralExpenseDraft {
  businessId: string
  glHint: string
  description: string
  /** MAJOR-unit human entry (e.g. "150000" rupiah) — see `parseDiscountInput`. */
  amountInput: string
  /** An optional `YYYY-MM-DD` date-only input; blank means "now" (server default). */
  occurredAt: string
}

export interface ParsedGeneralExpense {
  businessId: string
  glHint: string
  description: string
  amountMinor: number
  occurredAt: string | undefined
}

/**
 * Validates + parses a GENERAL draft into the POST body's GENERAL shape, or `null` when not
 * submittable: an outlet, a non-empty description, and a strictly positive amount are all
 * required (a zero/blank/negative amount parses to 0 via `parseDiscountInput`, which this rejects).
 */
export function parseGeneralExpense(
  draft: GeneralExpenseDraft,
  currency: string,
): ParsedGeneralExpense | null {
  const description = draft.description.trim()
  if (!draft.businessId || !description) return null
  const amountMinor = parseDiscountInput(draft.amountInput, currency)
  if (amountMinor <= 0) return null
  return {
    businessId: draft.businessId,
    glHint: draft.glHint,
    description,
    amountMinor,
    occurredAt: dateOnlyToInstant(draft.occurredAt),
  }
}

// ---------------------------------------------------------------------------
// INVENTORY mode
// ---------------------------------------------------------------------------

export interface InventoryLineDraft {
  /** React key only — never sent to the server. */
  key: string
  ingredientId: string
  ingredientName: string
  /** Quantity typed in the ingredient's DISPLAY unit (kg/liter accept decimals). */
  qtyInput: string
  /** Total paid for THIS line, MAJOR units of the company base currency. */
  totalInput: string
}

/**
 * Parses one INVENTORY line into the wire shape, or `null` when incomplete/invalid: an ingredient
 * must be chosen (and resolvable — `ingredient` is `null` when the id no longer matches the
 * outlet's catalog), the quantity must convert to a strictly positive BASE integer via the
 * ingredient's own display-unit factor (fractional input on a base-unit ingredient is rejected,
 * exactly like the Terima dialog), and the amount paid must be strictly positive.
 */
export function parseInventoryLine(
  draft: InventoryLineDraft,
  ingredient: UnitBearing | null,
  currency: string,
): CompanyExpenseLineInput | null {
  if (!draft.ingredientId || !ingredient) return null
  const qtyBase = parseShownQtyInput(draft.qtyInput, ingredient)
  if (qtyBase == null || qtyBase <= 0) return null
  const valueMinor = parseDiscountInput(draft.totalInput, currency)
  if (valueMinor <= 0) return null
  return {
    ingredientId: draft.ingredientId,
    ingredientName: draft.ingredientName,
    qtyBase,
    valueMinor,
  }
}

export interface ParsedInventoryExpense {
  businessId: string
  description: string
  occurredAt: string | undefined
  lines: CompanyExpenseLineInput[]
}

/**
 * Validates + parses an INVENTORY draft: an outlet, a non-empty description, and AT LEAST ONE
 * line, where EVERY line must be individually valid (a single incomplete/invalid row blocks the
 * whole submit rather than silently dropping it — the operator must fix or remove it).
 * `ingredientOf` resolves a line's chosen ingredient id to its unit shape (or `null` if stale/
 * unknown), letting this stay a pure function independent of the TanStack Query cache shape.
 */
export function parseInventoryExpense(
  businessId: string,
  description: string,
  occurredAt: string,
  lineDrafts: readonly InventoryLineDraft[],
  ingredientOf: (ingredientId: string) => UnitBearing | null,
  currency: string,
): ParsedInventoryExpense | null {
  const trimmedDescription = description.trim()
  if (!businessId || !trimmedDescription || lineDrafts.length === 0) return null
  const lines: CompanyExpenseLineInput[] = []
  for (const draft of lineDrafts) {
    const parsed = parseInventoryLine(draft, ingredientOf(draft.ingredientId), currency)
    if (!parsed) return null
    lines.push(parsed)
  }
  return {
    businessId,
    description: trimmedDescription,
    occurredAt: dateOnlyToInstant(occurredAt),
    lines,
  }
}

/** The live grand total of every line's `valueMinor` — display-only (the server recomputes and is
 *  authoritative), used for the form's running total. Ignores lines that don't yet parse. */
export function inventoryLinesTotalMinor(
  lineDrafts: readonly InventoryLineDraft[],
  ingredientOf: (ingredientId: string) => UnitBearing | null,
  currency: string,
): number {
  return lineDrafts.reduce((sum, draft) => {
    const parsed = parseInventoryLine(draft, ingredientOf(draft.ingredientId), currency)
    return sum + (parsed?.valueMinor ?? 0)
  }, 0)
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

/**
 * An optional `YYYY-MM-DD` date-only input → an ISO Instant at LOCAL midnight, or `undefined` for
 * "now" (the server clock default) when blank/unparseable. The form has no time-of-day picker —
 * `occurredAt` only ever narrows to a calendar day.
 */
export function dateOnlyToInstant(dateOnly: string): string | undefined {
  const trimmed = dateOnly.trim()
  if (!trimmed) return undefined
  const d = new Date(`${trimmed}T00:00:00`)
  if (Number.isNaN(d.getTime())) return undefined
  return d.toISOString()
}
