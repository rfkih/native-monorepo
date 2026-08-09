/**
 * QRIS payment settings — the console client for `/api/v1/payment-settings` (ADR 0045). Mirrors
 * `features/pos/api.ts`'s `session: CompanySession` + `tenantOf` idiom and `features/expenses/
 * api.ts`'s object-URL-with-revoke pattern (there: `useReceiptUrlAtPath`; here: {@link
 * useStaticQrImageUrl}, reimplemented locally rather than imported across features).
 *
 * A company has ONE QRIS mode (MANUAL/STATIC/GATEWAY) with an optional per-unit MODE override —
 * ADR 0045 amendment: a "unit" is a generic org-unit scope, either a division (business unit) or
 * an outlet, resolved outlet → division → company (never a per-unit gateway credential — those
 * are company-level only). A unit may also override just the STATIC image while inheriting its
 * resolved mode. `QrisMode`/`EffectiveSettings` live in `./effectiveMode` (a fetch-free pure
 * module) and are re-exported here for convenience.
 *
 * Money/PII rules don't apply here (no monetary amounts, no PII) but rule 9 still does — this
 * file is data plumbing only, no user-facing strings.
 */
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, apiFetchBlob, apiUpload } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { EffectiveSettings, QrisMode } from './effectiveMode'
import type { ChargeStatus } from './chargePhase'

export type { EffectiveSettings, QrisMode } from './effectiveMode'
export type { ChargeStatus } from './chargePhase'

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

// ---------------------------------------------------------------------------
// Owner surface — GET/PUT /api/v1/payment-settings (+ outlet overrides)
// ---------------------------------------------------------------------------

export type GatewayEnvironment = 'SANDBOX' | 'PRODUCTION'

/** The company-level gateway config block — credentials are company-level only (never per-outlet). */
export interface GatewaySettings {
  provider: 'MIDTRANS'
  environment: GatewayEnvironment
  /** Last 4 characters of the stored server key — never the key itself. Null when never configured. */
  serverKeyLast4: string | null
  connected: boolean
}

/** One row of the owner settings response — either the company default or a unit override. */
export interface PaymentSettingsRow {
  id: string
  /** Null for the company default row; otherwise a generic org-unit id — a division (business
   *  unit) or an outlet (ADR 0045 amendment; RENAMED from `outletId`). */
  unitId: string | null
  mode: QrisMode
  hasStaticImage: boolean
  staticQrByteSize: number | null
  staticQrSha256: string | null
  /** Null on a unit-override row (credentials are company-level only) and on any row that has
   * never been configured for GATEWAY. */
  gateway: GatewaySettings | null
}

/** GET/PUT /api/v1/payment-settings response shape. */
export interface OwnerPaymentSettingsResponse {
  companyDefault: PaymentSettingsRow | null
  outletOverrides: PaymentSettingsRow[]
}

function ownerSettingsKey(session: CompanySession) {
  return ['payment-settings', session.companyId]
}

/** GET /api/v1/payment-settings — OWNER only. The full company default + every outlet override. */
export function useOwnerPaymentSettings(session: CompanySession) {
  return useQuery({
    queryKey: ownerSettingsKey(session),
    queryFn: () =>
      apiFetch<OwnerPaymentSettingsResponse>('/api/v1/payment-settings', {
        tenant: tenantOf(session),
        auth: 'personal',
      }),
  })
}

/**
 * A change to the company default or any outlet override can shift what the till resolves for
 * ANY businessId — broad-invalidate every cached effective query for this company (the
 * `invalidateClaims` idiom, features/expenses/api.ts) rather than trying to know which
 * businessId(s) are affected.
 */
function invalidatePaymentSettings(qc: ReturnType<typeof useQueryClient>, session: CompanySession) {
  void qc.invalidateQueries({ queryKey: ownerSettingsKey(session) })
  void qc.invalidateQueries({ queryKey: ['payment-settings-effective', session.companyId] })
}

/** PUT /api/v1/payment-settings body — serverKey/clientKey are WRITE-ONLY (blank/absent keeps the
 *  stored value); omit them entirely for a mode-only write (the settings page's mode picker). */
export interface UpsertCompanySettingsBody {
  mode: QrisMode
  provider?: 'MIDTRANS'
  environment?: GatewayEnvironment
  serverKey?: string
  clientKey?: string
}

/** PUT /api/v1/payment-settings — OWNER only. */
export function useUpsertPaymentSettings(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: UpsertCompanySettingsBody) =>
      apiFetch<OwnerPaymentSettingsResponse>('/api/v1/payment-settings', {
        method: 'PUT',
        tenant: tenantOf(session),
        auth: 'personal',
        body,
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** PUT /api/v1/payment-settings/units/{unitId} — OWNER only; mode-only (gateway fields 422).
 *  `unitId` is a division or an outlet id (ADR 0045 amendment; RENAMED from `outlets/{outletId}`). */
export function useUpsertUnitOverride(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ unitId, mode }: { unitId: string; mode: QrisMode }) =>
      apiFetch<OwnerPaymentSettingsResponse>(`/api/v1/payment-settings/units/${unitId}`, {
        method: 'PUT',
        tenant: tenantOf(session),
        auth: 'personal',
        body: { mode },
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** DELETE /api/v1/payment-settings/units/{unitId} — OWNER only; removes the mode override (the
 *  unit falls back to inheriting its resolved parent — division default, else company default). */
export function useDeleteUnitOverride(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (unitId: string) =>
      apiFetch<null>(`/api/v1/payment-settings/units/${unitId}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
        auth: 'personal',
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

// ---------------------------------------------------------------------------
// Static QR image — upload/delete (owner) + a blob preview (owner AND the till)
// ---------------------------------------------------------------------------

/** POST .../static-qr response — metadata only, never the bytes (mirrors the receipt-upload idiom). */
export interface StaticQrMeta {
  contentType: string
  byteSize: number
  sha256: string
}

function staticQrUploadPath(unitId?: string) {
  return unitId
    ? `/api/v1/payment-settings/units/${unitId}/static-qr`
    : '/api/v1/payment-settings/static-qr'
}

/**
 * POST /api/v1/payment-settings/static-qr (or the unit variant) — OWNER only, multipart `file`
 * (jpeg/png/webp, ≤ 2 MiB; the caller pre-checks size client-side to avoid a round trip for the
 * common case, but the server is the source of truth — 413/422 on a bad upload). `unitId` is a
 * division or an outlet id (ADR 0045 amendment; RENAMED from `outletId`).
 */
export function useUploadStaticQr(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ file, unitId }: { file: File; unitId?: string }) => {
      const formData = new FormData()
      formData.append('file', file)
      return apiUpload<StaticQrMeta>(staticQrUploadPath(unitId), formData, {
        tenant: tenantOf(session),
        auth: 'personal',
      })
    },
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** DELETE /api/v1/payment-settings/static-qr (or the unit variant) — OWNER only; 404 when none. */
export function useDeleteStaticQr(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (unitId?: string) =>
      apiFetch<null>(staticQrUploadPath(unitId), {
        method: 'DELETE',
        tenant: tenantOf(session),
        auth: 'personal',
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

export type StaticQrPreviewStatus = 'loading' | 'ready' | 'none' | 'error'

export interface StaticQrPreviewState {
  url: string | null
  status: StaticQrPreviewStatus
}

const STATIC_QR_LOADING: StaticQrPreviewState = { url: null, status: 'loading' }

/**
 * Fetches the static QRIS image as an object URL for an `<img>` preview — GET
 * /api/v1/payment-settings/static-qr/image, company-scoped when `businessId`/`divisionId` are
 * both null/undefined, otherwise resolved outlet → division → company server-side (ADR 0045
 * amendment). Same object-URL/revoke lifecycle as `features/expenses/api.ts`'s
 * `useReceiptUrlAtPath` (reimplemented here rather than imported across features — a Blob's
 * object-URL lifetime needs an explicit revoke tied to THIS component instance).
 *
 * `version` is a caller-bumped counter (e.g. incremented after a successful upload/delete) that
 * forces a re-fetch even though `businessId`/`divisionId`/`enabled` did not change — the image
 * byte content can change behind the same URL. A 404 (no image on file) resolves to `'none'`, not
 * `'error'`.
 */
export function useStaticQrImageUrl(
  session: CompanySession,
  businessId: string | null | undefined,
  enabled: boolean,
  version = 0,
  /** ADR 0045 amendment: the outlet's parent business-unit id, when known — omitted/undefined on
   *  a company-scoped preview or when no division context is available. */
  divisionId?: string | null,
): StaticQrPreviewState {
  const [state, setState] = useState<StaticQrPreviewState>(STATIC_QR_LOADING)

  useEffect(() => {
    if (!enabled) return undefined

    let objectUrl: string | null = null
    let cancelled = false

    void (async () => {
      setState({ url: null, status: 'loading' })
      try {
        const blob = await apiFetchBlob('/api/v1/payment-settings/static-qr/image', {
          tenant: tenantOf(session),
          query: {
            businessId: businessId ?? undefined,
            divisionId: divisionId ?? undefined,
          },
        })
        if (cancelled) return
        if (!blob) {
          setState({ url: null, status: 'none' })
          return
        }
        objectUrl = URL.createObjectURL(blob)
        setState({ url: objectUrl, status: 'ready' })
      } catch (err) {
        if (cancelled) return
        setState({ url: null, status: err instanceof ApiError && err.status === 404 ? 'none' : 'error' })
      }
    })()

    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
    // Keyed on session.companyId/session.actor (not the session object) — the useReceiptUrlAtPath idiom.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.companyId, session.actor, businessId, divisionId, enabled, version])

  // Not enabled → the idle/loading sentinel, same value the initial render already has — never
  // reset via setState-in-effect (mirrors features/expenses/api.ts's useReceiptUrlAtPath, which
  // returns its own IDLE constant directly rather than resetting state from inside the effect).
  return enabled ? state : STATIC_QR_LOADING
}

// ---------------------------------------------------------------------------
// Till surface — GET /api/v1/payment-settings/effective?businessId= (POS roles)
// ---------------------------------------------------------------------------

/**
 * GET /api/v1/payment-settings/effective?businessId=<uuid>&divisionId=<uuid> — the till's read:
 * the resolved mode for this outlet (ADR 0045 amendment: per-facet resolution outlet → division →
 * company — mode and `staticQrAvailable` walk the chain; `gateway` comes from the company only),
 * whether a static image is actually available, and the gateway's connection state. Cached briefly
 * (60s) since a payment surface may re-render often within one checkout session; `options.enabled`
 * lets the caller gate the fetch (e.g. never fetch while offline — a digital mode is unreachable
 * anyway); `options.divisionId` is the outlet's parent business-unit id, when known — omitted
 * degrades to outlet → company resolution exactly as before this amendment.
 */
export function useQrisEffective(
  session: CompanySession,
  businessId: string,
  options: { enabled?: boolean; divisionId?: string | null } = {},
) {
  const enabled = (options.enabled ?? true) && !!businessId
  const divisionId = options.divisionId ?? undefined
  return useQuery({
    queryKey: ['payment-settings-effective', session.companyId, businessId, divisionId ?? null],
    enabled,
    staleTime: 60_000,
    queryFn: () =>
      apiFetch<EffectiveSettings>('/api/v1/payment-settings/effective', {
        tenant: tenantOf(session),
        query: { businessId, divisionId },
      }),
  })
}

// ---------------------------------------------------------------------------
// Till surface — dynamic-QRIS charges, POS roles (ADR 0045; payment-service /api/v1/payment-charges)
//
// Deliberately PLAIN async fetchers, not useQuery/useMutation hooks: the caller is
// `useGatewayQris.ts`, which already owns its own lifecycle state (the `chargePhase.ts` reducer,
// a 1s expiry ticker) and drives these imperatively (auto-create on a paymentId transition, a
// user-initiated retry/new-QR/cancel/check-status tap) rather than on React Query's own
// render-driven refetch model. Conventions mirror the rest of this file: `tenantOf(session)`,
// `apiFetch<T>(path, { method, tenant, body })`, explicit `?? null` for optional wire fields.
// ---------------------------------------------------------------------------

/** Mirrors payment-service's `ChargeResponse` record verbatim — the till's view of a charge
 *  (create/poll/sync/cancel all return this same shape). Never any credential material. */
export interface ChargeResponse {
  chargeId: string
  status: ChargeStatus
  vertical: string
  paymentId: string
  qrString: string
  qrUrl: string
  /** ISO instant. */
  expiresAt: string
  amountMinor: number
  currency: string
}

export interface CreateChargeInput {
  /**
   * `charge:<paymentId>:<attempt>` — minted by `useGatewayQris.ts`, attempt starting at 1 and
   * incrementing on every retry/new-QR tap after a terminal charge (a fresh attempt is a fresh
   * Idempotency-Key, so it mints a NEW charge rather than replaying the expired/failed/canceled
   * one — see `PaymentChargeController`/`ChargeService`).
   */
  idempotencyKey: string
  vertical: 'restaurant' | 'carwash' | 'barbershop'
  /** The vertical's own PENDING payment id this charge settles. */
  paymentId: string
  /** The ticket id for carwash/barbershop; omitted (→ null on the wire) for restaurant. */
  referenceId?: string | null
  businessId: string
  /** ADR 0045 amendment: the outlet's parent business-unit id (→ null on the wire when absent) —
   *  advisory only, used for the GATEWAY mode gate; the charge itself is always company-scoped. */
  divisionId?: string | null
  /** The tender RESIDUAL the vertical checkout returned — integer minor units (rule 8). */
  amountMinor: number
  currency: string
}

/**
 * POST /api/v1/payment-charges — 201 fresh / 200 replay (same live charge for the same payment
 * regardless of which key asked); 409 on a payload mismatch for an already-used key, a non-GATEWAY
 * effective mode, or no gateway credentials; 422 for a non-IDR currency; 502 (charge left FAILED)
 * if the PSP itself is unreachable.
 */
export function createCharge(
  session: CompanySession,
  input: CreateChargeInput,
): Promise<ChargeResponse | null> {
  const { idempotencyKey, referenceId, divisionId, ...rest } = input
  return apiFetch<ChargeResponse>('/api/v1/payment-charges', {
    method: 'POST',
    tenant: tenantOf(session),
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { ...rest, referenceId: referenceId ?? null, divisionId: divisionId ?? null },
  })
}

/** GET /api/v1/payment-charges/{chargeId} — the poll view; also lazily expires a stale QR
 *  server-side. Exported for completeness with the documented endpoint surface; `useGatewayQris`
 *  itself relies on the client-side countdown ticker + the create/sync/cancel responses rather than
 *  polling this endpoint on an interval — a future caller (e.g. a reattach-after-reload flow) can
 *  use this directly. */
export function getCharge(session: CompanySession, chargeId: string): Promise<ChargeResponse | null> {
  return apiFetch<ChargeResponse>(`/api/v1/payment-charges/${chargeId}`, {
    tenant: tenantOf(session),
  })
}

/** POST /api/v1/payment-charges/{chargeId}/sync — the manual "customer says they paid" status
 *  probe (a server-side Midtrans status poll); the till's "check status" affordance. */
export function syncCharge(session: CompanySession, chargeId: string): Promise<ChargeResponse | null> {
  return apiFetch<ChargeResponse>(`/api/v1/payment-charges/${chargeId}/sync`, {
    method: 'POST',
    tenant: tenantOf(session),
  })
}

/** POST /api/v1/payment-charges/{chargeId}/cancel — cancels at Midtrans; if the customer ALREADY
 *  paid, this SETTLES instead (the response comes back SUCCEEDED) — the caller must treat that as
 *  a capture-in-flight, never a clean cancel (see `useGatewayQris.cancel()`'s doc). */
export function cancelCharge(session: CompanySession, chargeId: string): Promise<ChargeResponse | null> {
  return apiFetch<ChargeResponse>(`/api/v1/payment-charges/${chargeId}/cancel`, {
    method: 'POST',
    tenant: tenantOf(session),
  })
}
