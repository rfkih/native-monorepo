import { useMutation } from '@tanstack/react-query'
import { apiFetch, type ApiError } from '@/lib/api'

export interface SignupRequest {
  companyName: string
  baseCurrency: string
  defaultLanguage: string
  firstBusinessName: string
  /** Lowercase business vertical (restaurant | carwash | barbershop). */
  vertical: string
  ownerEmail: string
  ownerPassword: string
  /** ToS consent — also enforced server-side (@AssertTrue). */
  termsAccepted: boolean
}

export interface SignupResponse {
  companyId: string
  ownerEmail: string
  /** True when the realm requires email verification before first login. */
  emailVerificationRequired: boolean
}

/**
 * POST /api/v1/signup — fully public; no auth header, no X-Company-Id.
 * Creates the company + owner account in one shot. Errors follow RFC-7807 (problem+json);
 * a 409 with type ending in "email-already-exists" gets its own i18n key.
 */
async function signup(body: SignupRequest): Promise<SignupResponse> {
  // No `tenant` or `actor` options — this is an unauthenticated bootstrap endpoint.
  const res = await apiFetch<SignupResponse>('/api/v1/signup', {
    method: 'POST',
    body,
  })
  if (!res) throw new Error('Empty response from signup')
  return res
}

export function useSignup() {
  return useMutation<SignupResponse, ApiError, SignupRequest>({
    mutationFn: signup,
  })
}
