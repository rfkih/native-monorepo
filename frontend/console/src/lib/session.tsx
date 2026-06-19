import { createContext, useContext, useState, type ReactNode } from 'react'
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
}

const SessionContext = createContext<SessionContextValue | null>(null)
const STORAGE_KEY = 'native.console.session'

function loadDev(): CompanySession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as CompanySession) : null
  } catch {
    return null
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const auth = useAuth()
  // `manual` holds the dev-persisted company AND any just-onboarded company; in oidc mode the
  // canonical source is the API query below.
  const [manual, setManual] = useState<CompanySession | null>(() =>
    AUTH_MODE === 'dev' ? loadDev() : null,
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

  function setCompany(next: CompanySession | null) {
    setManual(next)
    if (AUTH_MODE === 'dev') {
      try {
        if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
        else localStorage.removeItem(STORAGE_KEY)
      } catch {
        /* storage unavailable — in-memory only */
      }
    }
  }

  return (
    <SessionContext.Provider value={{ company, loading, setCompany }}>
      {children}
    </SessionContext.Provider>
  )
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
