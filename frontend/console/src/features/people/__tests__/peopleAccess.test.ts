import { describe, expect, it } from 'vitest'
import type { OrgUnit } from '@/features/org/api'
import {
  businessUnitsOf,
  childOutletsOf,
  defaultBusinessUnitId,
  visiblePeopleTabs,
} from '../peopleAccess'

function unit(partial: Partial<OrgUnit> & Pick<OrgUnit, 'id' | 'type'>): OrgUnit {
  return {
    id: partial.id,
    name: partial.name ?? partial.id,
    type: partial.type,
    vertical: partial.vertical ?? null,
    parentId: partial.parentId ?? null,
    active: partial.active ?? true,
  }
}

describe('visiblePeopleTabs — capability gating (mirrors rolePreset exactly)', () => {
  it('owner: all three tabs', () => {
    expect(visiblePeopleTabs(['owner'])).toEqual(['employees', 'attendance', 'payroll'])
  })

  it('hr: all three tabs (hr is in the PAYROLL bundle too)', () => {
    expect(visiblePeopleTabs(['hr'])).toEqual(['employees', 'attendance', 'payroll'])
  })

  it('manager: Employees + Attendance WITHOUT Payroll (manager is excluded from PAYROLL)', () => {
    expect(visiblePeopleTabs(['manager'])).toEqual(['employees', 'attendance'])
  })

  it('accountant alone: no tabs at all (not in the HR bundle)', () => {
    expect(visiblePeopleTabs(['accountant'])).toEqual([])
  })

  it('a floor role (cashier) or empty roles: no tabs', () => {
    expect(visiblePeopleTabs(['cashier'])).toEqual([])
    expect(visiblePeopleTabs([])).toEqual([])
  })

  it('hr + accountant (multi-role): union still grants every People tab from the hr half', () => {
    expect(visiblePeopleTabs(['hr', 'accountant'])).toEqual(['employees', 'attendance', 'payroll'])
  })
})

describe('businessUnitsOf / childOutletsOf', () => {
  const units: OrgUnit[] = [
    unit({ id: 'bu-1', type: 'BUSINESS_UNIT', name: 'Cafe Group' }),
    unit({ id: 'out-1', type: 'OUTLET', name: 'Cafe Sudirman', parentId: 'bu-1' }),
    unit({ id: 'out-2', type: 'OUTLET', name: 'Cafe Kemang', parentId: 'bu-1' }),
    unit({ id: 'bu-2', type: 'BUSINESS_UNIT', name: 'Laundromat' }),
    unit({ id: 'out-3', type: 'OUTLET', name: 'Laundromat Menteng', parentId: 'bu-2' }),
    unit({ id: 'team-1', type: 'TEAM', name: 'Ops team', parentId: 'bu-1' }),
  ]

  it('businessUnitsOf returns only BUSINESS_UNIT rows, in server order', () => {
    expect(businessUnitsOf(units).map((u) => u.id)).toEqual(['bu-1', 'bu-2'])
  })

  it('childOutletsOf returns only OUTLET rows whose parentId matches, excluding TEAM', () => {
    expect(childOutletsOf(units, 'bu-1').map((u) => u.id)).toEqual(['out-1', 'out-2'])
    expect(childOutletsOf(units, 'bu-2').map((u) => u.id)).toEqual(['out-3'])
  })

  it('childOutletsOf returns empty for a unit with no outlets', () => {
    expect(childOutletsOf(units, 'unknown')).toEqual([])
  })
})

describe('defaultBusinessUnitId', () => {
  const businessUnits: OrgUnit[] = [
    unit({ id: 'bu-1', type: 'BUSINESS_UNIT' }),
    unit({ id: 'bu-2', type: 'BUSINESS_UNIT' }),
  ]

  it('picks the session business id when it IS one of the business units', () => {
    expect(defaultBusinessUnitId(businessUnits, 'bu-2')).toBe('bu-2')
  })

  it('falls back to the first business unit when the session id is not a business unit here', () => {
    expect(defaultBusinessUnitId(businessUnits, 'some-outlet-id')).toBe('bu-1')
  })

  it('falls back to the first business unit when the session id is null', () => {
    expect(defaultBusinessUnitId(businessUnits, null)).toBe('bu-1')
  })

  it('returns null when there are no business units at all (fresh/mid-onboarding tenant)', () => {
    expect(defaultBusinessUnitId([], 'bu-1')).toBeNull()
    expect(defaultBusinessUnitId([], null)).toBeNull()
  })
})
