import { describe, expect, it } from 'vitest'
import { dockActions, dueLabelKey, peekLines } from '../dockLines'

const line = (name: string, paid = false) => ({ name, paid })

describe('peekLines — what the collapsed dock shows', () => {
  it('takes the three NEWEST lines, oldest-first', () => {
    const lines = [line('a'), line('b'), line('c'), line('d'), line('e')]
    expect(peekLines(lines).map((l) => l.name)).toEqual(['c', 'd', 'e'])
  })

  it('shows every line when the ticket is shorter than the window', () => {
    expect(peekLines([line('a'), line('b')]).map((l) => l.name)).toEqual(['a', 'b'])
    expect(peekLines([])).toEqual([])
  })

  it('drops PAID lines — a settled split check must not push the newest tap off the peek', () => {
    const lines = [line('a', true), line('b', true), line('c'), line('d')]
    expect(peekLines(lines).map((l) => l.name)).toEqual(['c', 'd'])
  })

  it('counts the window in UNPAID lines, not in rows', () => {
    const lines = [line('a'), line('b', true), line('c'), line('d', true), line('e'), line('f')]
    expect(peekLines(lines).map((l) => l.name)).toEqual(['c', 'e', 'f'])
  })

  it('an all-paid ticket peeks at nothing (the footer still shows the total)', () => {
    expect(peekLines([line('a', true), line('b', true)])).toEqual([])
  })

  it('honours a custom window, and a zero/negative one yields nothing', () => {
    const lines = [line('a'), line('b'), line('c')]
    expect(peekLines(lines, 1).map((l) => l.name)).toEqual(['c'])
    expect(peekLines(lines, 0)).toEqual([])
    expect(peekLines(lines, -1)).toEqual([])
  })
})

describe('dueLabelKey — what the dock calls its figure', () => {
  it('a plain ticket owes its total', () => {
    expect(dueLabelKey({ splitMode: false, hasPaidLines: false })).toBe('pos.total')
  })

  it('a partially-paid bill owes the REMAINDER, not the total', () => {
    expect(dueLabelKey({ splitMode: false, hasPaidLines: true })).toBe('posShell.dock.dueRemaining')
  })

  it('split mode wins over a partial payment — the figure is the ticked subset', () => {
    expect(dueLabelKey({ splitMode: true, hasPaidLines: false })).toBe('bills.splitSelected')
    expect(dueLabelKey({ splitMode: true, hasPaidLines: true })).toBe('bills.splitSelected')
  })
})

describe('dockActions — which chips the expanded dock offers', () => {
  it('walk-in, owner/manager: discount and member, no split on a one-line cart', () => {
    expect(dockActions({ isBill: false, unpaidCount: 1, canManualDiscount: true })).toEqual([
      'discount',
      'member',
    ])
  })

  it('split appears only on a BILL, and only once there are two unpaid lines to split', () => {
    expect(dockActions({ isBill: true, unpaidCount: 0, canManualDiscount: false })).not.toContain('split')
    expect(dockActions({ isBill: true, unpaidCount: 1, canManualDiscount: false })).not.toContain('split')
    expect(dockActions({ isBill: true, unpaidCount: 2, canManualDiscount: false })).toContain('split')
  })

  it('the walk-in cart never offers split — it has no server-side line ids to charge a subset of', () => {
    expect(dockActions({ isBill: false, unpaidCount: 9, canManualDiscount: true })).not.toContain(
      'split',
    )
  })

  it('a cashier gets no manual-discount chip (ADR 0026 §5)', () => {
    expect(dockActions({ isBill: false, unpaidCount: 3, canManualDiscount: false })).toEqual([
      'member',
    ])
  })

  it('the mockup’s Park chip is not offered — the header’s Incoming button already opens the tray', () => {
    expect(dockActions({ isBill: false, unpaidCount: 3, canManualDiscount: true })).not.toContain(
      'park',
    )
  })

  it('a bill offers attachments, never the cart-scoped discount/member chips', () => {
    expect(dockActions({ isBill: true, unpaidCount: 3, canManualDiscount: true })).toEqual([
      'split',
      'attachments',
    ])
  })

  it('an empty bill still offers its attachments', () => {
    expect(dockActions({ isBill: true, unpaidCount: 0, canManualDiscount: false })).toEqual([
      'attachments',
    ])
  })
})
