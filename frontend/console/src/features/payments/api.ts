/**
 * QRIS payment settings — the console client for `/api/v1/payment-settings` (ADR 0045). Mirrors
 * `features/pos/api.ts`'s `session: CompanySession` + `tenantOf` idiom and `features/expenses/
 * api.ts`'s object-URL-with-revoke pattern (there: `useReceiptUrlAtPath`; here: {@link
 * useStaticQrImageUrl}, reimplemented locally rather than imported across features).
 *
 * A company has ONE QRIS mode (MANUAL/STATIC/GATEWAY) with an optional per-outlet MODE override
 * (never a per-outlet gateway credential — those are company-level only); an outlet may also
 * override just the STATIC image while inheriting the company's mode. `QrisMode`/
 * `EffectiveSettings` live in `./effectiveMode` (a fetch-free pure module) and are re-exported
 * here for convenience.
 *
 * Money/PII rules don't apply here (no monetary amounts, no PII) but rule 9 still does — this
 * file is data plumbing only, no user-facing strings.
 */
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, apiFetchBlob, apiUpload } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { EffectiveSettings, QrisMode } from './effectiveMode'

export type { EffectiveSettings, QrisMode } from './effectiveMode'

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

/** One row of the owner settings response — either the company default or an outlet override. */
export interface PaymentSettingsRow {
  id: string
  /** Null for the company default row; an org-unit (outlet) id for an override. */
  outletId: string | null
  mode: QrisMode
  hasStaticImage: boolean
  staticQrByteSize: number | null
  staticQrSha256: string | null
  /** Null on an outlet-override row (credentials are company-level only) and on any row that has
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
        body,
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** PUT /api/v1/payment-settings/outlets/{outletId} — OWNER only; mode-only (gateway fields 422). */
export function useUpsertOutletOverride(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ outletId, mode }: { outletId: string; mode: QrisMode }) =>
      apiFetch<OwnerPaymentSettingsResponse>(`/api/v1/payment-settings/outlets/${outletId}`, {
        method: 'PUT',
        tenant: tenantOf(session),
        body: { mode },
      }),
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** DELETE /api/v1/payment-settings/outlets/{outletId} — OWNER only; removes the mode override
 *  (the outlet falls back to inheriting the company default mode). */
export function useDeleteOutletOverride(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (outletId: string) =>
      apiFetch<null>(`/api/v1/payment-settings/outlets/${outletId}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
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

function staticQrUploadPath(outletId?: string) {
  return outletId
    ? `/api/v1/payment-settings/outlets/${outletId}/static-qr`
    : '/api/v1/payment-settings/static-qr'
}

/**
 * POST /api/v1/payment-settings/static-qr (or the outlet variant) — OWNER only, multipart `file`
 * (jpeg/png/webp, ≤ 2 MiB; the caller pre-checks size client-side to avoid a round trip for the
 * common case, but the server is the source of truth — 413/422 on a bad upload).
 */
export function useUploadStaticQr(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ file, outletId }: { file: File; outletId?: string }) => {
      const formData = new FormData()
      formData.append('file', file)
      return apiUpload<StaticQrMeta>(staticQrUploadPath(outletId), formData, {
        tenant: tenantOf(session),
      })
    },
    onSuccess: () => invalidatePaymentSettings(qc, session),
  })
}

/** DELETE /api/v1/payment-settings/static-qr (or the outlet variant) — OWNER only; 404 when none. */
export function useDeleteStaticQr(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (outletId?: string) =>
      apiFetch<null>(staticQrUploadPath(outletId), {
        method: 'DELETE',
        tenant: tenantOf(session),
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
 * /api/v1/payment-settings/static-qr/image, company-scoped when `businessId` is null/undefined,
 * outlet-scoped (falling back to the company image server-side) when set. Same object-URL/revoke
 * lifecycle as `features/expenses/api.ts`'s `useReceiptUrlAtPath` (reimplemented here rather than
 * imported across features — a Blob's object-URL lifetime needs an explicit revoke tied to THIS
 * component instance).
 *
 * `version` is a caller-bumped counter (e.g. incremented after a successful upload/delete) that
 * forces a re-fetch even though `businessId`/`enabled` did not change — the image byte content can
 * change behind the same URL. A 404 (no image on file) resolves to `'none'`, not `'error'`.
 */
export function useStaticQrImageUrl(
  session: CompanySession,
  businessId: string | null | undefined,
  enabled: boolean,
  version = 0,
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
          query: businessId ? { businessId } : undefined,
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
  }, [session.companyId, session.actor, businessId, enabled, version])

  // Not enabled → the idle/loading sentinel, same value the initial render already has — never
  // reset via setState-in-effect (mirrors features/expenses/api.ts's useReceiptUrlAtPath, which
  // returns its own IDLE constant directly rather than resetting state from inside the effect).
  return enabled ? state : STATIC_QR_LOADING
}

// ---------------------------------------------------------------------------
// Till surface — GET /api/v1/payment-settings/effective?businessId= (POS roles)
// ---------------------------------------------------------------------------

/**
 * GET /api/v1/payment-settings/effective?businessId=<uuid> — the till's read: the resolved mode
 * for this business/outlet (outlet override if set, else the company default), whether a static
 * image is actually available, and the gateway's connection state. Cached briefly (60s) since a
 * payment surface may re-render often within one checkout session; `options.enabled` lets the
 * caller gate the fetch (e.g. never fetch while offline — a digital mode is unreachable anyway).
 */
export function useQrisEffective(
  session: CompanySession,
  businessId: string,
  options: { enabled?: boolean } = {},
) {
  const enabled = (options.enabled ?? true) && !!businessId
  return useQuery({
    queryKey: ['payment-settings-effective', session.companyId, businessId],
    enabled,
    staleTime: 60_000,
    queryFn: () =>
      apiFetch<EffectiveSettings>('/api/v1/payment-settings/effective', {
        tenant: tenantOf(session),
        query: { businessId },
      }),
  })
}
