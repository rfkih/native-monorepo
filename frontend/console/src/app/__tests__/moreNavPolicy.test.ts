import { describe, expect, it } from 'vitest'
import { OWN_GROUPS, arrangeNavGroups, isOwnGroup, normalizeQuery } from '../moreNavPolicy'

// Headings are REAL here: the fixture used to omit them, which is exactly why the search bug
// (typing a group name returned nothing) slipped through a green suite.
const GROUPS = [
  { key: 'summary', heading: 'Overview', items: [{ label: 'Dashboard' }] },
  { key: 'reports', heading: 'Reports', items: [{ label: 'Income statement' }, { label: 'Balance sheet' }] },
  { key: 'receivables', heading: 'Piutang', items: [{ label: 'Invoices' }, { label: 'Customers' }, { label: 'AR ageing' }] },
  { key: 'payables', heading: 'Utang', items: [{ label: 'Bills' }, { label: 'Vendors' }] },
  { key: 'cashTax', heading: 'Kas & pajak', items: [{ label: 'Bank' }, { label: 'Tax' }] },
  { key: 'people', heading: 'Orang', items: [{ label: 'Employees' }, { label: 'Payroll' }] },
]

const keys = (gs: { key: string }[]) => gs.map((g) => g.key)

describe('arrangeNavGroups — at rest', () => {
  it("floats the accountant's own groups to the top, in the order given", () => {
    expect(keys(arrangeNavGroups(GROUPS, '', OWN_GROUPS.finance))).toEqual([
      'receivables',
      'payables',
      'cashTax',
      'reports',
      'summary',
      'people',
    ])
  })

  it("floats a manager's own groups instead", () => {
    expect(keys(arrangeNavGroups(GROUPS, '', OWN_GROUPS.ops))).toEqual([
      'summary',
      'reports',
      'people',
      'receivables',
      'payables',
      'cashTax',
    ])
  })

  it('keeps everything, and keeps the remainder in its original relative order', () => {
    const out = arrangeNavGroups(GROUPS, '', OWN_GROUPS.finance)
    expect(out).toHaveLength(GROUPS.length)
    expect(keys(out).slice(4)).toEqual(['summary', 'people'])
  })

  it('ignores an own-key that this login has no group for', () => {
    expect(keys(arrangeNavGroups(GROUPS, '', ['nope', 'people']))).toEqual([
      'people',
      'summary',
      'reports',
      'receivables',
      'payables',
      'cashTax',
    ])
  })

  it('orders by nothing when the persona has no groups of its own', () => {
    expect(keys(arrangeNavGroups(GROUPS, '', []))).toEqual(keys(GROUPS))
  })
})

describe('arrangeNavGroups — filtering', () => {
  it('matches item labels case-insensitively', () => {
    const out = arrangeNavGroups(GROUPS, 'BANK', OWN_GROUPS.finance)
    expect(keys(out)).toEqual(['cashTax'])
    expect(out[0].items).toEqual([{ label: 'Bank' }])
  })

  it('drops groups a filter leaves empty, so no heading sits above nothing', () => {
    const out = arrangeNavGroups(GROUPS, 'e', OWN_GROUPS.finance)
    expect(out.every((g) => g.items.length > 0)).toBe(true)
  })

  it('does NOT reorder while filtering — results stay in tree order', () => {
    expect(keys(arrangeNavGroups(GROUPS, 'a', OWN_GROUPS.finance))).toEqual(
      keys(arrangeNavGroups(GROUPS, 'a', [])),
    )
  })

  it('treats whitespace as no query at all', () => {
    expect(keys(arrangeNavGroups(GROUPS, '   ', OWN_GROUPS.ops))).toEqual(
      keys(arrangeNavGroups(GROUPS, '', OWN_GROUPS.ops)),
    )
  })

  it('returns nothing when a query matches nothing', () => {
    expect(arrangeNavGroups(GROUPS, 'zzzz', OWN_GROUPS.ops)).toEqual([])
  })

  it('leaves the source groups untouched', () => {
    const before = JSON.stringify(GROUPS)
    arrangeNavGroups(GROUPS, 'bank', OWN_GROUPS.finance)
    expect(JSON.stringify(GROUPS)).toBe(before)
  })
})

describe('isOwnGroup', () => {
  it('marks a persona group only at rest — a filtered list is not "your work"', () => {
    expect(isOwnGroup('receivables', '', OWN_GROUPS.finance)).toBe(true)
    expect(isOwnGroup('receivables', 'inv', OWN_GROUPS.finance)).toBe(false)
    expect(isOwnGroup('sales', '', OWN_GROUPS.finance)).toBe(false)
  })
})

describe('normalizeQuery', () => {
  it('trims and lowercases', () => {
    expect(normalizeQuery('  Bank  ')).toBe('bank')
  })
})

describe('arrangeNavGroups — heading matches', () => {
  it('finds a group by its own heading, and keeps all of its items', () => {
    const out = arrangeNavGroups(GROUPS, 'Piutang', OWN_GROUPS.finance)
    expect(keys(out)).toEqual(['receivables'])
    expect(out[0].items).toHaveLength(3)
  })

  it('matches a heading case-insensitively and on a partial word', () => {
    expect(keys(arrangeNavGroups(GROUPS, 'pajak', OWN_GROUPS.finance))).toEqual(['cashTax'])
    expect(keys(arrangeNavGroups(GROUPS, 'ORANG', OWN_GROUPS.ops))).toEqual(['people'])
  })

  it('still narrows a group to matching items when the heading does not match', () => {
    const out = arrangeNavGroups(GROUPS, 'bank', OWN_GROUPS.finance)
    expect(keys(out)).toEqual(['cashTax'])
    expect(out[0].items).toEqual([{ label: 'Bank' }])
  })

  it('returns every group a query reaches, by heading or by item', () => {
    // "or" hits the Reports and Orang headings, and the Vendors item under Utang.
    expect(keys(arrangeNavGroups(GROUPS, 'or', []))).toEqual(['reports', 'payables', 'people'])
  })
})
