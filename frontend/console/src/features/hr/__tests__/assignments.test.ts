import { describe, expect, it } from 'vitest'
import { isActiveAssignment } from '../assignments'

describe('isActiveAssignment', () => {
  it('true — an open-ended assignment (effectiveTo is still the OPEN_ENDED sentinel)', () => {
    expect(isActiveAssignment({ assignmentId: 'a1', effectiveTo: '9999-12-31' })).toBe(true)
  })

  it('false — an assignment already given a real end date via Akhiri, even one still in the future', () => {
    expect(isActiveAssignment({ assignmentId: 'a1', effectiveTo: '2026-08-20' })).toBe(false)
  })

  it('false — an assignment ended in the past', () => {
    expect(isActiveAssignment({ assignmentId: 'a1', effectiveTo: '2020-01-01' })).toBe(false)
  })

  it('false — no assignment at all (the LEFT JOIN null row for an unassigned employee)', () => {
    expect(isActiveAssignment({ assignmentId: null, effectiveTo: null })).toBe(false)
  })

  it('false — assignmentId null wins even if effectiveTo happens to equal the sentinel', () => {
    expect(isActiveAssignment({ assignmentId: null, effectiveTo: '9999-12-31' })).toBe(false)
  })

  it('the real bug report — 3 rows, only the 2 open-ended ones are active', () => {
    const rows = [
      { assignmentId: 'a1', effectiveTo: '9999-12-31' },
      { assignmentId: 'a2', effectiveTo: '9999-12-31' },
      { assignmentId: 'a3', effectiveTo: '2026-08-15' },
    ]
    expect(rows.filter(isActiveAssignment)).toEqual([
      { assignmentId: 'a1', effectiveTo: '9999-12-31' },
      { assignmentId: 'a2', effectiveTo: '9999-12-31' },
    ])
  })
})
