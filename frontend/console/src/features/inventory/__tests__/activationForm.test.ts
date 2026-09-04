import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import {
  activationErrorKey,
  canAdvanceActivationForm,
  defaultCutoverPeriod,
  isValidCutoverPeriod,
  parseOpeningInventoryValueInput,
} from '../activationForm'

describe('defaultCutoverPeriod', () => {
  it('formats the given date as YYYY-MM', () => {
    expect(defaultCutoverPeriod(new Date(2026, 0, 15))).toBe('2026-01')
    expect(defaultCutoverPeriod(new Date(2026, 10, 3))).toBe('2026-11')
  })
})

describe('isValidCutoverPeriod', () => {
  it('accepts a well-formed YYYY-MM', () => {
    expect(isValidCutoverPeriod('2026-08')).toBe(true)
    expect(isValidCutoverPeriod('2026-12')).toBe(true)
    expect(isValidCutoverPeriod('2026-01')).toBe(true)
  })

  it('rejects month 00 or 13, malformed shapes, and blank input', () => {
    expect(isValidCutoverPeriod('2026-00')).toBe(false)
    expect(isValidCutoverPeriod('2026-13')).toBe(false)
    expect(isValidCutoverPeriod('2026-8')).toBe(false)
    expect(isValidCutoverPeriod('08-2026')).toBe(false)
    expect(isValidCutoverPeriod('')).toBe(false)
  })
})

describe('parseOpeningInventoryValueInput', () => {
  it('converts a whole-rupiah amount to minor units (IDR has zero minor-unit digits)', () => {
    expect(parseOpeningInventoryValueInput('4500000', 'IDR')).toBe(4_500_000)
  })

  it('converts a decimal USD amount to cents', () => {
    expect(parseOpeningInventoryValueInput('450.50', 'USD')).toBe(45_050)
  })

  it('rejects a grouping-separator on a zero-exponent currency instead of mis-scaling', () => {
    expect(parseOpeningInventoryValueInput('45.000', 'IDR')).toBeNull()
    expect(parseOpeningInventoryValueInput('45,000', 'IDR')).toBeNull()
  })

  it('accepts zero — a company with nothing counted on hand yet still activates', () => {
    expect(parseOpeningInventoryValueInput('0', 'IDR')).toBe(0)
  })

  it('returns null for blank, non-numeric, or negative input', () => {
    expect(parseOpeningInventoryValueInput('', 'IDR')).toBeNull()
    expect(parseOpeningInventoryValueInput('   ', 'IDR')).toBeNull()
    expect(parseOpeningInventoryValueInput('abc', 'IDR')).toBeNull()
    expect(parseOpeningInventoryValueInput('-5000', 'IDR')).toBeNull()
  })
})

describe('canAdvanceActivationForm', () => {
  it('requires both a valid cutover period and a parseable opening value', () => {
    expect(canAdvanceActivationForm('2026-08', '0', 'IDR')).toBe(true)
    expect(canAdvanceActivationForm('2026-08', '5000000', 'IDR')).toBe(true)
    expect(canAdvanceActivationForm('2026-13', '0', 'IDR')).toBe(false)
    expect(canAdvanceActivationForm('2026-08', '', 'IDR')).toBe(false)
    expect(canAdvanceActivationForm('2026-08', '-1', 'IDR')).toBe(false)
  })
})

function problemError(status: number, type: string): ApiError {
  return new ApiError(status, { type, title: 'x', status, detail: 'x' }, 'x')
}

describe('activationErrorKey', () => {
  it('maps the 409 already-active problem', () => {
    expect(
      activationErrorKey(
        problemError(409, 'https://errors.nativeapp.id/inventory-method-already-active'),
      ),
    ).toBe('settings.inventoryMethod.error.alreadyActive')
  })

  it('maps the 422 sealed-period problem', () => {
    expect(
      activationErrorKey(
        problemError(422, 'https://errors.nativeapp.id/inventory-method-sealed-period'),
      ),
    ).toBe('settings.inventoryMethod.error.sealedPeriod')
  })

  it('falls back to generic for any other error, including a non-ApiError', () => {
    expect(activationErrorKey(problemError(400, 'https://errors.nativeapp.id/inventory-method-invalid-request'))).toBe(
      'settings.inventoryMethod.error.generic',
    )
    expect(activationErrorKey(new Error('network down'))).toBe('settings.inventoryMethod.error.generic')
    expect(activationErrorKey(null)).toBe('settings.inventoryMethod.error.generic')
  })
})
