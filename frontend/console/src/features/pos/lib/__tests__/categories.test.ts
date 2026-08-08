/**
 * categories.test.ts — locks in the two recently-fixed category semantics (redesign P1):
 * null=All default resolution (commit 7277394) and case-insensitive name adoption (f050be1).
 * BillDetail.tsx carried a stale case-SENSITIVE fork of this logic — these tests pin the single
 * shared source so it can never fork again.
 */
import { describe, expect, it } from 'vitest'
import { deriveCategories, visibleMenuItems } from '../categories'
import type { CategoryResponse, MenuItem } from '../../api'

function mi(partial: Partial<MenuItem>): MenuItem {
  return {
    id: 'item-1',
    name: 'Item',
    category: '',
    categoryId: null,
    priceMinor: 1000,
    currency: 'IDR',
    available: true,
    stockQuantity: null,
    modifierGroups: [],
    ...partial,
  } as MenuItem
}

function cat(partial: Partial<CategoryResponse>): CategoryResponse {
  return { id: 'cat-1', name: 'Mains', active: true, displayOrder: 1, ...partial } as CategoryResponse
}

describe('deriveCategories', () => {
  it('maps backend categories with a language-canonical legacyKey (templates get tpl:*)', () => {
    const result = deriveCategories([], [cat({ id: 'c1', name: 'Coffee & Tea' })])
    expect(result).toEqual([{ id: 'c1', name: 'Coffee & Tea', legacyKey: 'tpl:coffeeTea' }])
  })

  it('appends an uncovered free-text category with a LOWERCASED legacyKey (f050be1)', () => {
    const result = deriveCategories([mi({ category: 'DRINKS' })], [])
    expect(result).toEqual([{ id: 'DRINKS', name: 'DRINKS', legacyKey: 'drinks' }])
  })

  it('does NOT append a free-text category covered by a managed one, case-insensitively', () => {
    const result = deriveCategories(
      [mi({ category: 'drinks' }), mi({ id: 'i2', category: 'DRINKS' })],
      [cat({ id: 'c1', name: 'Drinks' })],
    )
    // Both free-text spellings are covered by the managed 'Drinks' row case-insensitively —
    // only the managed row remains.
    expect(result).toEqual([{ id: 'c1', name: 'Drinks', legacyKey: 'drinks' }])
  })

  it('ignores the free-text bridge for items already linked to a managed category', () => {
    const result = deriveCategories(
      [mi({ categoryId: 'c1', category: 'Anything' })],
      [cat({ id: 'c1', name: 'Mains' })],
    )
    expect(result).toEqual([{ id: 'c1', name: 'Mains', legacyKey: 'mains' }])
  })

  it('keeps a free-text category whose categoryId is stale (unknown backend id)', () => {
    const result = deriveCategories([mi({ categoryId: 'gone', category: 'Snacks' })], [])
    expect(result).toEqual([{ id: 'Snacks', name: 'Snacks', legacyKey: 'tpl:snacks' }])
  })
})


describe('language-canonical grouping (categoryCanon)', () => {
  it('merges the en and id names of one template into ONE category', () => {
    const result = deriveCategories(
      [mi({ id: 'i1', category: 'Main Course' }), mi({ id: 'i2', category: 'Menu Utama' })],
      [],
    )
    expect(result).toHaveLength(1)
    expect(result[0].legacyKey).toBe('tpl:mains')
  })

  it('a managed category covers the OTHER language of the same template', () => {
    const result = deriveCategories(
      [mi({ category: 'Menu Utama' })],
      [cat({ id: 'c1', name: 'Main Course' })],
    )
    expect(result).toEqual([{ id: 'c1', name: 'Main Course', legacyKey: 'tpl:mains' }])
  })

  it('the category tab adopts items stored under either language', () => {
    const items = [mi({ id: 'i1', category: 'Main Course' }), mi({ id: 'i2', category: 'Menu Utama' })]
    const cats = deriveCategories(items, [])
    const visible = visibleMenuItems(items, cats, cats[0].id, '')
    expect(visible.map((i) => i.id)).toEqual(['i1', 'i2'])
  })
})

describe('visibleMenuItems', () => {
  const items = [
    mi({ id: 'i1', name: 'Nasi Goreng', category: 'Mains' }),
    mi({ id: 'i2', name: 'Es Teh', category: 'DRINKS' }),
    mi({ id: 'i3', name: 'Linked Latte', category: '', categoryId: 'c-drinks' }),
  ]
  const cats = deriveCategories(items, [cat({ id: 'c-drinks', name: 'Drinks' })])

  it("shows everything on the All tab (resolvedCategoryId '' matches no category — 7277394)", () => {
    expect(visibleMenuItems(items, cats, '', '')).toHaveLength(3)
  })

  it('search wins over the category tab and matches case-insensitively', () => {
    expect(visibleMenuItems(items, cats, 'c-drinks', 'nasi')).toEqual([items[0]])
  })

  it('a managed category adopts free-text items by name CASE-INSENSITIVELY (f050be1)', () => {
    const visible = visibleMenuItems(items, cats, 'c-drinks', '')
    expect(visible.map((i) => i.id).sort()).toEqual(['i2', 'i3'])
  })

  it('a legacy free-text tab matches its own items', () => {
    const visible = visibleMenuItems(items, cats, 'Mains', '')
    expect(visible.map((i) => i.id)).toEqual(['i1'])
  })

  it('shows everything when there are no categories at all', () => {
    expect(visibleMenuItems(items, [], 'anything', '')).toHaveLength(3)
  })
})
