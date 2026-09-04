import { describe, expect, it } from 'vitest'
import { accountName, detailRows } from '../incomeDetail'
import type { IncomeLine } from '../api'

const line = (accountCode: string, netMinor: number): IncomeLine => ({
  accountCode,
  accountType: 'REVENUE',
  netMinor,
  currency: 'IDR',
})

const names = new Map([
  ['4100', 'Penjualan Makanan'],
  ['4200', 'Penjualan Minuman'],
])

describe('accountName', () => {
  it('maps a known code to its name', () => {
    expect(accountName(names, '4100')).toBe('Penjualan Makanan')
  })
  it('falls back to the raw code when unknown', () => {
    expect(accountName(names, '9999')).toBe('9999')
  })
})

describe('detailRows', () => {
  it('sorts largest amount first and computes each share of the total', () => {
    const rows = detailRows([line('4200', 300_000), line('4100', 700_000)], 1_000_000, names)
    expect(rows.map((r) => r.accountCode)).toEqual(['4100', '4200'])
    expect(rows[0]).toMatchObject({ name: 'Penjualan Makanan', amountMinor: 700_000, share: 0.7 })
    expect(rows[1].share).toBeCloseTo(0.3)
  })

  it('uses the code as the name when the account is not in the map', () => {
    const rows = detailRows([line('5500', 50_000)], 50_000, names)
    expect(rows[0].name).toBe('5500')
  })

  it('a zero total yields a zero share (no divide-by-zero)', () => {
    const rows = detailRows([line('4100', 0)], 0, names)
    expect(rows[0].share).toBe(0)
  })

  it('a contra (negative) line sorts to the bottom with a negative share', () => {
    const rows = detailRows([line('4100', 900_000), line('4900', -100_000)], 800_000, names)
    expect(rows.map((r) => r.accountCode)).toEqual(['4100', '4900'])
    expect(rows[1].share).toBeCloseTo(-0.125)
  })
})
