/**
 * Opening balances & business migration (ADR 0037) — typed client for finance-service's
 * `/api/v1/opening-balances` (the once-only opening balance sheet) and `/api/v1/assets/opening`
 * (registering a pre-owned fixed asset brought into Native — no cash posted). Dashboard-facing
 * (owner/manager only, gateway DASHBOARD_ROLES) — mirrors features/assets/api.ts's shape (a
 * `{ companyId, actor }` tenant, `Idempotency-Key`-bearing mutation variables).
 *
 * Money rule 8: every amount on the wire is an integer MINOR unit + an ISO-4217 currency, never a
 * float.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { AssetDetail } from '@/features/assets/api'

export type OpeningBalanceSide = 'DEBIT' | 'CREDIT'

/** Mirrors backend `OpeningBalanceLine` exactly. */
export interface OpeningBalanceLine {
  accountCode: string
  amountMinor: number
  side: OpeningBalanceSide
}

/** Mirrors backend `OpeningBalanceResponse` exactly. */
export interface OpeningBalanceResponse {
  id: string
  openingEntryId: string
  asOfDate: string
  period: string
  totalMinor: number
  plugMinor: number
  currency: string
  recordedAt: string
}

/** POST /api/v1/opening-balances body. */
export interface RecordOpeningBalancesBody {
  asOfDate: string
  currency: string
  lines: OpeningBalanceLine[]
}

/**
 * Mutation variables for {@link useRecordOpeningBalances}. `idempotencyKey` must be minted ONCE per
 * user submit and REUSED across a retry of that SAME submit (a per-submit UUID held in component
 * state — see OpeningBalances.tsx) — the AcquireAssetVariables idiom (features/assets/api.ts). NOT
 * generated inside `mutationFn`, which re-runs on TanStack retries and would mint a fresh key each
 * time, defeating the backend's replay de-dupe.
 */
export interface RecordOpeningBalancesVariables extends RecordOpeningBalancesBody {
  idempotencyKey: string
}

/** POST /api/v1/assets/opening body — registers a pre-owned fixed asset (no cash posted). */
export interface RegisterBroughtForwardAssetBody {
  name: string
  asOfDate: string
  costMinor: number
  salvageMinor: number
  openingAccumulatedMinor: number
  remainingLifeMonths: number
  currency: string
}

/** See {@link RecordOpeningBalancesVariables} — one STABLE key per fixed-asset row. */
export interface RegisterBroughtForwardAssetVariables extends RegisterBroughtForwardAssetBody {
  idempotencyKey: string
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/** Exported so a caller that needs to force a refetch (e.g. the already-recorded race below) uses
 *  the EXACT same key shape as {@link useOpeningBalance} — never a hand-duplicated literal. */
export function openingBalanceKey(companyId: string) {
  return ['openingBalance', companyId]
}

/** The statements move the instant either POST lands — invalidate all three (the assets/api.ts
 *  `invalidateAll` idiom). */
function invalidateStatements(queryClient: ReturnType<typeof useQueryClient>, companyId: string) {
  void queryClient.invalidateQueries({ queryKey: ['balanceSheet', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['incomeStatement', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['cashFlow', companyId] })
}

/**
 * True when a GET /api/v1/opening-balances 404'd because nothing has been recorded YET. The
 * backend returns a bare 404 with no problem+json body for this case
 * (`ResponseEntity.of(Optional.empty())`), so this is a plain status check — not a problem-type
 * match.
 */
export function isOpeningBalanceNotRecorded(err: unknown): boolean {
  return err instanceof ApiError && err.status === 404
}

/**
 * True for the 409 `opening-balances-already-recorded` conflict — a RACE where another request (a
 * second tab, a teammate) recorded the sheet between this page's GET and its own submit. The caller
 * (OpeningBalances.tsx) refetches on this instead of showing a dead-end error, so the user lands on
 * the read-only "already recorded" state rather than a form that can never succeed.
 */
export function isOpeningBalanceAlreadyRecorded(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('opening-balances-already-recorded')
  )
}

/**
 * GET /api/v1/opening-balances — the recorded opening balance sheet, or a 404 (see
 * {@link isOpeningBalanceNotRecorded}) when this company has not recorded one yet. `retry: false`
 * so a genuine "not recorded" 404 settles immediately instead of retrying a state that will never
 * become 200 (the features/me/api.ts `useMyProfile` idiom).
 */
export function useOpeningBalance(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: openingBalanceKey(companyId),
    queryFn: () =>
      apiFetch<OpeningBalanceResponse>('/api/v1/opening-balances', {
        tenant: { companyId, actor },
      }),
  })
}

/**
 * POST /api/v1/opening-balances — record the opening balance sheet (once per company; the backend
 * REQUIRES the Idempotency-Key header — a keyless request is a 400). A fresh recording → 201; a
 * same-key replay → 200 with the original. Invalidates the opening-balance read and every
 * statement (the entry moves the balance sheet immediately).
 */
export function useRecordOpeningBalances(session: CompanySession) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RecordOpeningBalancesVariables) =>
      apiFetch<OpeningBalanceResponse>('/api/v1/opening-balances', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: openingBalanceKey(session.companyId) })
      invalidateStatements(queryClient, session.companyId)
    },
  })
}

/**
 * POST /api/v1/assets/opening — register a pre-owned fixed asset brought into the business (Dr
 * asset cost / Cr accumulated depreciation / Cr opening balance equity — NO cash posted). Same
 * Idempotency-Key contract as {@link useRecordOpeningBalances}. Invalidates the asset register and
 * every statement.
 */
export function useRegisterBroughtForwardAsset(session: CompanySession) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RegisterBroughtForwardAssetVariables) =>
      apiFetch<AssetDetail>('/api/v1/assets/opening', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['assets', session.companyId] })
      invalidateStatements(queryClient, session.companyId)
    },
  })
}
