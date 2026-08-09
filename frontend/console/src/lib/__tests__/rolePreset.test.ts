import { describe, expect, it } from 'vitest'
import { canFinance, canHr, canOps, canPayroll, canPos, canReports, ROLE_HOME } from '../rolePreset'

describe('rolePreset — single role → home + capabilities', () => {
  it('owner: home "/" and every capability', () => {
    const roles = ['owner']
    expect(ROLE_HOME(roles)).toBe('/')
    expect(canOps(roles)).toBe(true)
    expect(canReports(roles)).toBe(true)
    expect(canFinance(roles)).toBe(true)
    expect(canHr(roles)).toBe(true)
    expect(canPayroll(roles)).toBe(true)
    expect(canPos(roles)).toBe(true)
  })

  it('manager: home "/", OPS/REPORTS/HR/POS but NOT FINANCE/PAYROLL', () => {
    const roles = ['manager']
    expect(ROLE_HOME(roles)).toBe('/')
    expect(canOps(roles)).toBe(true)
    expect(canReports(roles)).toBe(true)
    expect(canFinance(roles)).toBe(false)
    expect(canHr(roles)).toBe(true)
    expect(canPayroll(roles)).toBe(false)
    expect(canPos(roles)).toBe(true)
  })

  it('accountant: home is the income statement, REPORTS+FINANCE only', () => {
    const roles = ['accountant']
    expect(ROLE_HOME(roles)).toBe('/statements/income')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(true)
    expect(canFinance(roles)).toBe(true)
    expect(canHr(roles)).toBe(false)
    expect(canPayroll(roles)).toBe(false)
    expect(canPos(roles)).toBe(false)
  })

  it('hr: home is the People area, HR+PAYROLL only', () => {
    const roles = ['hr']
    expect(ROLE_HOME(roles)).toBe('/people')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(false)
    expect(canFinance(roles)).toBe(false)
    expect(canHr(roles)).toBe(true)
    expect(canPayroll(roles)).toBe(true)
    expect(canPos(roles)).toBe(false)
  })

  it('cashier: home is the POS, POS only', () => {
    const roles = ['cashier']
    expect(ROLE_HOME(roles)).toBe('/pos')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(false)
    expect(canFinance(roles)).toBe(false)
    expect(canHr(roles)).toBe(false)
    expect(canPayroll(roles)).toBe(false)
    expect(canPos(roles)).toBe(true)
  })

  it('waitress: home is the POS, POS only (same floor as cashier)', () => {
    const roles = ['waitress']
    expect(ROLE_HOME(roles)).toBe('/pos')
    expect(canPos(roles)).toBe(true)
    expect(canOps(roles)).toBe(false)
    expect(canHr(roles)).toBe(false)
  })

  it('chef: home is the kitchen display, POS only', () => {
    const roles = ['chef']
    expect(ROLE_HOME(roles)).toBe('/kitchen')
    expect(canPos(roles)).toBe(true)
    expect(canOps(roles)).toBe(false)
    expect(canFinance(roles)).toBe(false)
  })

  it('employee (the floor role): home is /me, no office capability at all', () => {
    const roles = ['employee']
    expect(ROLE_HOME(roles)).toBe('/me')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(false)
    expect(canFinance(roles)).toBe(false)
    expect(canHr(roles)).toBe(false)
    expect(canPayroll(roles)).toBe(false)
    expect(canPos(roles)).toBe(false)
  })
})

describe('rolePreset — multi-role login: union of capabilities, highest-privilege home', () => {
  it('hr + accountant: home follows accountant (ranks above hr); capabilities UNION', () => {
    const roles = ['hr', 'accountant']
    expect(ROLE_HOME(roles)).toBe('/statements/income')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(true) // from accountant
    expect(canFinance(roles)).toBe(true) // from accountant
    expect(canHr(roles)).toBe(true) // from hr
    expect(canPayroll(roles)).toBe(true) // from hr
    expect(canPos(roles)).toBe(false)
  })

  it('manager + hr: home follows manager (owner/manager rank highest); HR/OPS/REPORTS/PAYROLL true (from hr), FINANCE false', () => {
    const roles = ['manager', 'hr']
    expect(ROLE_HOME(roles)).toBe('/')
    expect(canOps(roles)).toBe(true)
    expect(canReports(roles)).toBe(true)
    expect(canHr(roles)).toBe(true)
    expect(canFinance(roles)).toBe(false)
    expect(canPayroll(roles)).toBe(true) // the hr half of the union grants payroll; manager alone would not
  })

  it('cashier + chef: home prefers chef (ranks above cashier in HOME_PRIORITY); both are POS', () => {
    const roles = ['cashier', 'chef']
    expect(ROLE_HOME(roles)).toBe('/kitchen')
    expect(canPos(roles)).toBe(true)
  })

  it('owner order-independence: owner present anywhere in the array still wins home + every capability', () => {
    const roles = ['employee', 'cashier', 'owner']
    expect(ROLE_HOME(roles)).toBe('/')
    expect(canOps(roles)).toBe(true)
    expect(canFinance(roles)).toBe(true)
    expect(canPayroll(roles)).toBe(true)
  })
})

describe('rolePreset — a floor/no-role login has no office capability', () => {
  it('an empty role list: home /me, nothing else', () => {
    const roles: string[] = []
    expect(ROLE_HOME(roles)).toBe('/me')
    expect(canOps(roles)).toBe(false)
    expect(canReports(roles)).toBe(false)
    expect(canFinance(roles)).toBe(false)
    expect(canHr(roles)).toBe(false)
    expect(canPayroll(roles)).toBe(false)
    expect(canPos(roles)).toBe(false)
  })
})
