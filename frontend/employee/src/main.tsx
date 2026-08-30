import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import i18n from '@/i18n'
import { queryClient } from '@/lib/queryClient'
import { AuthProvider } from '@/lib/auth'
import { SessionProvider } from '@/lib/SessionProvider'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { staffResources } from './staffI18n'
import { App } from './App'

// Merge the staff-app-specific strings into the shared `translation` namespace (deep, overwrite) —
// kept out of the console's locale files so the two apps never collide. See staffI18n.ts.
i18n.addResourceBundle('en', 'translation', staffResources.en, true, true)
i18n.addResourceBundle('id', 'translation', staffResources.id, true, true)

/**
 * The Employee app's boot (ADR 0049 P5) — a deliberately SMALL subset of the console's own
 * main.tsx provider stack: QueryClientProvider + AuthProvider + SessionProvider + BrowserRouter,
 * all reused UNCHANGED via the `@` alias into ../console/src (see vite.config.ts). No
 * OperatorSessionProvider (no till operators here), no PrinterProvider (no receipt printing),
 * and no bootstrapPwa() (this app is not offline-first — ADR 0049 P5 scope).
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Outermost: a crash anywhere below must show the recover screen, never a blank page — the
        Android shell has no reload affordance of its own. */}
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        {/* persistSession: the Employee app is a PERSONAL-device app (ADR 0049 P5) — keep the OIDC
            session in localStorage so an employee stays signed in across app/browser restarts (the
            offline refresh token then re-auths silently for the ~30-day offline idle window). The
            console deliberately does NOT pass this — its sessionStorage default protects shared
            computers. */}
        <AuthProvider persistSession>
          <SessionProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </SessionProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
