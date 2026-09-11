import { describe, expect, it } from 'vitest'
import { formatMoney } from '@/lib/money'
import { baseUnitPrice, modifierLabel, modifierList } from '../lineLabels'

const IDR = 'IDR'
const ID = 'id-ID'
const rp = (minor: number) => formatMoney(minor, IDR, ID)

describe('modifierLabel / modifierList — the cashier sees what each add-on costs', () => {
  it('a paid add-on carries its per-unit price in parentheses', () => {
    expect(modifierLabel({ nameSnapshot: 'Telur', priceDeltaMinor: 2_000 }, IDR, ID)).toBe(
      `Telur (+${rp(2_000)})`,
    )
  })

  it('a free add-on is just its name', () => {
    expect(modifierLabel({ nameSnapshot: 'Less ice', priceDeltaMinor: 0 }, IDR, ID)).toBe('Less ice')
  })

  it('a deduction keeps its sign', () => {
    expect(modifierLabel({ nameSnapshot: 'No milk', priceDeltaMinor: -1_000 }, IDR, ID)).toBe(
      `No milk (${rp(-1_000)})`,
    )
  })

  it('the list joins them with commas, and is empty with no add-ons', () => {
    expect(
      modifierList(
        [
          { nameSnapshot: 'Telur', priceDeltaMinor: 2_000 },
          { nameSnapshot: 'Less ice', priceDeltaMinor: 0 },
        ],
        IDR,
        ID,
      ),
    ).toBe(`Telur (+${rp(2_000)}), Less ice`)
    expect(modifierList([], IDR, ID)).toBe('')
  })
})

describe('baseUnitPrice — the product price behind the price the cart was charged at', () => {
  it('subtracts the add-ons from the effective unit price', () => {
    expect(
      baseUnitPrice(27_000, [
        { nameSnapshot: 'Telur', priceDeltaMinor: 2_000 },
        { nameSnapshot: 'Less ice', priceDeltaMinor: 0 },
      ]),
    ).toBe(25_000)
  })

  it('is the effective price itself when there are no add-ons', () => {
    expect(baseUnitPrice(25_000, [])).toBe(25_000)
  })
})
