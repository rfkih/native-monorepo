import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

/**
 * SessionProvider owns the active-company + active-outlet pointers (session.ts's model). It is a
 * plain component with no test-only exports (react-refresh/only-export-components — see the file's
 * own header comment), and this repo ships neither @testing-library/react nor a DOM environment
 * (vitest here runs `environment: 'node'`, no jsdom/happy-dom) nor react-test-renderer. Per the
 * task's own instruction not to add dependencies, this suite renders the REAL component with
 * `react-dom/server`'s `renderToStaticMarkup` (part of the already-installed `react-dom` — no new
 * package), which runs happily in a plain Node process and still executes the provider's
 * render-phase "adjust state as we compute it" logic (the loadedScope / activeCompanyId / outlet
 * blocks) exactly as the client build does — React resolves those in-render `setState` calls by
 * re-invoking the function component before the pass returns, on both the client and server
 * renderer, so the markup below reflects the FULLY SETTLED values, not a pre-adjustment snapshot.
 *
 * localStorage/sessionStorage are polyfilled with a tiny in-memory Storage below (Node has no
 * global Web Storage under vitest's `node` environment) — not a new dependency, just ~15 lines
 * implementing the standard `Storage` interface.
 *
 * LIMITATION (reported per the task's instructions rather than papered over): a static, one-shot
 * SSR render cannot express a genuine A→B *transition* of an already-mounted instance (the "switch
 * companies" and "log out" cases) — that needs a live reconciler processing a second commit against
 * the SAME fiber tree (a mounted `createRoot` + `act`, which needs a real DOM/jsdom, or
 * `react-test-renderer`), neither of which is installed. Those two rows of the transition table are
 * marked `.todo` below with the reason, rather than silently dropped or faked.
 */

class MemoryStorage implements Storage {
  private map = new Map<string, string>()
  get length() {
    return this.map.size
  }
  clear(): void {
    this.map.clear()
  }
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) ?? null) : null
  }
  key(index: number): string | null {
    return Array.from(this.map.keys())[index] ?? null
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
  setItem(key: string, value: string): void {
    this.map.set(key, String(value))
  }
}

vi.mock('@/lib/config', () => ({ AUTH_MODE: 'oidc' }))
vi.mock('@/lib/api', () => ({ apiFetch: vi.fn().mockResolvedValue([]) }))
vi.mock('@/lib/authContext', () => ({
  useAuth: () => ({
    ready: true,
    authenticated: true,
    companyId: 'company-1',
    companyIds: ['company-1'],
    actor: 'owner@acme.test',
    sub: 'sub-1',
    roles: ['owner'],
    login: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
  }),
}))

import { SessionProvider } from '../SessionProvider'
import { useSession } from '../session'

const ACTIVE_KEY = 'native.console.activeCompany.sub-1'
const OUTLET_KEY = 'native.console.outlet'

const COMPANY_1 = {
  id: 'company-1',
  name: 'Acme',
  baseCurrency: 'IDR',
  defaultLanguage: 'id',
  firstBusinessId: 'biz-1',
}
const COMPANY_2 = {
  id: 'company-2',
  name: 'Acme Cabang',
  baseCurrency: 'IDR',
  defaultLanguage: 'id',
  firstBusinessId: 'biz-2',
}

/** Reads the resolved session (company/companies/outlet) via a probe child, in ONE render pass. */
function Probe() {
  const { company, companies, activeOutletId } = useSession()
  return createElement('div', {
    'data-company': company?.companyId ?? 'none',
    'data-companies': companies.map((c) => c.companyId).join(','),
    'data-outlet': activeOutletId ?? 'none',
  })
}

/** Mounts SessionProvider with the `/mine` response pre-seeded into the query cache (synchronously
 * `isSuccess`, matching the real app after the first successful load — see the file header). */
function renderProbe(companies: (typeof COMPANY_1)[]) {
  const client = new QueryClient()
  client.setQueryData(['myCompanies', 'sub-1', 'company-1'], companies)
  return renderToStaticMarkup(
    createElement(
      QueryClientProvider,
      { client },
      createElement(SessionProvider, null, createElement(Probe)),
    ),
  )
}

function attr(html: string, name: string): string {
  const m = new RegExp(`${name}="([^"]*)"`).exec(html)
  if (!m) throw new Error(`attribute ${name} not found in ${html}`)
  return m[1]
}

beforeEach(() => {
  globalThis.localStorage = new MemoryStorage()
  globalThis.sessionStorage = new MemoryStorage()
})

describe('SessionProvider — active-company / active-outlet transition table', () => {
  it('(a) reload restore: company X in storage + an outlet in sessionStorage -> both survive', () => {
    localStorage.setItem(ACTIVE_KEY, 'company-1')
    sessionStorage.setItem(OUTLET_KEY, 'outlet-9')

    const html = renderProbe([COMPANY_1, COMPANY_2])

    expect(attr(html, 'data-company')).toBe('company-1')
    expect(attr(html, 'data-outlet')).toBe('outlet-9')
    // Nothing corrected a valid pointer — storage is untouched.
    expect(localStorage.getItem(ACTIVE_KEY)).toBe('company-1')
    expect(sessionStorage.getItem(OUTLET_KEY)).toBe('outlet-9')
  })

  it('(b) none -> X initial load: no stored pointer, resolves to companies[0], outlet survives', () => {
    // No ACTIVE_KEY at all (a brand-new login). A stray outlet from a previous tab session must not
    // be wiped on the very first-ever render — there is nothing "previously seen" to compare against
    // yet (see SessionProvider's `seenCompanyId` sentinel: `undefined` means "nothing seen").
    sessionStorage.setItem(OUTLET_KEY, 'outlet-1')

    const html = renderProbe([COMPANY_1, COMPANY_2])

    expect(attr(html, 'data-company')).toBe('company-1')
    expect(attr(html, 'data-outlet')).toBe('outlet-1')
  })

  it('(e) activeCompanyId not in list -> falls back to companies[0] AND persists the correction', () => {
    // A pointer left over from a login whose membership was since revoked.
    localStorage.setItem(ACTIVE_KEY, 'revoked-company')

    const html = renderProbe([COMPANY_1, COMPANY_2])

    expect(attr(html, 'data-company')).toBe('company-1')
    // The whole point of the fix: the stale pointer itself is corrected, not just this render's
    // resolved value — otherwise every future load re-resolves silently, forever.
    expect(localStorage.getItem(ACTIVE_KEY)).toBe('company-1')
  })

  it('(e) a genuinely empty company list never evicts a pointer (transient/partial load stays safe)', () => {
    localStorage.setItem(ACTIVE_KEY, 'company-1')

    const html = renderProbe([])

    // No companies to fall back to — the pointer is left exactly as it was, not overwritten to null.
    expect(attr(html, 'data-company')).toBe('none')
    expect(localStorage.getItem(ACTIVE_KEY)).toBe('company-1')
  })

  // (c) and (d) — X→Y switch and X→none — are genuine transitions of an ALREADY-MOUNTED instance
  // (calling `setActiveCompany`, or the login's company list emptying out from under it). Proving
  // those needs a live reconciler committing a SECOND pass over the SAME fiber tree (a mounted
  // `createRoot` + `act()`, which needs a real DOM/jsdom — or `react-test-renderer`). Neither is a
  // devDependency of this project, and the task's instructions are to not add one. Left as `.todo`
  // (visible in the test run, not silently dropped) rather than faked with a second, unrelated
  // `renderToStaticMarkup` call — a fresh call mounts a FRESH instance, whose `seenCompanyId`
  // sentinel starts at `undefined` again, so it can never exercise the "already saw a company"
  // branch that the outlet-clearing logic depends on.
  it.todo(
    '(c) X→Y switch (same mounted instance) -> outlet cleared in state + sessionStorage — ' +
      'needs a live reconciler (jsdom + @testing-library/react, or react-test-renderer); not installed',
  )
  it.todo(
    '(d) X→none (same mounted instance, e.g. logout) -> outlet cleared — same limitation as (c)',
  )
})
