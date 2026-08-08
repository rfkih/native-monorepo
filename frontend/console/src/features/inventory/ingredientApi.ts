/**
 * ingredientApi.ts — ingredient inventory (ADR 0046 phase 1). The per-outlet catalog of raw
 * materials (bahan: bread, patty, sauce…) that the stock opname counts — a SEPARATE concept
 * from per-menu-item stock, which stays the sellable-portion/86 gate.
 *
 * Quantities are INTEGERS in the ingredient's own unit (g/ml/pcs/pack — the backend stores
 * `unit` as opaque display text; the ArchUnit decimal ban makes fractional quantities
 * impossible server-side, so the picker deliberately offers no kg/L).
 *
 * Money rule (rule 8): `unitCostMinor` is integer minor units with its own `costCurrency`
 * (an ingredient has no price to ride on) — both null together = counted but never valued.
 * Strings rule (rule 9): no user-facing strings here.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** Units offered by the console picker. Backend treats the value as opaque display text. */
export const INGREDIENT_UNITS = ['g', 'ml', 'pcs', 'pack'] as const
export type IngredientUnit = (typeof INGREDIENT_UNITS)[number]

export interface Ingredient {
  id: string
  businessId: string
  name: string
  unit: string
  stockQty: number
  unitCostMinor: number | null
  costCurrency: string | null
  active: boolean
}

export const INGREDIENTS_KEY = (s: CompanySession) => ['ingredients', s.companyId, s.businessId]

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/** GET /api/v1/ingredients?businessId= — the outlet's ACTIVE ingredients, ordered by name. */
export function useIngredients(session: CompanySession) {
  return useQuery({
    queryKey: INGREDIENTS_KEY(session),
    queryFn: () =>
      apiFetch<Ingredient[]>('/api/v1/ingredients', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

export interface CreateIngredientInput {
  name: string
  unit: string
  /** Both-or-neither with `costCurrency` (server-enforced CHECK). */
  unitCostMinor?: number | null
  costCurrency?: string | null
  initialStockQty?: number
}

export function useCreateIngredient(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateIngredientInput) =>
      apiFetch<Ingredient>('/api/v1/ingredients', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          name: input.name,
          unit: input.unit,
          unitCostMinor: input.unitCostMinor ?? null,
          costCurrency: input.costCurrency ?? null,
          initialStockQty: input.initialStockQty ?? 0,
        },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}

export interface UpdateIngredientInput {
  id: string
  name?: string
  unit?: string
  unitCostMinor?: number | null
  costCurrency?: string | null
}

export function useUpdateIngredient(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...patch }: UpdateIngredientInput) =>
      apiFetch<Ingredient>(`/api/v1/ingredients/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body: patch,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}

/** DELETE /{id} — soft deactivate (recorded stocktake lines keep referencing the row). */
export function useDeactivateIngredient(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/api/v1/ingredients/${id}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}

/** PUT /{id}/stock — absolute set (the "Atur jumlah" action). */
export function useSetIngredientStock(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, quantity }: { id: string; quantity: number }) =>
      apiFetch<Ingredient>(`/api/v1/ingredients/${id}/stock`, {
        method: 'PUT',
        tenant: tenantOf(session),
        body: { quantity },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}

/** POST /{id}/stock/add — signed delta, floored at 0 server-side (the receive/purchase path). */
export function useAddIngredientStock(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, amount }: { id: string; amount: number }) =>
      apiFetch<Ingredient>(`/api/v1/ingredients/${id}/stock/add`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { amount },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}
