import { describe, expect, it } from 'vitest'
import { formatMoney, formatSignedMoney } from '../money'

describe('formatSignedMoney', () => {
  it('signs every non-zero amount in the currency and locale', () => {
    // IDR books zero minor digits (Money.java), so 34000 minor IS Rp 34.000.
    expect(formatSignedMoney(34000, 'IDR', 'id-ID').replace(/\u00a0/g, ' ')).toBe('+Rp 34.000')
    expect(formatSignedMoney(-165200, 'IDR', 'id-ID').replace(/\u00a0/g, ' ')).toBe('-Rp 165.200')
    expect(formatSignedMoney(1250, 'USD', 'en-US')).toBe('+$12.50')
  })

  it('leaves zero unsigned, exactly as formatMoney writes it', () => {
    expect(formatSignedMoney(0, 'IDR', 'id-ID')).toBe(formatMoney(0, 'IDR', 'id-ID'))
    expect(formatSignedMoney(0, 'USD', 'en-US')).toBe('$0.00')
  })
})
