/**
 * OperatorSessionProvider (ADR 0049 P3b, storage scope amended P3d) — the Business-app till's
 * signed-in operator, mounted once app-wide (main.tsx, alongside AuthProvider/SessionProvider) so
 * it survives the OutletGate remount (Pos.tsx keys its inner tree on the outlet id) and any route
 * change within the same device session. Persists to `sessionStorage` within the token's `exp`
 * (operatorSessionStore.ts) and arms `lib/api.ts`'s `X-Operator-Session` header on every mutation
 * of the signed-in operator.
 *
 * P3d (owner request): the operator must re-identify (re-pick, and re-PIN when the outlet
 * requires one) every time the app is reopened — a shift-change safety property, not just
 * convenience. `sessionStorage` survives an in-app reload/route change but is gone after a cold
 * relaunch, unlike the OUTLET/device credential itself, which stays in `localStorage`
 * (`lib/auth.tsx`, deliberately untouched here — kiosk persistence for THAT credential is still
 * wanted, only the per-shift operator identity resets).
 *
 * Capacitor caveat: `sessionStorage` clearing on a cold app launch is a WebView-process-lifetime
 * assumption (the SAME one `lib/auth.tsx`'s personal-elevation `UserManager` already relies on —
 * see its own comment), not a spec guarantee. TODO: if a specific WebView build is ever found to
 * persist `sessionStorage` across a cold launch, the fallback is an explicit clear on native
 * cold-boot (e.g. a Capacitor `App` `resume`/launch listener calling `clearOperatorSession`) — not
 * implemented here; no such listener exists yet.
 *
 * Reused ACROSS a normal `user` login too (the context always exists everywhere), but nothing ever
 * calls `signIn` there — `Pos.tsx`/`ServicePos.tsx` only open the PIN sheet when
 * `auth.actorType === 'device'` — so a person login's `operator` stays null forever and every byte
 * of existing behavior is unchanged.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { setOperatorSession as armOperatorSessionHeader } from '@/lib/api'
import { useAuth } from '@/lib/authContext'
import { useSession } from '@/lib/session'
import { useOperatorSignInMutation } from './api'
import {
  clearOperatorSession,
  loadOperatorSession,
  saveOperatorSession,
  type OperatorSessionData,
} from './operatorSessionStore'
import { OperatorSessionContext, type OperatorInfo } from './operatorSessionContext'

function toInfo(data: OperatorSessionData): OperatorInfo {
  return { displayName: data.displayName, role: data.role }
}

export function OperatorSessionProvider({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const { company } = useSession()
  const signInMutation = useOperatorSignInMutation(company)

  const [data, setData] = useState<OperatorSessionData | null>(() =>
    typeof window === 'undefined' ? null : loadOperatorSession(window.sessionStorage),
  )

  // Arm the shared X-Operator-Session header to match whatever was (or wasn't) restored from
  // sessionStorage on mount. Every later change goes through signIn/signOut below, which arm it
  // inline — this effect only covers the initial load.
  useEffect(() => {
    armOperatorSessionHeader(data?.token ?? null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Device sign-out safety net: a genuine authenticated -> unauthenticated TRANSITION (the outlet
  // credential itself signed out) drops any lingering operator — the NEXT person to use this
  // browser/kiosk must never find a stale operator already "signed in". Deliberately does NOT fire
  // on the initial unauthenticated boot state (ready:false/authenticated:false before the session
  // resolves), which would otherwise wipe a legitimately persisted operator on every cold start
  // before auth even settles — only a TRUE true->false edge clears it.
  const wasAuthenticated = useRef(auth.authenticated)
  useEffect(() => {
    if (wasAuthenticated.current && !auth.authenticated) {
      clearOperatorSession(window.sessionStorage)
      armOperatorSessionHeader(null)
      setData(null)
    }
    wasAuthenticated.current = auth.authenticated
  }, [auth.authenticated])

  async function signIn(
    businessId: string,
    employeeId: string,
    pin?: string,
  ): Promise<OperatorInfo> {
    const res = await signInMutation.mutateAsync({ businessId, employeeId, pin })
    if (!res) throw new Error('empty operator session response')
    const next: OperatorSessionData = {
      token: res.operatorSession,
      displayName: res.displayName,
      role: res.role,
      expiresAt: res.expiresAt,
    }
    saveOperatorSession(window.sessionStorage, next)
    armOperatorSessionHeader(next.token)
    setData(next)
    return toInfo(next)
  }

  function signOut() {
    clearOperatorSession(window.sessionStorage)
    armOperatorSessionHeader(null)
    setData(null)
  }

  return (
    <OperatorSessionContext.Provider
      value={{ operator: data ? toInfo(data) : null, signIn, signOut }}
    >
      {children}
    </OperatorSessionContext.Provider>
  )
}
