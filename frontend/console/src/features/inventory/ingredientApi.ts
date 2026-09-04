/**
 * ingredientApi.ts — ingredient inventory (ADR 0046 phase 1). The per-outlet catalog of raw
 * materials (bahan: bread, patty, sauce…) that the stock opname counts — a SEPARATE concept
 * from per-menu-item stock, which stays the sellable-portion/86 gate.
 *
 * Quantities are INTEGERS in the ingredient's BASE `unit` (g/ml/pcs/pack). A weight/volume item may
 * carry a `displayUnit` (kg over a base of g, liter over ml) so the console SHOWS and accepts it in
 * kg/litres with a decimal while storage stays whole integers — see `lib/units.ts` for the ×1000
 * conversion. `displayUnit` is null when the shown unit IS the base unit.
 *
 * Money rule (rule 8): `unitCostMinor` is integer minor units with its own `costCurrency`
 * (an ingredient has no price to ride on) — both null together = counted but never valued.
 * Strings rule (rule 9): no user-facing strings here.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** Units offered by the console picker. kg/liter are DISPLAY units over a smaller integer base
 *  (g/ml) — the console converts ×1000 so a weight can be entered as 1.5 kg yet stored whole (see
 *  `lib/units.ts`). g/ml/pcs/pack are base units shown as-is. */
export const INGREDIENT_UNITS = ['g', 'kg', 'ml', 'liter', 'pcs', 'pack'] as const
export type IngredientUnit = (typeof INGREDIENT_UNITS)[number]

/** The picker groups units by kind (weight / volume / count) for a clearer, less crowded UI. */
export const INGREDIENT_UNIT_GROUPS: { key: 'weight' | 'volume' | 'count'; units: IngredientUnit[] }[] = [
  { key: 'weight', units: ['g', 'kg'] },
  { key: 'volume', units: ['ml', 'liter'] },
  { key: 'count', units: ['pcs', 'pack'] },
]

export interface Ingredient {
  id: string
  businessId: string
  name: string
  /** The BASE unit stock is stored/counted in (g/ml/pcs/pack). */
  unit: string
  /** The unit SHOWN to the user when it sits above the base (kg over g, liter over ml); null = base. */
  displayUnit: string | null
  /** On-hand stock in the BASE `unit` — a whole integer (convert with `lib/units.ts` to show kg). */
  stockQty: number
  unitCostMinor: number | null
  costCurrency: string | null
  /** Total value of stock on hand, minor units of `costCurrency` (moving-average cost). */
  stockValueMinor: number
  active: boolean
  /**
   * V46 — how many BASE units one PACK usually holds (a tortilla pack of 20 on a `pcs` item: 20; a
   * 25 kg flour sack on a `g`-based item: 25000), or `null` for no remembered default. A REMEMBERED
   * DEFAULT only — the purchase forms pre-fill a line's "Isi per kemasan" from it (converted to the
   * SHOWN unit, `lib/units.ts`'s `toDisplayQty`) but the line stays fully editable and never writes
   * back here (brands differ purchase to purchase).
   */
  packSize: number | null
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

/** One ingredient's quantity consumed by sales on the requested day ("terpakai", V42). */
export interface IngredientUsage {
  ingredientId: string
  qtyUsed: number
}

/**
 * The outlet-local calendar-day key (YYYY-MM-DD) usage is attributed to — Asia/Jakarta, mirroring
 * the SERVER's attribution zone (IngredientDepletionWriter / the register business-date
 * convention). Never the device zone: a device in another zone would query the wrong bucket.
 */
export function usageDayKey(at: Date = new Date()): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Jakarta',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(at)
}

/**
 * GET /api/v1/ingredients/usage?businessId&date — per-ingredient "terpakai" for one outlet-local
 * day. Ingredients with no sales that day are ABSENT (treat absence as 0). Usage accrues from the
 * feature's deployment forward — earlier days read empty, not zero-filled.
 */
export function useIngredientUsage(session: CompanySession, dateKey: string, enabled: boolean) {
  return useQuery({
    enabled,
    queryKey: ['ingredient-usage', session.companyId, session.businessId, dateKey],
    staleTime: 30_000,
    queryFn: () =>
      apiFetch<IngredientUsage[]>('/api/v1/ingredients/usage', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId, date: dateKey },
      }),
  })
}

export interface CreateIngredientInput {
  name: string
  /** BASE unit (g/ml/pcs/pack). */
  unit: string
  /** Display label (kg/liter) when it sits above the base, else null — see `lib/units.ts`. */
  displayUnit?: string | null
  /** Both-or-neither with `costCurrency` (server-enforced CHECK). */
  unitCostMinor?: number | null
  costCurrency?: string | null
  /** Initial stock in the BASE `unit` (already converted from any display value). */
  initialStockQty?: number
  /** V46 — the remembered pack-size default, in BASE units (already converted from the SHOWN-unit
   *  form input — see `Ingredient.packSize`'s doc). Omitted/`null` = no default. */
  packSize?: number | null
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
          displayUnit: input.displayUnit ?? null,
          unitCostMinor: input.unitCostMinor ?? null,
          costCurrency: input.costCurrency ?? null,
          initialStockQty: input.initialStockQty ?? 0,
          packSize: input.packSize ?? null,
        },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}

export interface UpdateIngredientInput {
  id: string
  name?: string
  unit?: string
  displayUnit?: string | null
  unitCostMinor?: number | null
  costCurrency?: string | null
  /** V46 — set a new pack-size default (BASE units). Omit (undefined) to leave the stored default
   *  alone; to REMOVE an existing default, omit this and send `clearPackSize: true` instead (both
   *  present is nonsensical and never constructed by this console — see `IngredientFormDialog`). */
  packSize?: number
  /** V46 — `true` removes the stored pack-size default; omitted/`false`-not-sent leaves it alone
   *  (the server reads a MISSING field as "not sent", never as `false`). */
  clearPackSize?: boolean
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

export interface AddIngredientStockInput {
  id: string
  amount: number
  /** Total paid for THIS receipt (minor units, `costCurrency`) — moving-average cost input.
   * Sent together with `costCurrency`, only on a positive receive with a price entered. */
  amountPaidMinor?: number
  costCurrency?: string
  /**
   * ADR 0067 Phase D1 — a stable UUID minted ONCE per user submit (`crypto.randomUUID()` in the
   * calling component's submit handler, NOT inside `mutationFn`, which re-runs on every TanStack
   * Query retry and would mint a fresh key per retry) — mirrors `features/ap/api.ts`'s
   * `useRecordPayment` idiom exactly. Sent as the `Idempotency-Key` header ONLY on a priced receive
   * (`amountPaidMinor` present): the backend dedupes a retried priced receive by this key
   * (`goods_receipt.idempotency_key`) so it never double-adds the value / double-posts `Dr 1100`.
   * The header is optional server-side (backward compatible) and ignored on a costless call.
   */
  idempotencyKey?: string
}

/** POST /{id}/stock/add — signed delta, floored at 0 server-side (the receive/purchase path). */
export function useAddIngredientStock(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      id,
      amount,
      amountPaidMinor,
      costCurrency,
      idempotencyKey,
    }: AddIngredientStockInput) =>
      apiFetch<Ingredient>(`/api/v1/ingredients/${id}/stock/add`, {
        method: 'POST',
        tenant: tenantOf(session),
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
        body:
          amountPaidMinor != null && costCurrency != null
            ? { amount, amountPaidMinor, costCurrency }
            : { amount },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) }),
  })
}
