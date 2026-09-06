import { describe, expect, it } from 'vitest'
import { parseRecipeQty } from '../lib/recipeQty'

/**
 * A recipe is written in the ingredient's BASE unit. These pin the shape that change was made for:
 * an owner types the number a cook would say.
 */
describe('parseRecipeQty', () => {
  it('accepts the number a cook actually says', () => {
    // The whole point of the change: 60 g of meat is typed as 60, not as 0.06 kg. Before, a
    // kg-displayed ingredient forced the owner to divide by a thousand on every line — and in
    // production they stopped writing recipes for weight ingredients altogether.
    expect(parseRecipeQty('60')).toBe(60)
    expect(parseRecipeQty(' 15 ')).toBe(15)
    expect(parseRecipeQty('1')).toBe(1)
  })

  it('accepts a negative delta for a modifier option', () => {
    // "no cheese" removes 20 g. The sign is legal here; whether it is legal for THIS line is the
    // caller's rule (a base line must be positive, a delta must be non-zero).
    expect(parseRecipeQty('-20')).toBe(-20)
  })

  it('rejects a fraction for every ingredient, whatever unit is on screen', () => {
    // Base units are whole by definition — the server stores an INTEGER and 1 g is the finest
    // grain there is. This no longer depends on which unit happens to be displayed, which is what
    // made the old rule confusing: the same typed value was valid or not depending on the item.
    expect(parseRecipeQty('0.06')).toBeNull()
    expect(parseRecipeQty('1.5')).toBeNull()
    expect(parseRecipeQty('-2.5')).toBeNull()
  })

  it('rejects anything that is not a number', () => {
    expect(parseRecipeQty('')).toBeNull()
    expect(parseRecipeQty('   ')).toBeNull()
    expect(parseRecipeQty('abc')).toBeNull()
    expect(parseRecipeQty('12g')).toBeNull()
    expect(parseRecipeQty('1e999')).toBeNull() // Infinity
  })

  it('rejects a quantity the server column cannot hold', () => {
    // qty_per_portion is a 32-bit INTEGER. Accepting more here would surface as a 500 on save
    // rather than as a field error the owner can act on.
    expect(parseRecipeQty('2147483647')).toBe(2_147_483_647)
    expect(parseRecipeQty('2147483648')).toBeNull()
    expect(parseRecipeQty('-2147483648')).toBeNull()
  })

  it('treats zero as parseable so the caller can reject it with the right message', () => {
    // A base line rejects 0 as "must be positive" and a delta rejects it as "must be non-zero" —
    // two different messages, so the parse must not collapse them into a generic parse failure.
    expect(parseRecipeQty('0')).toBe(0)
  })
})
