import { describe, expect, it } from 'vitest'
import { sheetFromParam } from '../sheetParam'

describe('sheetFromParam — the till opens only the two sheets it names', () => {
  it('knows the sales history and the order switcher', () => {
    expect(sheetFromParam('history')).toBe('history')
    expect(sheetFromParam('orders')).toBe('orders')
  })

  it('opens nothing for anything else', () => {
    for (const v of [null, undefined, '', 'History', 'bills', 'orders ', '<script>']) {
      expect(sheetFromParam(v)).toBeNull()
    }
  })
})
