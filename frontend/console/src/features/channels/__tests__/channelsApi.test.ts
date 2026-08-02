import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { isSalesChannelCodeConflict } from '../channelsApi'

function apiError(status: number, type: string): ApiError {
  return new ApiError(status, { type }, `HTTP ${status}`)
}

describe('isSalesChannelCodeConflict', () => {
  it('true for the 409 sales-channel-code-conflict problem', () => {
    expect(
      isSalesChannelCodeConflict(apiError(409, 'https://errors.nativeapp.id/sales-channel-code-conflict')),
    ).toBe(true)
  })

  it('false for a 409 of a different type', () => {
    expect(isSalesChannelCodeConflict(apiError(409, 'https://errors.nativeapp.id/promotion-conflict'))).toBe(
      false,
    )
  })

  it('false for the right type on the wrong status', () => {
    expect(
      isSalesChannelCodeConflict(apiError(422, 'https://errors.nativeapp.id/sales-channel-code-conflict')),
    ).toBe(false)
  })

  it('false for non-ApiErrors', () => {
    expect(isSalesChannelCodeConflict(new Error('network'))).toBe(false)
    expect(isSalesChannelCodeConflict(undefined)).toBe(false)
  })
})
