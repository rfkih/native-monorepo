import { describe, expect, it } from 'vitest'
import { hasAccessRoleMismatch, impliedAccessRole, uniqueBusinessRoles } from '../accessRoleMatch'

describe('impliedAccessRole', () => {
  it('matches a job title that is exactly one of the 8 access-role names', () => {
    expect(impliedAccessRole('manager')).toBe('manager')
    expect(impliedAccessRole('owner')).toBe('owner')
    expect(impliedAccessRole('cashier')).toBe('cashier')
  })

  it('is case-insensitive and trims whitespace', () => {
    expect(impliedAccessRole('Manager')).toBe('manager')
    expect(impliedAccessRole('MANAGER')).toBe('manager')
    expect(impliedAccessRole('  manager  ')).toBe('manager')
  })

  it('returns null for a free-text job title that is not an access-role name', () => {
    expect(impliedAccessRole('barista')).toBeNull()
    expect(impliedAccessRole('shift_lead')).toBeNull()
    expect(impliedAccessRole('head chef')).toBeNull()
  })

  it('returns null for null/undefined/empty job titles', () => {
    expect(impliedAccessRole(null)).toBeNull()
    expect(impliedAccessRole(undefined)).toBeNull()
    expect(impliedAccessRole('')).toBeNull()
    expect(impliedAccessRole('   ')).toBeNull()
  })
})

describe('hasAccessRoleMismatch', () => {
  it('true — the real bug: job title "manager" but the login only holds cashier', () => {
    expect(hasAccessRoleMismatch('manager', ['cashier'])).toBe(true)
  })

  it('false — the login already holds the implied role', () => {
    expect(hasAccessRoleMismatch('manager', ['manager'])).toBe(false)
    expect(hasAccessRoleMismatch('manager', ['cashier', 'manager'])).toBe(false)
  })

  it('false — case-insensitive match still resolves as already-held', () => {
    expect(hasAccessRoleMismatch('Manager', ['manager'])).toBe(false)
  })

  it('false — a free-text job title never implies a role, so never mismatches', () => {
    expect(hasAccessRoleMismatch('barista', [])).toBe(false)
    expect(hasAccessRoleMismatch('barista', ['cashier'])).toBe(false)
  })

  it('false — null job title', () => {
    expect(hasAccessRoleMismatch(null, [])).toBe(false)
  })

  it('true — implied role, login holds zero access roles', () => {
    expect(hasAccessRoleMismatch('hr', [])).toBe(true)
  })
})

describe('uniqueBusinessRoles', () => {
  it('de-duplicates while preserving first-occurrence order', () => {
    expect(uniqueBusinessRoles(['cashier', 'manager', 'cashier'])).toEqual(['cashier', 'manager'])
  })

  it('returns an empty array for an empty input', () => {
    expect(uniqueBusinessRoles([])).toEqual([])
  })

  it('is a plain union — the additive grant case (existing roles + newly implied role)', () => {
    expect(uniqueBusinessRoles([...['cashier'], 'manager'])).toEqual(['cashier', 'manager'])
    // Already-held role added again stays de-duplicated.
    expect(uniqueBusinessRoles([...['cashier', 'manager'], 'manager'])).toEqual([
      'cashier',
      'manager',
    ])
  })
})
