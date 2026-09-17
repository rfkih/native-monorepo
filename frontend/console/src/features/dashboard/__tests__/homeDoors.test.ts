import { describe, expect, it } from 'vitest'
import { homeDoorFor } from '../lib/homeDoors'

describe('homeDoorFor — every figure on the home opens the page that explains it', () => {
  it('today, transactions and the average bill are the sales history', () => {
    for (const key of ['hero', 'txn', 'avg'] as const) {
      expect(homeDoorFor(key, { pnlOk: true })).toBe('/pos?sheet=history')
    }
  })

  it('open bills are the order switcher', () => {
    expect(homeDoorFor('bills', { pnlOk: false })).toBe('/pos?sheet=orders')
  })

  it('gross margin is Laba-rugi, only with the page grant', () => {
    expect(homeDoorFor('margin', { pnlOk: true })).toBe('/statements/income')
    expect(homeDoorFor('margin', { pnlOk: false })).toBeNull()
  })
})
