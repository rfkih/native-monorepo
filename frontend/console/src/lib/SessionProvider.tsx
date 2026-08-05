import { useCallback, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { AUTH_MODE } from '@/lib/config'
import { useAuth } from '@/lib/authContext'
import { SessionContext, type CompanySession } from '@/lib/session'

/**
 * Fills the session context — see session.ts for the model (oidc vs dev company resolution,
 * what is and is not trusted from storage). Split from session.ts so this file exports only a
 * component (react-refresh/only-export-components).
 */

/** Shape returned by org-service for the create / current / mine endpoints. */
interface CompanyDto {
  id: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  firstBusinessId: string
}

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
  // Re-read the pointer when the storage scope resolves or changes (oidc: 'anonymous' → sub).
  // Adjusted during render — never in an effect — so no frame pairs the new scope with the old
  // scope's pointer.
  const [loadedScope, setLoadedScope] = useState(AUTH_MODE === 'oidc' ? 'anonymous' : 'dev')
  if (loadedScope !== activeScope) {
    setLoadedScope(activeScope)
    setActiveCompanyId(loadActiveCompanyId(activeScope))
  }

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
    loading = oidcEnabled && companiesQuery.isLoading && !manual

    // Membership can be revoked server-side while the active pointer is still sitting in storage
    // from a previous login. Falling back to companies[0] for THIS render is not enough — without
    // persisting the correction, every future load would silently re-resolve to companies[0] again
    // (the stale pointer is never overwritten). Adjusted during render, NOT in an effect, so no
    // frame renders the now-invalid company. Gated on the query having genuinely settled (success,
    // not the first-load `loading` window) and a non-empty list, so a transient/partial state never
    // evicts a still-valid pointer; a just-onboarded `manual` company is already folded into
    // `companies` above, so it is never seen as "missing" here. Self-limiting: once corrected,
    // activeCompanyId equals companies[0].companyId, so this condition is false next render.
    if (
      companiesQuery.isSuccess &&
      !loading &&
      companies.length > 0 &&
      activeCompanyId != null &&
      !companies.some((c) => c.companyId === activeCompanyId)
    ) {
      const fallbackId = companies[0].companyId
      setActiveCompanyId(fallbackId)
      saveActiveCompanyId(activeScope, fallbackId)
    }

    const active =
      companies.find((c) => c.companyId === activeCompanyId) ?? companies[0] ?? null
    company = active
  } else {
    companies = devSessions
    company =
      companies.find((c) => c.companyId === activeCompanyId) ?? companies[0] ?? null
  }

  // Clear the active outlet whenever the company changes — logout (X→none) and genuine A→B
  // switches (a stale outlet belongs to a different tenant). Adjusted during render, NOT in an
  // effect: the old effect let children render one frame of company B paired with company A's
  // outlet before it fired. `undefined` = first render (nothing seen yet — a reload keeps the
  // restored per-tab outlet, and the initial none→company transition must not clear it either).
  // The idempotent sessionStorage remove rides along; StrictMode's double render is harmless.
  const currentCompanyId = company?.companyId ?? null
  const [seenCompanyId, setSeenCompanyId] = useState<string | null | undefined>(undefined)
  if (seenCompanyId !== currentCompanyId) {
    setSeenCompanyId(currentCompanyId)
    if (seenCompanyId != null) {
      setActiveOutletState(null)
      saveOutletToSessionStorage(null)
    }
  }

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
