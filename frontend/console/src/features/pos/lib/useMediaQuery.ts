/**
 * useMediaQuery — SSR-safe matchMedia subscriber, extracted from BillDetail.tsx (redesign P2).
 * The responsive shell container (P4) shares it. Rewritten from a useState+useEffect pair to
 * useSyncExternalStore in the move: same observable behavior, but no synchronous setState inside
 * an effect (react-hooks/set-state-in-effect — this was the one lint error that traveled out of
 * BillDetail).
 */
import { useCallback, useSyncExternalStore } from 'react'

export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const mql = window.matchMedia(query)
      mql.addEventListener('change', onChange)
      return () => mql.removeEventListener('change', onChange)
    },
    [query],
  )
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => false,
  )
}
