import { describe, expect, it } from 'vitest'
import { homeDoorFor } from '../lib/homeDoors'

const all = { tillOk: true, pnlOk: true }

describe('homeDoorFor — every figure on the home opens the page that explains it', () => {
  it('today, transactions and the average bill are the sales history', () => {
    for (const key of ['hero', 'txn', 'avg'] as const) {
      expect(homeDoorFor(key, all)).toBe('/pos?sheet=history')
    }
  })

  it('open bills are the order switcher', () => {
    expect(homeDoorFor('bills', { tillOk: true, pnlOk: false })).toBe('/pos?sheet=orders')
  })

  it('gross margin is Laba-rugi', () => {
    expect(homeDoorFor('margin', { tillOk: false, pnlOk: true })).toBe('/statements/income')
  })

  it('a door the router would bounce is a plain card instead', () => {
    // No till (pos grant revoked, tier without POS, or a service vertical whose till has no sheets).
    for (const key of ['hero', 'txn', 'avg', 'bills'] as const) {
      expect(homeDoorFor(key, { tillOk: false, pnlOk: true })).toBeNull()
    }
    // No reports grant.
    expect(homeDoorFor('margin', { tillOk: true, pnlOk: false })).toBeNull()
  })
})
