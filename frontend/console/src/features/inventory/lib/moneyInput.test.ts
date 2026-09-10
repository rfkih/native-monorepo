import { describe, expect, it } from 'vitest'
import { parseMoneyInput, sanitizeMoneyInput } from './moneyInput'

describe('parseMoneyInput', () => {
  it('reads whole rupiah and refuses a separator, which can only be grouping', () => {
    expect(parseMoneyInput('25000', 'IDR')).toBe(25000)
    expect(parseMoneyInput('0', 'IDR')).toBe(0)
    expect(parseMoneyInput('25.000', 'IDR')).toBeNull()
    expect(parseMoneyInput('1,000,000', 'IDR')).toBeNull()
  })

  it('reads a decimal in either separator for a currency with a fraction', () => {
    expect(parseMoneyInput('12.5', 'USD')).toBe(1250)
    expect(parseMoneyInput('12,5', 'USD')).toBe(1250)
    expect(parseMoneyInput('12', 'USD')).toBe(1200)
  })

  it('is null — never zero — for blank or garbage', () => {
    expect(parseMoneyInput('', 'IDR')).toBeNull()
    expect(parseMoneyInput('   ', 'IDR')).toBeNull()
    expect(parseMoneyInput('Rp 25.000', 'IDR')).toBeNull()
    expect(parseMoneyInput('.', 'USD')).toBeNull()
    expect(parseMoneyInput('-5', 'USD')).toBeNull()
    expect(parseMoneyInput('1.2.3', 'USD')).toBeNull()
  })
})

describe('sanitizeMoneyInput', () => {
  it('keeps digits only for a whole-unit currency', () => {
    expect(sanitizeMoneyInput('Rp 25.000', 'IDR')).toBe('25000')
    expect(sanitizeMoneyInput('1,000,000', 'IDR')).toBe('1000000')
  })

  it('keeps the first separator only for a fractional currency', () => {
    expect(sanitizeMoneyInput('12.50', 'USD')).toBe('12.50')
    expect(sanitizeMoneyInput('1,000.50', 'USD')).toBe('1,00050')
    expect(sanitizeMoneyInput('$12', 'USD')).toBe('12')
  })
})
