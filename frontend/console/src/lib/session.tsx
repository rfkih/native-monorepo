import { createContext, useContext, useState, type ReactNode } from 'react'

/**
 * The company the console is currently acting as. Set when a company is created (or selected) and
 * persisted to localStorage so a refresh keeps the tenant. The base currency + default language are
 * fixed at company creation (org-service) — the console reads them, it never offers to change them.
 */
export interface CompanySession {
  companyId: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  actor: string
}

interface SessionContextValue {
  company: CompanySession | null
  setCompany: (company: CompanySession | null) => void
}

const SessionContext = createContext<SessionContextValue | null>(null)
const STORAGE_KEY = 'native.console.session'

function load(): CompanySession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as CompanySession) : null
  } catch {
    return null
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [company, setCompanyState] = useState<CompanySession | null>(load)

  function setCompany(next: CompanySession | null) {
    setCompanyState(next)
    try {
      if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      else localStorage.removeItem(STORAGE_KEY)
    } catch {
      /* storage unavailable — in-memory only */
    }
  }

  return (
    <SessionContext.Provider value={{ company, setCompany }}>{children}</SessionContext.Provider>
  )
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
