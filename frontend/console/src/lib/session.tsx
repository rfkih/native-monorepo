import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { AUTH_MODE } from '@/lib/config'
import { useAuth } from '@/lib/authContext'

/**
 * The company the console is currently acting as.
 *
 * - **oidc mode**: identity drives the tenant. `companyId`/`actor` come from the verified token;
 *   the company's `name`/`baseCurrency`/`businessId` are loaded from `GET /api/v1/companies/current`
 *   (the bound tenant). localStorage is NOT trusted for identity.
 * - **dev mode**: the company is set at onboarding and persisted to localStorage so a refresh keeps
 *   the tenant (the original local-dev behaviour).
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

/** Shape returned by org-service for the create + current-company endpoints. */
interface CompanyDto {
  id: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  firstBusinessId: string
}

interface SessionContextValue {
  company: CompanySession | null
  /** True while the signed-in user's company is still being loaded (oidc mode). */
  loading: boolean
  setCompany: (company: CompanySession | null) => void
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
const STORAGE_KEY = 'native.console.session'
const OUTLET_SESSION_KEY = 'native.console.outlet'

function loadDev(): CompanySession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as CompanySession) : null
  } catch {
    return null
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
  // `manual` holds the dev-persisted company AND any just-onboarded company; in oidc mode the
  // canonical source is the API query below.
  const [manual, setManual] = useState<CompanySession | null>(() =>
    AUTH_MODE === 'dev' ? loadDev() : null,
  )

  // Active outlet — persisted per-tab in sessionStorage, NOT localStorage.
  const [activeOutletId, setActiveOutletState] = useState<string | null>(
    () => loadOutletFromSessionStorage(),
  )

  // oidc: load the signed-in user's company once we have a verified company_id claim.
  const oidcEnabled = AUTH_MODE === 'oidc' && auth.authenticated && !!auth.companyId
  const companyQuery = useQuery({
    queryKey: ['company', 'current', auth.companyId],
    enabled: oidcEnabled,
    queryFn: () => apiFetch<CompanyDto>('/api/v1/companies/current'),
  })

  let company: CompanySession | null = manual
  let loading = false
  if (AUTH_MODE === 'oidc') {
    if (manual) {
      company = manual
    } else if (companyQuery.data && auth.companyId) {
      const c = companyQuery.data
      company = {
        companyId: auth.companyId,
        name: c.name,
        baseCurrency: c.baseCurrency,
        defaultLanguage: c.defaultLanguage,
        businessId: c.firstBusinessId,
        actor: auth.actor,
      }
    } else {
      company = null
    }
    loading = oidcEnabled && companyQuery.isLoading
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

  function setCompany(next: CompanySession | null) {
    setManual(next)
    if (!next) {
      // Company cleared — clear outlet for this tab.
      setActiveOutletState(null)
      saveOutletToSessionStorage(null)
    }
    if (AUTH_MODE === 'dev') {
      try {
        if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
        else localStorage.removeItem(STORAGE_KEY)
      } catch {
        /* storage unavailable — in-memory only */
      }
    }
  }

  const setActiveOutlet = useCallback((id: string | null) => {
    setActiveOutletState(id)
    saveOutletToSessionStorage(id)
  }, [])

  return (
    <SessionContext.Provider value={{ company, loading, setCompany, activeOutletId, setActiveOutlet }}>
      {children}
    </SessionContext.Provider>
  )
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}

