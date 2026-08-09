/**
 * recipeApi.ts — the menu-item recipe / BOM (ADR 0050 phase A). Rides the existing
 * `/api/v1/menu/**` gateway route in restaurant-service; a recipe belongs to a MENU ITEM, is a
 * FULL REPLACE on every PUT (absent lines are deleted, an empty list removes the recipe), and its
 * headline cost (HPP — Harga Pokok Penjualan) is computed server-side from BASE lines only
 * (modifierOptionId null); option-scoped lines are signed quantity DELTAS (e.g. "extra cheese"
 * +20 g, "no cheese" -20 g) that never feed the headline figure.
 *
 * Quantities are INTEGERS in the ingredient's own unit — same rule as ingredientApi.ts.
 * Money rule (rule 8): unitCostMinor/unitHppMinor are integer minor units with their own currency;
 * an ingredient with no cost on file (or a mismatched currency across lines) is surfaced via
 * `completeness` rather than silently producing a wrong number.
 * Strings rule (rule 9): no user-facing strings here.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

export type RecipeCompleteness = 'COMPLETE' | 'MISSING_COST' | 'CURRENCY_MISMATCH'

export interface RecipeLine {
  id: string
  ingredientId: string
  ingredientName: string
  unit: string
  /** null = a BASE line (part of every portion); set = a signed delta scoped to this option. */
  modifierOptionId: string | null
  qtyPerPortion: number
  unitCostMinor: number | null
  costCurrency: string | null
  ingredientActive: boolean
}

export interface Recipe {
  menuItemId: string
  lines: RecipeLine[]
  unitHppMinor: number | null
  hppCurrency: string | null
  completeness: RecipeCompleteness
}

export interface HppSummaryRow {
  menuItemId: string
  unitHppMinor: number | null
  hppCurrency: string | null
  completeness: RecipeCompleteness
}

export interface PutRecipeLineInput {
  ingredientId: string
  modifierOptionId: string | null
  qtyPerPortion: number
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

const RECIPE_KEY = (s: CompanySession, itemId: string) => ['recipe', s.companyId, itemId]
const HPP_SUMMARY_KEY = (s: CompanySession) => ['hpp-summary', s.companyId, s.businessId]

/** GET /api/v1/menu/{itemId}/recipe. Disabled while `itemId` is null (drawer closed). */
export function useRecipe(session: CompanySession, itemId: string | null) {
  return useQuery({
    queryKey: RECIPE_KEY(session, itemId ?? ''),
    enabled: itemId != null,
    queryFn: () =>
      apiFetch<Recipe>(`/api/v1/menu/${itemId}/recipe`, {
        tenant: tenantOf(session),
      }),
  })
}

/**
 * PUT /api/v1/menu/{itemId}/recipe — FULL REPLACE; invalidates both the recipe query (so the
 * drawer reopens with the saved state) and the hpp-summary (so the item's HPP/margin chip on the
 * menu list updates). Errors: 404 unknown item; 422 `recipe-validation` ProblemDetail — the
 * caller surfaces `err.problem.detail` (see MenuManagement's RecipeDrawer).
 */
export function usePutRecipe(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, lines }: { itemId: string; lines: PutRecipeLineInput[] }) =>
      apiFetch<Recipe>(`/api/v1/menu/${itemId}/recipe`, {
        method: 'PUT',
        tenant: tenantOf(session),
        body: { lines },
      }),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({ queryKey: RECIPE_KEY(session, vars.itemId) })
      void qc.invalidateQueries({ queryKey: HPP_SUMMARY_KEY(session) })
    },
  })
}

/** GET /api/v1/menu/hpp-summary?businessId= — only items WITH a recipe appear. */
export function useHppSummary(session: CompanySession) {
  return useQuery({
    queryKey: HPP_SUMMARY_KEY(session),
    queryFn: () =>
      apiFetch<HppSummaryRow[]>('/api/v1/menu/hpp-summary', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

/**
 * Margin vs the item's selling price — `(price - hpp) / price`, or null when there is nothing
 * sound to divide (no price, no HPP yet, or the HPP is priced in a different currency than the
 * item — CURRENCY_MISMATCH territory). Shared by the ItemRow chip (server-derived HPP, via
 * {@link useHppSummary}) and RecipeDrawer's header (client-side live preview) so the two never
 * drift apart on rounding or sign conventions.
 */
export function computeMarginRatio(
  priceMinor: number,
  priceCurrency: string,
  hppMinor: number | null,
  hppCurrency: string | null,
): number | null {
  if (hppMinor == null || hppCurrency == null || hppCurrency !== priceCurrency || priceMinor <= 0) {
    return null
  }
  return (priceMinor - hppMinor) / priceMinor
}
