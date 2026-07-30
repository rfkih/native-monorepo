import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { AUTH_MODE } from '@/lib/config'
import { useAuth } from '@/lib/authContext'

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
  actor: string
}

/** Shape returned by org-service for the create / current / mine endpoints. */
interface CompanyDto {
  id: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  firstBusinessId: string
}

interface SessionContextValue {
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

const SessionContext = createContext<SessionContextValue | null>(null)
const LEGACY_STORAGE_KEY = 'native.console.session'
const SESSIONS_KEY = 'native.console.sessions'
const ACTIVE_KEY = 'native.console.activeCompany'
const OUTLET_SESSION_KEY = 'native.console.outlet'

/** Dev sessions: the list of onboarded companies (migrates the legacy single-session key). */
function loadDevSessions(): CompanySession[] {
  try {
    const raw = localStorage.getItem(SESSIONS_KEY)
    if (raw) return JSON.parse(raw) as CompanySession[]
    const legacy = localStorage.getItem(LEGACY_STORAGE_KEY)
    if (legacy) {
      const single = JSON.parse(legacy) as CompanySession
      return [single]
    }
    return []
  } catch {
    return []
  }
}

function saveDevSessions(sessions: CompanySession[]): void {
  try {
    if (sessions.length > 0) localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions))
    else localStorage.removeItem(SESSIONS_KEY)
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  } catch {
    /* storage unavailable — in-memory only */
  }
}

function loadActiveCompanyId(scope: string): string | null {
  try {
    return localStorage.getItem(`${ACTIVE_KEY}.${scope}`)
  } catch {
    return null
  }
}

function saveActiveCompanyId(scope: string, companyId: string | null): void {
  try {
    if (companyId) localStorage.setItem(`${ACTIVE_KEY}.${scope}`, companyId)
    else localStorage.removeItem(`${ACTIVE_KEY}.${scope}`)
  } catch {
    /* storage unavailable — in-memory only */
  }
}

function loadOutletFromSessionStorage(): string | null {
  try {
    return sessionStorage.getItem(OUTLET_SESSION_KEY)
  } catch {
    return null
  }
}

function saveOutletToSessionStorage(id: string | null): void {
  try {
    if (id) {
      sessionStorage.setItem(OUTLET_SESSION_KEY, id)
    } else {
      sessionStorage.removeItem(OUTLET_SESSION_KEY)
    }
  } catch {
    /* sessionStorage unavailable — in-memory only */
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const auth = useAuth()

  // Dev: the accumulated onboarded companies. Oidc: `manual` holds a just-onboarded company (it
  // wins over the API list until the token refresh + /mine catch up).
  const [devSessions, setDevSessions] = useState<CompanySession[]>(() =>
    AUTH_MODE === 'dev' ? loadDevSessions() : [],
  )
  const [manual, setManual] = useState<CompanySession | null>(null)

  // The active-company pointer, persisted per login (oidc: per sub; dev: one scope).
  const activeScope = AUTH_MODE === 'oidc' ? (auth.sub ?? 'anonymous') : 'dev'
  const [activeCompanyId, setActiveCompanyId] = useState<string | null>(() =>
    loadActiveCompanyId(AUTH_MODE === 'oidc' ? 'anonymous' : 'dev'),
  )
  // Re-read the pointer once the oidc sub resolves (the initial state used 'anonymous').
  useEffect(() => {
    if (AUTH_MODE === 'oidc' && auth.sub) {
      setActiveCompanyId(loadActiveCompanyId(auth.sub))
    }
  }, [auth.sub])

  // Active outlet — persisted per-tab in sessionStorage, NOT localStorage.
  const [activeOutletId, setActiveOutletState] = useState<string | null>(
    () => loadOutletFromSessionStorage(),
  )

  // oidc: load the login's companies once the verified claim set is non-empty. The claim set is in
  // the key so a token refresh (an enlarged membership) re-fetches the list automatically.
  const oidcEnabled = AUTH_MODE === 'oidc' && auth.authenticated && auth.companyIds.length > 0
  const companiesQuery = useQuery({
    queryKey: ['myCompanies', auth.sub, auth.companyIds.join(',')],
    enabled: oidcEnabled,
    queryFn: async () => (await apiFetch<CompanyDto[]>('/api/v1/companies/mine')) ?? [],
  })

  const toSession = useCallback(
    (dto: CompanyDto): CompanySession => ({
      companyId: dto.id,
      name: dto.name,
      baseCurrency: dto.baseCurrency,
      defaultLanguage: dto.defaultLanguage,
      businessId: dto.firstBusinessId,
      actor: auth.actor,
    }),
    [auth.actor],
  )

  // The switcher list + the resolved active company.
  let companies: CompanySession[]
  let company: CompanySession | null
  let loading = false
  if (AUTH_MODE === 'oidc') {
    companies = (companiesQuery.data ?? []).map(toSession)
    // A just-onboarded company (manual) joins the list until /mine includes it.
    if (manual && !companies.some((c) => c.companyId === manual.companyId)) {
      companies = [...companies, manual]
    }
    const active =
      companies.find((c) => c.companyId === activeCompanyId) ?? companies[0] ?? null
    company = active
    loading = oidcEnabled && companiesQuery.isLoading && !manual
  } else {
    companies = devSessions
    company =
      companies.find((c) => c.companyId === activeCompanyId) ?? companies[0] ?? null
  }

  // Clear the active outlet whenever the company changes — both on logout (null) and on a
  // genuine A→B company switch (both non-null but different ids).
  const prevCompanyIdRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const prev = prevCompanyIdRef.current
    const current = company?.companyId
    prevCompanyIdRef.current = current

    if (prev === current) return // same company, nothing to do

    if (!current) {
      // Company cleared (logout / no session) — clear outlet too.
      setActiveOutletState(null)
      saveOutletToSessionStorage(null)
    } else if (prev !== undefined && prev !== null) {
      // Genuine company switch A→B — stale outlet belongs to a different tenant.
      setActiveOutletState(null)
      saveOutletToSessionStorage(null)
    }
  })

  /**
   * Registers a company as current — the onboarding/add-business hand-off. In dev it UPSERTS into
   * the persisted list and activates it (the multi-company extension of the old single-session
   * write); in oidc it holds the company as `manual` until the token refresh + /mine catch up,
   * and persists the active pointer so the selection survives the catch-up. `null` clears the dev
   * session list entirely (the legacy semantics).
   */
  function setCompany(next: CompanySession | null) {
    if (AUTH_MODE === 'dev') {
      if (!next) {
        setDevSessions([])
        saveDevSessions([])
        setActiveCompanyId(null)
        saveActiveCompanyId('dev', null)
        setActiveOutletState(null)
        saveOutletToSessionStorage(null)
        return
      }
      setDevSessions((prev) => {
        const merged = [...prev.filter((c) => c.companyId !== next.companyId), next]
        saveDevSessions(merged)
        return merged
      })
      setActiveCompanyId(next.companyId)
      saveActiveCompanyId('dev', next.companyId)
      return
    }
    setManual(next)
    if (next) {
      setActiveCompanyId(next.companyId)
      saveActiveCompanyId(activeScope, next.companyId)
    } else {
      setActiveOutletState(null)
      saveOutletToSessionStorage(null)
    }
  }

  /** Switches the active company; every companyId-keyed query re-fetches under the new tenant. */
  const setActiveCompany = useCallback(
    (companyId: string) => {
      setActiveCompanyId(companyId)
      saveActiveCompanyId(activeScope, companyId)
    },
    [activeScope],
  )

  const setActiveOutlet = useCallback((id: string | null) => {
    setActiveOutletState(id)
    saveOutletToSessionStorage(id)
  }, [])

  return (
    <SessionContext.Provider
      value={{
        company,
        companies,
        loading,
        setCompany,
        setActiveCompany,
        activeOutletId,
        setActiveOutlet,
      }}
    >
      {children}
    </SessionContext.Provider>
  )
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
