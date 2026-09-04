import { describe, expect, it } from 'vitest'
import { shouldUseBillGatewayFlow } from '../billGatewayQris'

describe('shouldUseBillGatewayFlow', () => {
  it('true for a full-bill QRIS tender when the outlet resolves to GATEWAY', () => {
    expect(shouldUseBillGatewayFlow('QRIS', 'GATEWAY', false)).toBe(true)
  })

  it('false for a split check even when QRIS/GATEWAY — full-bill only this pass', () => {
    expect(shouldUseBillGatewayFlow('QRIS', 'GATEWAY', true)).toBe(false)
  })

  it('false for a non-QRIS tender regardless of mode', () => {
    expect(shouldUseBillGatewayFlow('CARD', 'GATEWAY', false)).toBe(false)
    expect(shouldUseBillGatewayFlow('CASH', 'GATEWAY', false)).toBe(false)
    expect(shouldUseBillGatewayFlow('ONLINE', 'GATEWAY', false)).toBe(false)
  })

  it('false when QRIS resolves to STATIC or MANUAL', () => {
    expect(shouldUseBillGatewayFlow('QRIS', 'STATIC', false)).toBe(false)
    expect(shouldUseBillGatewayFlow('QRIS', 'MANUAL', false)).toBe(false)
  })
})
