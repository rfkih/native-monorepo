import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/assets/**`, `/api/v1/deferrals/**`), so it always uses the PERSONAL bearer (the
 * elevation token on a device terminal; identical to the single login token for a normal `user`
 * login). Shadows the shared `apiFetch` import so every call site below is correct with ZERO
 * per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/**
 * Fixed assets & deferrals — typed client for finance-service's /api/v1/assets and
 * /api/v1/deferrals endpoints (via the gateway, owner/manager only). Acquiring an asset posts the
 * capex entry; creating a deferral posts its opening entry; the monthly amortization run posts every
 * due depreciation/amortization for a period, idempotently (a re-run is a 200 no-op). Money on the
 * wire is integer minor units + ISO-4217 (rule 8). ILLUSTRATIVE / SME-gated (straight-line only,
 * month-after-acquisition start, COA codes) — see ADR 0020.
 */

export interface Asset {
  id: string
  name: string
  acquisitionDate: string
  startPeriod: string
  costMinor: number
  salvageMinor: number
  usefulLifeMonths: number
  currency: string
  status: string
  /** Null until the asset is DISPOSED (ADR 0022). */
  disposalDate: string | null
  /** Null until the asset is DISPOSED (ADR 0022). */
  proceedsMinor: number | null
  accumulatedMinor: number
  bookValueMinor: number
}

export interface ScheduleLine {
  period: string
  monthIndex: number
  amountMinor: number
  currency: string
}

export interface AssetDetail {
  asset: Asset
  schedule: ScheduleLine[]
}

export type DeferralKind = 'PREPAID_EXPENSE' | 'DEFERRED_REVENUE'

export interface Deferral {
  id: string
  kind: DeferralKind
  description: string
  totalMinor: number
  months: number
  startPeriod: string
  currency: string
  amortizedMinor: number
  remainingMinor: number
}

export interface AmortizationRun {
  id: string
  period: string
  lineCount: number
  totalMinor: number
  runAt: string
}

export interface AcquireAssetBody {
  name: string
  acquisitionDate: string
  costMinor: number
  salvageMinor: number
  usefulLifeMonths: number
  currency: string
}

export interface CreateDeferralBody {
  kind: DeferralKind
  description: string
  totalMinor: number
  months: number
  startPeriod: string
  currency: string
}

/** GET /api/v1/assets — the asset register. */
export function useAssets(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['assets', companyId],
    queryFn: async () => {
      const result = await apiFetch<Asset[]>('/api/v1/assets', { tenant: { companyId, actor } })
      return result ?? []
    },
  })
}

/** GET /api/v1/assets/runs — the amortization-run history. */
export function useRuns(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['assetRuns', companyId],
    queryFn: async () => {
      const result = await apiFetch<AmortizationRun[]>('/api/v1/assets/runs', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/deferrals — the deferral list. */
export function useDeferrals(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['deferrals', companyId],
    queryFn: async () => {
      const result = await apiFetch<Deferral[]>('/api/v1/deferrals', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

function invalidateAll(queryClient: ReturnType<typeof useQueryClient>, companyId: string) {
  void queryClient.invalidateQueries({ queryKey: ['assets', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['assetRuns', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['deferrals', companyId] })
  // The statements move with every posting — keep them fresh.
  void queryClient.invalidateQueries({ queryKey: ['incomeStatement', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['balanceSheet', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['cashFlow', companyId] })
}

/**
 * Mutation variables carrying an `idempotencyKey`. The key must be generated ONCE per user submit
 * (`crypto.randomUUID()` in the submit handler) and passed here — NOT minted inside `mutationFn`,
 * which re-runs on TanStack retries and would defeat the backend's retry de-dupe. Acquire/create
 * post money, so the backend REQUIRES the header (keyless → 400) and replays the original on a
 * retried key (→ 200, nothing re-posted).
 */
export interface AcquireAssetVariables extends AcquireAssetBody {
  idempotencyKey: string
}

/** See {@link AcquireAssetVariables}. */
export interface CreateDeferralVariables extends CreateDeferralBody {
  idempotencyKey: string
}

/** See {@link AcquireAssetVariables}. */
export interface DisposeAssetVariables {
  assetId: string
  disposalDate: string
  proceedsMinor: number
  currency: string
  idempotencyKey: string
}

/** POST /api/v1/assets — acquire (capitalize) an asset (requires an Idempotency-Key). */
export function useAcquireAsset(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: AcquireAssetVariables) =>
      apiFetch<AssetDetail>('/api/v1/assets', {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateAll(queryClient, companyId),
  })
}

/** POST /api/v1/deferrals — create a deferral (requires an Idempotency-Key). */
export function useCreateDeferral(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: CreateDeferralVariables) =>
      apiFetch<Deferral[]>('/api/v1/deferrals', {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateAll(queryClient, companyId),
  })
}

/**
 * POST /api/v1/assets/{id}/dispose — sell/scrap an asset (requires an Idempotency-Key; ADR 0022).
 * Derecognizes cost + accumulated depreciation and posts the gain/loss on disposal; proceeds of 0
 * = write-off. One-shot: an already-disposed asset returns 409.
 */
export function useDisposeAsset(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ assetId, idempotencyKey, ...body }: DisposeAssetVariables) =>
      apiFetch<AssetDetail>(`/api/v1/assets/${assetId}/dispose`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateAll(queryClient, companyId),
  })
}

/** POST /api/v1/assets/runs — run depreciation/amortization for a month (idempotent). */
export function useRunAmortization(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (period: string) =>
      apiFetch<AmortizationRun>('/api/v1/assets/runs', {
        method: 'POST',
        tenant: { companyId, actor },
        body: { period },
      }),
    onSuccess: () => invalidateAll(queryClient, companyId),
  })
}
