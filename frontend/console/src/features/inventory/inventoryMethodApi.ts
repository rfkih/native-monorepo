/**
 * inventoryMethodApi.ts — perpetual-inventory election & activation status (ADR 0067 §5, Phase
 * D4/D5). `POST /activate` is OWNER-ONLY at the gateway (narrower than the general FINANCE_ROLES
 * `GET` sits behind — mirrors QRIS payment-settings administration and the payroll bank file):
 * activation books money (a one-time opening `Dr 1100 / Cr 3900` entry) and is a once-per-company,
 * effectively irreversible election. Every call here uses the PERSONAL bearer (ADR 0049 P3b) —
 * identical to `features/settings/api.ts`'s `useSetPlanTier` and `features/payments/api.ts` —
 * never the outlet token.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/**
 * Mirror of finance-service `InventoryMethodStatusResponse` — the body of both `GET
 * /api/v1/inventory-method` and a successful `POST /activate`. `method`/`cutoverPeriod`/
 * `activatedAt` are `null` while inactive. `inventoryAssetMinor`/`inventoryAssetNegative` are
 * ALWAYS populated regardless of activation — account 1100 can already carry an ADR 0037 opening
 * balance before any perpetual-inventory election; `inventoryAssetNegative` is the D5
 * negative-inventory monitor signal the console surfaces as a warning badge.
 */
export interface InventoryMethodStatus {
  active: boolean
  method: string | null
  cutoverPeriod: string | null
  activatedAt: string | null
  inventoryAssetMinor: number
  inventoryAssetNegative: boolean
  currency: string | null
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

const INVENTORY_METHOD_KEY = (companyId: string) => ['inventoryMethod', companyId]

/** GET /api/v1/inventory-method — the bound company's election status + live 1100 GL balance. */
export function useInventoryMethod(session: CompanySession) {
  return useQuery({
    queryKey: INVENTORY_METHOD_KEY(session.companyId),
    queryFn: () =>
      apiFetch<InventoryMethodStatus>('/api/v1/inventory-method', { tenant: tenantOf(session) }),
  })
}

/** POST /api/v1/inventory-method/activate body. */
export interface ActivateInventoryMethodBody {
  /** `YYYY-MM` — the month perpetual accounting starts from. */
  cutoverPeriod: string
  /** The counted on-hand inventory value AS OF the cutover, integer minor units (rule 8; zero is
   *  valid — no opening journal is booked when it's zero, but the company still activates). */
  openingInventoryValueMinor: number
  /** ISO-4217 code the opening value is expressed in — always the company's BASE currency; there
   *  is no currency picker (rule: no currency toggle in the dashboard). */
  currency: string
}

/**
 * Mutation variables for {@link useActivateInventoryMethod}. `idempotencyKey` must be generated
 * ONCE per user submit (`crypto.randomUUID()` in the calling component's confirm handler) and
 * passed in here — NOT generated inside `mutationFn`, which re-runs on every TanStack Query retry
 * and would mint a fresh key per retry, defeating the backend's retry de-dupe. Mirrors
 * features/ap/api.ts's `useRecordPayment` / `RecordPaymentVariables` EXACTLY: the backend REQUIRES
 * the header (a keyless request is a 400) and replays a retry under the SAME key + payload rather
 * than double-activating / double-booking the opening entry.
 */
export interface ActivateInventoryMethodVariables extends ActivateInventoryMethodBody {
  idempotencyKey: string
}

/**
 * POST /api/v1/inventory-method/activate — OWNER-ONLY (gateway-enforced; this client call is UI
 * convenience only, not the authorization boundary). Once-only per company: a retry under the same
 * key + payload replays (200); a different key/payload against an already-active company is a 409
 * (`activationForm.ts`'s `activationErrorKey` maps it to a friendly message, alongside the 422
 * sealed-period refusal).
 */
export function useActivateInventoryMethod(session: CompanySession) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: ActivateInventoryMethodVariables) =>
      apiFetch<InventoryMethodStatus>('/api/v1/inventory-method/activate', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_METHOD_KEY(session.companyId) })
    },
  })
}
