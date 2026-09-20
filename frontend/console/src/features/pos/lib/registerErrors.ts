/**
 * RFC-7807 register-session faults (restaurant-service `RegisterAdvice`) → i18n keys. A refused
 * close is a routine end-of-day event, not a raw English diagnostic with an internal id (rule 9).
 * Pure (no react), shared by the register sheet and its close mutation.
 */
import { ApiError } from '@/lib/api'

const REGISTER_ERROR_KEY: Record<string, string> = {
  'register-session-day-closed': 'register.errorDayClosed',
  'register-session-already-open': 'register.errorAlreadyOpen',
  'register-session-not-open': 'register.errorNotOpen',
  'register-session-idempotency-key-conflict': 'register.errorKeyConflict',
  // ADR 0086 — bills still OPEN at the outlet; pay or cancel them, then close again.
  'register-session-open-bills': 'register.errorOpenBills',
}

/** The i18n key for a known register fault, or null to fall back to the server's detail. */
export function registerErrorKey(err: unknown): string | null {
  if (err instanceof ApiError && typeof err.problem?.type === 'string') {
    for (const slug of Object.keys(REGISTER_ERROR_KEY)) {
      if (err.problem.type.includes(slug)) return REGISTER_ERROR_KEY[slug]
    }
  }
  return null
}

/** The 409 the close answers while bills are OPEN (ADR 0086) — the sheet flips to its blocked state. */
export function isRegisterOpenBillsFault(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('register-session-open-bills')
  )
}
