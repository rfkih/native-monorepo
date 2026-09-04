import { describe, expect, it } from 'vitest'
import { invalidCompanyFields, symbolOf } from '../companyForm'

describe('invalidCompanyFields — the shared Company-step required rule', () => {
  it('flags both fields when blank (incl. whitespace-only)', () => {
    expect(invalidCompanyFields({ companyName: '' })).toEqual([
      'companyName',
      
    ])
  })

  it('flags only the blank field', () => {
    expect(invalidCompanyFields({ companyName: 'Acme' })).toEqual([
      
    ])
    expect(invalidCompanyFields({ companyName: '' })).toEqual([
      'companyName',
    ])
  })

  it('accepts when both are present', () => {
    expect(invalidCompanyFields({ companyName: 'Acme' })).toEqual([])
  })
})

describe('symbolOf — narrow currency symbol for the derived-currency preview', () => {
  it('resolves known currencies', () => {
    expect(symbolOf('USD', 'en')).toBe('$')
    // IDR narrow symbol is "Rp" across the supported locales.
    expect(symbolOf('IDR', 'id')).toBe('Rp')
  })

  it('falls back to the ISO code when the currency is unknown', () => {
    expect(symbolOf('ZZZ', 'en')).toBe('ZZZ')
  })
})
