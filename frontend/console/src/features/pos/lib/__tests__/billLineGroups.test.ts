import { describe, expect, it } from 'vitest'
import { groupUnpaidLines } from '../billLineGroups'
import type { BillLineResponse } from '../../billsApi'

function line(over: Partial<BillLineResponse> & { id: string; menuItemId: string }): BillLineResponse {
  return {
    nameSnapshot: over.menuItemId,
    unitPriceMinor: 1000,
    modifierDeltaMinor: 0,
    qty: 1,
    lineTotalMinor: 1000,
    modifiers: [],
    paid: false,
    ...over,
  }
}

describe('groupUnpaidLines', () => {
  it('excludes paid lines', () => {
    const groups = groupUnpaidLines([
      line({ id: 'l1', menuItemId: 'm1', paid: true }),
      line({ id: 'l2', menuItemId: 'm1', paid: false }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].lineIds).toEqual(['l2'])
    expect(groups[0].qty).toBe(1)
  })

  it('groups same item + same modifiers; qty is the LINE COUNT and totals sum', () => {
    const groups = groupUnpaidLines([
      line({ id: 'l1', menuItemId: 'm1', lineTotalMinor: 1000 }),
      line({ id: 'l2', menuItemId: 'm1', lineTotalMinor: 1000 }),
      line({ id: 'l3', menuItemId: 'm1', lineTotalMinor: 1000 }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].qty).toBe(3) // count of lines, not sum of line.qty
    expect(groups[0].lineTotalMinor).toBe(3000)
    expect(groups[0].lineIds).toEqual(['l1', 'l2', 'l3']) // insertion order (decrement removes last)
  })

  it('counts lines even if an individual line has qty > 1 (stepper is line-granular)', () => {
    const groups = groupUnpaidLines([line({ id: 'l1', menuItemId: 'm1', qty: 5 })])
    expect(groups[0].qty).toBe(1) // ONE line ⇒ one "−" clears it, regardless of line.qty
  })

  it('discriminates by modifier set, order-independently', () => {
    const withMods = (id: string, opts: string[]) =>
      line({ id, menuItemId: 'm1', modifiers: opts.map((o) => ({ optionId: o, nameSnapshot: o, priceDeltaMinor: 0 })) })
    const groups = groupUnpaidLines([
      withMods('l1', ['a', 'b']),
      withMods('l2', ['b', 'a']), // same set, reversed → same group
      withMods('l3', ['a']), //       different set → its own group
    ])
    expect(groups).toHaveLength(2)
    expect(groups[0].lineIds).toEqual(['l1', 'l2'])
    expect(groups[1].lineIds).toEqual(['l3'])
  })

  it('separates different menu items', () => {
    const groups = groupUnpaidLines([
      line({ id: 'l1', menuItemId: 'm1' }),
      line({ id: 'l2', menuItemId: 'm2' }),
    ])
    expect(groups.map((g) => g.menuItemId)).toEqual(['m1', 'm2'])
  })

  it('preserves first-seen insertion order across interleaved items', () => {
    const groups = groupUnpaidLines([
      line({ id: 'l1', menuItemId: 'm2' }),
      line({ id: 'l2', menuItemId: 'm1' }),
      line({ id: 'l3', menuItemId: 'm2' }),
    ])
    expect(groups.map((g) => g.menuItemId)).toEqual(['m2', 'm1'])
    expect(groups[0].lineIds).toEqual(['l1', 'l3'])
  })
})
