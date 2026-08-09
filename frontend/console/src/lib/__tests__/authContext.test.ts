import { describe, expect, it } from 'vitest'
import { effectiveRoles, hasAnyRole } from '../authContext'

describe('effectiveRoles — ADR 0049 P3b merged-roles derivation', () => {
  it('a normal `user` login (elevatedRoles=[]) is byte-identical to its base roles', () => {
    expect(effectiveRoles(['owner'], [])).toEqual(['owner'])
    expect(effectiveRoles(['cashier'], [])).toEqual(['cashier'])
    expect(effectiveRoles([], [])).toEqual([])
  })

  it('returns a NEW array (never the same reference) even when elevatedRoles is empty', () => {
    const roles = ['owner'] as const
    const result = effectiveRoles(roles, [])
    expect(result).not.toBe(roles)
    expect(result).toEqual(roles)
  })

  it('unions an elevated device terminal onto its base cashier role', () => {
    expect(effectiveRoles(['cashier'], ['owner'])).toEqual(['cashier', 'owner'])
  })

  it('de-duplicates when the elevated role overlaps the base role', () => {
    expect(effectiveRoles(['cashier', 'owner'], ['owner'])).toEqual(['cashier', 'owner'])
  })

  it('unions multiple elevated roles', () => {
    const merged = effectiveRoles(['cashier'], ['owner', 'manager'])
    expect(new Set(merged)).toEqual(new Set(['cashier', 'owner', 'manager']))
    expect(merged).toHaveLength(3)
  })

  it('base roles come first, elevated additions appended after (order stable for a fixed input)', () => {
    expect(effectiveRoles(['cashier'], ['manager'])).toEqual(['cashier', 'manager'])
  })
})

describe('hasAnyRole composed with effectiveRoles — the App.tsx canDashboard/isOwner pattern', () => {
  it('a bare (unelevated) device terminal cannot reach the dashboard', () => {
    const roles = effectiveRoles(['cashier'], [])
    expect(hasAnyRole(roles, 'owner', 'manager')).toBe(false)
  })

  it('an elevated (owner) device terminal CAN reach the dashboard', () => {
    const roles = effectiveRoles(['cashier'], ['owner'])
    expect(hasAnyRole(roles, 'owner', 'manager')).toBe(true)
  })

  it('an elevated (manager) device terminal reaches the dashboard but is not "owner"', () => {
    const roles = effectiveRoles(['cashier'], ['manager'])
    expect(hasAnyRole(roles, 'owner', 'manager')).toBe(true)
    expect(hasAnyRole(roles, 'owner')).toBe(false)
  })

  it('a normal owner login is unaffected (canDashboard true with no elevation involved)', () => {
    const roles = effectiveRoles(['owner'], [])
    expect(hasAnyRole(roles, 'owner', 'manager')).toBe(true)
    expect(hasAnyRole(roles, 'owner')).toBe(true)
  })
})
