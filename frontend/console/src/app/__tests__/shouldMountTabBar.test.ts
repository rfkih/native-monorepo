import { describe, expect, it } from 'vitest'
import { shouldMountTabBar } from '../tabBarPolicy'

describe('shouldMountTabBar', () => {
  it('never mounts on the full-screen till surfaces', () => {
    expect(shouldMountTabBar('/pos')).toBe(false)
    expect(shouldMountTabBar('/pos/customer-display')).toBe(false)
    expect(shouldMountTabBar('/menu')).toBe(false)
    expect(shouldMountTabBar('/catalog')).toBe(false)
    expect(shouldMountTabBar('/kitchen')).toBe(false)
    expect(shouldMountTabBar('/ingredients')).toBe(false)
  })

  it('never mounts inside the onboarding wizard', () => {
    expect(shouldMountTabBar('/onboarding')).toBe(false)
    expect(shouldMountTabBar('/onboarding/company')).toBe(false)
  })

  it('mounts on the back-office shell pages', () => {
    expect(shouldMountTabBar('/')).toBe(true)
    expect(shouldMountTabBar('/statements/income')).toBe(true)
    expect(shouldMountTabBar('/team')).toBe(true)
    expect(shouldMountTabBar('/expenses')).toBe(true)
    expect(shouldMountTabBar('/close')).toBe(true)
    expect(shouldMountTabBar('/ar/aging')).toBe(true)
  })

  it('mounts on the employee self-service surfaces', () => {
    expect(shouldMountTabBar('/me')).toBe(true)
    expect(shouldMountTabBar('/me/expenses')).toBe(true)
    expect(shouldMountTabBar('/me/payslips')).toBe(true)
    expect(shouldMountTabBar('/me/timeoff')).toBe(true)
  })

  it('mounts on settings (they are otherwise phone dead-ends)', () => {
    expect(shouldMountTabBar('/settings/printer')).toBe(true)
    expect(shouldMountTabBar('/settings/features')).toBe(true)
  })

  it('does not over-match path prefixes', () => {
    // A hypothetical /menuboard-style sibling must not inherit /menu's exclusion.
    expect(shouldMountTabBar('/menu-x')).toBe(true)
    expect(shouldMountTabBar('/positions')).toBe(true)
  })
})
