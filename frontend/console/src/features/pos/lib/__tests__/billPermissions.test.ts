import { describe, expect, it } from 'vitest'
import { canCancelBill, canRemoveBillLines, showCancelNeedsManager } from '../billPermissions'

describe('open-bill lockdown policy', () => {
  it('an owner/manager can cancel any unpaid bill', () => {
    expect(canCancelBill(true, 0, false)).toBe(true)
    expect(canCancelBill(true, 3, false)).toBe(true)
  })

  it('a cashier can cancel ONLY an empty bill (wrong table opened)', () => {
    expect(canCancelBill(false, 0, false)).toBe(true)
    expect(canCancelBill(false, 1, false)).toBe(false)
    expect(canCancelBill(false, 7, false)).toBe(false)
  })

  it('a bill with PAID lines is uncancellable for every role (server 409s anyway)', () => {
    expect(canCancelBill(true, 3, true)).toBe(false)
    expect(canCancelBill(false, 3, true)).toBe(false)
  })

  it('the needs-manager hint shows only for a role-blocked cashier on an unpaid bill', () => {
    expect(showCancelNeedsManager(false, 2, false)).toBe(true)
    expect(showCancelNeedsManager(false, 0, false)).toBe(false) // empty: button shows instead
    expect(showCancelNeedsManager(false, 2, true)).toBe(false) // paid: nothing shows for anyone
    expect(showCancelNeedsManager(true, 2, false)).toBe(false) // manager: button shows
  })

  it('removing/decrementing lines is owner/manager only', () => {
    expect(canRemoveBillLines(true)).toBe(true)
    expect(canRemoveBillLines(false)).toBe(false)
  })
})
