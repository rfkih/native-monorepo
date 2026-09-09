import { describe, expect, it } from 'vitest'
import { officeMiddleTabs, type OfficeTabKey } from '../tabBarPolicy'

/** Every candidate ineligible unless the case says otherwise. */
function eligible(...on: OfficeTabKey[]): Record<OfficeTabKey, boolean> {
  return {
    reports: on.includes('reports'),
    team: on.includes('team'),
    ar: on.includes('ar'),
    ap: on.includes('ap'),
  }
}

describe('officeMiddleTabs', () => {
  it('gives a manager Reports + Team — the bar they already had', () => {
    expect(officeMiddleTabs(eligible('reports', 'team', 'ar', 'ap'), '/')).toEqual(['reports', 'team'])
  })

  it('fills BOTH empty slots for an accountant, whose home IS the reports page', () => {
    // canReports is true for an accountant, but the reports tab would point at the page they are
    // already on; team needs ops. Before the backfill this returned nothing and the bar rendered
    // two tabs across four columns.
    expect(officeMiddleTabs(eligible('reports', 'ar', 'ap'), '/statements/income')).toEqual(['ar', 'ap'])
  })

  it('never offers a tab that points at the home tab', () => {
    expect(officeMiddleTabs(eligible('reports', 'team'), '/team')).toEqual(['reports'])
    expect(officeMiddleTabs(eligible('ar', 'ap'), '/ar/aging')).toEqual(['ap'])
  })

  it('caps at the two middle slots, in candidate order', () => {
    const tabs = officeMiddleTabs(eligible('reports', 'team', 'ar', 'ap'), '/nowhere')
    expect(tabs).toHaveLength(2)
    expect(tabs).toEqual(['reports', 'team'])
  })

  it('backfills only what the login can actually open', () => {
    // Finance capability absent → no ageing tabs to fall through to, and the bar stays short
    // rather than growing a tab that would bounce.
    expect(officeMiddleTabs(eligible('reports'), '/statements/income')).toEqual([])
  })

  it('returns nothing when every candidate is ineligible', () => {
    expect(officeMiddleTabs(eligible(), '/')).toEqual([])
  })
})
