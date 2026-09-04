import { describe, expect, it } from 'vitest'
import type { OrgUnit } from '@/features/org/api'
import {
  outletsOf,
  defaultOutletId,
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

describe('outletsOf — ADR 0070 flat tree', () => {
  // Every org unit is a top-level OUTLET now; parentId is always null on the wire.
  const units: OrgUnit[] = [
    unit({ id: 'out-1', type: 'OUTLET', name: 'Cafe Sudirman' }),
    unit({ id: 'out-2', type: 'OUTLET', name: 'Cafe Kemang' }),
    unit({ id: 'out-3', type: 'OUTLET', name: 'Laundromat Menteng' }),
  ]

  it('returns every outlet, in server order', () => {
    expect(outletsOf(units).map((u) => u.id)).toEqual(['out-1', 'out-2', 'out-3'])
  })
})

describe('defaultOutletId', () => {
  const outlets: OrgUnit[] = [
    unit({ id: 'out-1', type: 'OUTLET' }),
    unit({ id: 'out-2', type: 'OUTLET' }),
  ]

  it('picks the session outlet id when it IS one of the outlets', () => {
    expect(defaultOutletId(outlets, 'out-2')).toBe('out-2')
  })

  it('falls back to the first outlet when the session id is not a outlet here', () => {
    expect(defaultOutletId(outlets, 'some-outlet-id')).toBe('out-1')
  })

  it('falls back to the first outlet when the session id is null', () => {
    expect(defaultOutletId(outlets, null)).toBe('out-1')
  })

  it('returns null when there are no outlets at all (fresh/mid-onboarding tenant)', () => {
    expect(defaultOutletId([], 'out-1')).toBeNull()
    expect(defaultOutletId([], null)).toBeNull()
  })
})
