import { describe, expect, it } from 'vitest'
import {
  canActivateEnvironment,
  gatewayActiveConnected,
  type TypedGatewayKeys,
} from '../gatewayActivation'
import type { GatewayEnvironment, GatewaySettings } from '../api'

const NONE: TypedGatewayKeys = { sandboxServerKey: '', productionServerKey: '' }

function gateway(
  sandboxConnected: boolean,
  productionConnected: boolean,
  active: GatewayEnvironment = 'SANDBOX',
): GatewaySettings {
  return {
    provider: 'MIDTRANS',
    activeEnvironment: active,
    sandbox: { serverKeyLast4: sandboxConnected ? '9999' : null, connected: sandboxConnected },
    production: { serverKeyLast4: productionConnected ? '8888' : null, connected: productionConnected },
  }
}

describe('canActivateEnvironment', () => {
  it('allows an environment whose slot already holds a stored key', () => {
    expect(canActivateEnvironment('PRODUCTION', gateway(false, true), NONE)).toBe(true)
    expect(canActivateEnvironment('SANDBOX', gateway(true, false), NONE)).toBe(true)
  })

  it('blocks an environment with no stored key and nothing typed', () => {
    expect(canActivateEnvironment('PRODUCTION', gateway(true, false), NONE)).toBe(false)
    expect(canActivateEnvironment('SANDBOX', null, NONE)).toBe(false)
  })

  it('allows an environment once a key is typed into its own field', () => {
    expect(
      canActivateEnvironment('PRODUCTION', gateway(true, false), {
        sandboxServerKey: '',
        productionServerKey: 'Mid-server-typed',
      }),
    ).toBe(true)
  })

  it('does not let a key typed for the OTHER environment unlock this one', () => {
    // A sandbox key typed does not authorize activating PRODUCTION — the trap this guard closes.
    expect(
      canActivateEnvironment('PRODUCTION', gateway(true, false), {
        sandboxServerKey: 'SB-Mid-server-typed',
        productionServerKey: '',
      }),
    ).toBe(false)
  })
})

describe('gatewayActiveConnected', () => {
  it('reflects the ACTIVE environment slot, not the other one', () => {
    // Active SANDBOX + sandbox connected → connected, even though production is empty.
    expect(gatewayActiveConnected(gateway(true, false, 'SANDBOX'))).toBe(true)
    // Active PRODUCTION + only sandbox connected → NOT connected (follows the active slot).
    expect(gatewayActiveConnected(gateway(true, false, 'PRODUCTION'))).toBe(false)
    // Active PRODUCTION + production connected → connected.
    expect(gatewayActiveConnected(gateway(false, true, 'PRODUCTION'))).toBe(true)
  })

  it('is false when no gateway is configured', () => {
    expect(gatewayActiveConnected(null)).toBe(false)
  })
})
