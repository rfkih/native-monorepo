import { describe, expect, it } from 'vitest'
import { effectiveQrisMode, type EffectiveSettings } from '../effectiveMode'

const MANUAL: EffectiveSettings = { mode: 'MANUAL', staticQrAvailable: false, gateway: null }
const STATIC_READY: EffectiveSettings = { mode: 'STATIC', staticQrAvailable: true, gateway: null }
const STATIC_NO_IMAGE: EffectiveSettings = { mode: 'STATIC', staticQrAvailable: false, gateway: null }
const GATEWAY_CONNECTED: EffectiveSettings = {
  mode: 'GATEWAY',
  staticQrAvailable: false,
  gateway: { provider: 'MIDTRANS', environment: 'SANDBOX', connected: true },
}
const GATEWAY_DISCONNECTED: EffectiveSettings = {
  mode: 'GATEWAY',
  staticQrAvailable: false,
  gateway: { provider: 'MIDTRANS', environment: 'SANDBOX', connected: false },
}
const GATEWAY_NO_PROVIDER: EffectiveSettings = { mode: 'GATEWAY', staticQrAvailable: false, gateway: null }

describe('effectiveQrisMode', () => {
  it('MANUAL settings stay MANUAL', () => {
    expect(effectiveQrisMode(MANUAL, false, false, 'IDR')).toBe('MANUAL')
  })

  it('missing settings (still loading, or a 404-shaped gap) fail open to MANUAL', () => {
    expect(effectiveQrisMode(undefined, false, false, 'IDR')).toBe('MANUAL')
  })

  it('a query error fails open to MANUAL, even with a healthy STATIC row cached', () => {
    expect(effectiveQrisMode(STATIC_READY, true, false, 'IDR')).toBe('MANUAL')
  })

  it('offline fails open to MANUAL regardless of the resolved mode', () => {
    expect(effectiveQrisMode(STATIC_READY, false, true, 'IDR')).toBe('MANUAL')
    expect(effectiveQrisMode(GATEWAY_CONNECTED, false, true, 'IDR')).toBe('MANUAL')
  })

  describe('STATIC', () => {
    it('stays STATIC when an image is on file', () => {
      expect(effectiveQrisMode(STATIC_READY, false, false, 'IDR')).toBe('STATIC')
    })

    it('degrades to MANUAL when no image has been uploaded yet', () => {
      expect(effectiveQrisMode(STATIC_NO_IMAGE, false, false, 'IDR')).toBe('MANUAL')
    })
  })

  describe('GATEWAY', () => {
    it('stays GATEWAY for a connected provider in IDR', () => {
      expect(effectiveQrisMode(GATEWAY_CONNECTED, false, false, 'IDR')).toBe('GATEWAY')
    })

    it('degrades to MANUAL for a non-IDR sale, even with a connected provider', () => {
      expect(effectiveQrisMode(GATEWAY_CONNECTED, false, false, 'USD')).toBe('MANUAL')
    })

    it('degrades to MANUAL when the provider is not connected', () => {
      expect(effectiveQrisMode(GATEWAY_DISCONNECTED, false, false, 'IDR')).toBe('MANUAL')
    })

    it('degrades to MANUAL when no gateway is configured at all', () => {
      expect(effectiveQrisMode(GATEWAY_NO_PROVIDER, false, false, 'IDR')).toBe('MANUAL')
    })
  })
})
