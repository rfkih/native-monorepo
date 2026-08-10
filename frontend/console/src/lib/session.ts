import { createContext, useContext } from 'react'
import type { PlanTier } from '@/lib/featureTier'

/**
 * The company the console is currently acting as, plus the login's full company list (multi-company
 * ownership, ADR 0021 — one login can hold 1..N companies; the switcher changes the ACTIVE one).
 *
 * - **oidc mode**: identity drives the tenant. The login's companies come from
 *   `GET /api/v1/companies/mine` (backed by the verified token's `company_id` claim set); the
 *   active company is a per-login localStorage pointer validated against that list, and every API
 *   call sends it as `X-Company-Id` — which the gateway and each service validate against the
 *   token before binding. localStorage is NOT trusted for identity, only for the (validated)
 *   selection.
 * - **dev mode**: companies accumulate at onboarding and persist to localStorage (a list + an
 *   active pointer) so a refresh keeps them — the multi-company extension of the original single
 *   dev session.
 *
 * Base currency + default language are fixed at company creation (org-service) — the console reads
 * them, it never offers to change them.
 */
export interface CompanySession {
  companyId: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  /** The company's first business (org unit) — the POS records sales/orders against it. */
  businessId: string
  /**
   * ADR 0045 amendment (division-layer QRIS resolution): `businessId`'s parent business-unit id,
   * when known. Null on the base (pre-`OutletGate`) session and whenever the resolved outlet's
   * division is unknown (e.g. an older server that omits `divisionId` on `/api/v1/outlets`) —
   * every payments hook treats `undefined`/`null` as "no division context" and resolves
   * outlet → company exactly as before this amendment. Set by `OutletGate` from the SAME
   * resolved-outlet list `businessId` itself comes from.
   */
  divisionId?: string | null
  actor: string
  /**
   * The company's plan tier (P1 tier-mode, `~/.claude/plans/umkm-tier-mode.md`) — FREE shows a
   * lean UMKM console until an owner flips "show extended features"; FULL is the historical full
   * back-office. Always resolved by `toSession` (SessionProvider.tsx) via `toPlanTier`: an
   * absent/unrecognized server value fails OPEN to FULL so a read gap can never HIDE a feature
   * (see lib/featureTier.ts, plan Risk 5).
   */
  planTier: PlanTier
  /**
   * The company's immutable 6-char login-namespace code (ADR 0054). Shown read-only so an owner can
   * tell staff how to sign in — an invited employee's Keycloak username is `<companyCode>.<local>`.
   * Empty string only for a stale server that predates the column (`toSession` defaults it).
   */
  companyCode: string
}

export interface SessionContextValue {
  company: CompanySession | null
  /** Every company this login can act as — the switcher list (active included). */
  companies: CompanySession[]
  /** True while the signed-in user's companies are still being loaded (oidc mode). */
  loading: boolean
  setCompany: (company: CompanySession | null) => void
  /** Switches the active company (an id from `companies`); every query re-fetches under it. */
  setActiveCompany: (companyId: string) => void
  /**
   * The outlet the POS terminal is currently ringing for (Phase 3 outlet-scoping).
   * Null when no outlet has been selected yet, or when the tenant has no OUTLET org units.
   * Persisted per-TAB in sessionStorage under `native.console.outlet`.
   */
  activeOutletId: string | null
  /** Set the active outlet and persist it to sessionStorage for this tab. */
  setActiveOutlet: (id: string | null) => void
}

/** Internal — read through `useSession()`; exported only for SessionProvider.tsx to fill. */
export const SessionContext = createContext<SessionContextValue | null>(null)

/* The provider itself lives in SessionProvider.tsx (a component-only file for react-refresh);
 * this module stays the stable `@/lib/session` surface every consumer imports. */

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
