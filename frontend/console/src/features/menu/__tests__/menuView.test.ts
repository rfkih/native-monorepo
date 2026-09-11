import { describe, expect, it } from 'vitest'
import {
  ALL_CHIP,
  categoryChips,
  cloneLines,
  filterItems,
  lineCostMinor,
  marginOf,
  parseMajorPrice,
  recipeTotal,
  stockMeta,
  summary,
  withLine,
  withQty,
  withoutLine,
  type MenuItemLike,
  type RecipeLineLike,
} from '../lib/menuView'

const item = (over: Partial<MenuItemLike> & { id: string }): MenuItemLike => ({
  name: 'Item',
  category: 'Makanan',
  priceMinor: 10_000,
  currency: 'IDR',
  available: true,
  stockQuantity: null,
  ...over,
})

const line = (over: Partial<RecipeLineLike> & { ingredientId: string }): RecipeLineLike => ({
  ingredientName: over.ingredientId,
  unit: 'g',
  modifierOptionId: null,
  qtyPerPortion: 100,
  unitCostMinor: 10,
  costCurrency: 'IDR',
  ...over,
})

const canon = (name: string) => name.trim().toLowerCase()
const display = (name: string) => name.trim()

describe('categoryChips — every category the menu actually uses, counted', () => {
  it('leads with "all", groups case-insensitively, keeps first-seen display name, sorts by name', () => {
    const chips = categoryChips(
      [
        item({ id: 'a', category: 'Minuman' }),
        item({ id: 'b', category: 'makanan' }),
        item({ id: 'c', category: 'Makanan' }),
        item({ id: 'd', category: '' }),
      ],
      canon,
      display,
      'Tanpa kategori',
      'id-ID',
    )
    expect(chips.map((c) => [c.key, c.label, c.count])).toEqual([
      [ALL_CHIP, '', 4],
      ['makanan', 'makanan', 2],
      ['minuman', 'Minuman', 1],
      ['', 'Tanpa kategori', 1],
    ])
  })
})

describe('filterItems — chip first, then the query over names AND ingredient names', () => {
  const items = [
    item({ id: 'a', name: 'Kopi Susu', category: 'Minuman' }),
    item({ id: 'b', name: 'Nasi Goreng', category: 'Makanan' }),
    item({ id: 'c', name: 'Es Teh', category: 'Minuman' }),
  ]
  const ingredientNames = new Map([
    ['a', ['Biji kopi', 'Susu UHT']],
    ['b', ['Beras', 'Telur ayam']],
  ])

  it('a chip narrows to its category', () => {
    expect(
      filterItems(items, 'minuman', '', ingredientNames, canon, 'id-ID').map((i) => i.id),
    ).toEqual(['a', 'c'])
  })

  it('the query matches an ingredient name, not only the item name', () => {
    expect(
      filterItems(items, ALL_CHIP, 'telur', ingredientNames, canon, 'id-ID').map((i) => i.id),
    ).toEqual(['b'])
    expect(
      filterItems(items, ALL_CHIP, 'SUSU', ingredientNames, canon, 'id-ID').map((i) => i.id),
    ).toEqual(['a'])
  })

  it('an item whose recipe is unknown still matches on its own name', () => {
    expect(
      filterItems(items, ALL_CHIP, 'es teh', new Map(), canon, 'id-ID').map((i) => i.id),
    ).toEqual(['c'])
  })
})

describe('summary — the line above the list', () => {
  it('counts active items, marked-unavailable items, and averages margin over items that have both HPP and a price', () => {
    const hpp = new Map([
      ['a', { unitHppMinor: 4_000, hppCurrency: 'IDR' }],
      ['b', { unitHppMinor: 8_000, hppCurrency: 'IDR' }],
      ['c', { unitHppMinor: null, hppCurrency: null }],
    ])
    const s = summary(
      [
        item({ id: 'a', priceMinor: 10_000 }),
        item({ id: 'b', priceMinor: 10_000, available: false }),
        item({ id: 'c', priceMinor: 10_000 }),
        item({ id: 'd', priceMinor: 10_000, available: false }),
      ],
      hpp,
    )
    expect(s.active).toBe(4)
    expect(s.soldOut).toBe(2)
    expect(s.avgMargin).toBeCloseTo(0.4) // (0.6 + 0.2) / 2 — c and d have no HPP
  })

  it('no costed item means no average, not zero', () => {
    expect(summary([item({ id: 'a' })], new Map()).avgMargin).toBeNull()
  })
})

describe('stockMeta — read-only stock in the meta line', () => {
  it('untracked / zero / low (≤ 5) / ok', () => {
    expect(stockMeta(item({ id: 'a', stockQuantity: null }))).toEqual({
      kind: 'untracked',
      qty: null,
    })
    expect(stockMeta(item({ id: 'a', stockQuantity: 0 }))).toEqual({ kind: 'zero', qty: 0 })
    expect(stockMeta(item({ id: 'a', stockQuantity: 5 }))).toEqual({ kind: 'low', qty: 5 })
    expect(stockMeta(item({ id: 'a', stockQuantity: 24 }))).toEqual({ kind: 'ok', qty: 24 })
  })
})

describe('marginOf — the row figure', () => {
  it('is null without an HPP row, with a currency mismatch, or a zero price', () => {
    expect(marginOf(item({ id: 'a' }), undefined)).toBeNull()
    expect(marginOf(item({ id: 'a' }), { unitHppMinor: 100, hppCurrency: 'USD' })).toBeNull()
    expect(
      marginOf(item({ id: 'a', priceMinor: 0 }), { unitHppMinor: 100, hppCurrency: 'IDR' }),
    ).toBeNull()
    expect(
      marginOf(item({ id: 'a', priceMinor: 10_000 }), { unitHppMinor: 4_000, hppCurrency: 'IDR' }),
    ).toBeCloseTo(0.6)
  })
})

describe('recipe lines — cost is derived, the PUT body is immutable', () => {
  it('a line costs qty × unit cost, null when uncosted', () => {
    expect(lineCostMinor(line({ ingredientId: 'x', qtyPerPortion: 200, unitCostMinor: 12 }))).toBe(
      2_400,
    )
    expect(lineCostMinor(line({ ingredientId: 'x', unitCostMinor: null }))).toBeNull()
  })

  it('the total sums base lines only and says when a line is uncosted', () => {
    const t = recipeTotal([
      line({ ingredientId: 'a', qtyPerPortion: 10, unitCostMinor: 100 }),
      line({ ingredientId: 'b', qtyPerPortion: 5, unitCostMinor: null }),
      line({ ingredientId: 'c', qtyPerPortion: 1, unitCostMinor: 999, modifierOptionId: 'opt' }),
    ])
    expect(t).toEqual({ minor: 1_000, partial: true, baseCount: 2 })
    expect(recipeTotal([])).toEqual({ minor: null, partial: false, baseCount: 0 })
  })

  it('clone / add / remove / requantify keep option deltas and never mutate the input', () => {
    const lines = [
      line({ ingredientId: 'a', qtyPerPortion: 10 }),
      line({ ingredientId: 'b', qtyPerPortion: 2, modifierOptionId: 'opt' }),
    ]
    expect(cloneLines(lines)).toEqual([
      { ingredientId: 'a', modifierOptionId: null, qtyPerPortion: 10 },
    ])
    expect(withLine(lines, 'c', 7)).toEqual([
      { ingredientId: 'a', modifierOptionId: null, qtyPerPortion: 10 },
      { ingredientId: 'b', modifierOptionId: 'opt', qtyPerPortion: 2 },
      { ingredientId: 'c', modifierOptionId: null, qtyPerPortion: 7 },
    ])
    expect(withLine(lines, 'a', 99)).toEqual([
      { ingredientId: 'a', modifierOptionId: null, qtyPerPortion: 99 },
      { ingredientId: 'b', modifierOptionId: 'opt', qtyPerPortion: 2 },
    ])
    expect(withoutLine(lines, 'a')).toEqual([
      { ingredientId: 'b', modifierOptionId: 'opt', qtyPerPortion: 2 },
    ])
    expect(withQty(lines, 'a', 15)[0].qtyPerPortion).toBe(15)
    expect(lines[0].qtyPerPortion).toBe(10)
  })
})

describe("parseMajorPrice — what the owner types, in the currency's minor units", () => {
  it('IDR has no fraction; USD has two; blanks and non-positive are null', () => {
    expect(parseMajorPrice('45000', 'IDR')).toBe(45_000)
    expect(parseMajorPrice('45000.4', 'IDR')).toBe(45_000)
    expect(parseMajorPrice('12.5', 'USD')).toBe(1_250)
    expect(parseMajorPrice('', 'IDR')).toBeNull()
    expect(parseMajorPrice('0', 'IDR')).toBeNull()
    expect(parseMajorPrice('abc', 'IDR')).toBeNull()
  })
})
