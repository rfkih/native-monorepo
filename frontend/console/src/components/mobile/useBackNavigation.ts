/**
 * useBackNavigation — Back as a callable, for the moments a screen finishes on its own (a form
 * saved, a step completed) and wants to leave the way the arrow would (ADR 0075 rule N1: pop,
 * never push). Same decision as BackButton — `backIntentFor` owns it — so a save after a deep
 * link lands on the fallback instead of leaving the app, and a save after a push simply pops.
 */
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { backIntentFor } from './backGuardProtocol'

export function useBackNavigation(): (fallback: string) => void {
  const navigate = useNavigate()
  return useCallback(
    (fallback: string) => {
      const intent = backIntentFor(window.history.state)
      if (intent.kind === 'pop') navigate(intent.delta)
      else navigate(fallback, { replace: true })
    },
    [navigate],
  )
}
