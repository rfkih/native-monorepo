import { describe, expect, it } from 'vitest'
import {
  assetRowError,
  assetRowNetBookValueMinor,
  assetRowStarted,
  buildAssetRegistrationBody,
  buildOpeningBalanceLines,
  computePlugMinor,
  newAssetRow,
  parseOpeningAmountInput,
  type AssetRowInput,
} from '../lines'

describe('parseOpeningAmountInput', () => {
  it('converts a whole-rupiah amount to minor units (IDR has zero minor-unit digits)', () => {
    expect(parseOpeningAmountInput('45000', 'IDR')).toBe(45_000)
  })

  it('rejects a grouping-separator on a zero-exponent currency instead of mis-scaling', () => {
    expect(parseOpeningAmountInput('45.000', 'IDR')).toBeNull()
    expect(parseOpeningAmountInput('45,000', 'IDR')).toBeNull()
  })

  it('converts a decimal USD amount to cents', () => {
    expect(parseOpeningAmountInput('12.5', 'USD')).toBe(1_250)
  })

  it('returns null for blank, non-numeric, or negative input', () => {
    expect(parseOpeningAmountInput('', 'IDR')).toBeNull()
    expect(parseOpeningAmountInput('   ', 'IDR')).toBeNull()
    expect(parseOpeningAmountInput('abc', 'IDR')).toBeNull()
    expect(parseOpeningAmountInput('-5000', 'IDR')).toBeNull()
  })

  it('rejects zero by default but accepts it when allowZero is set', () => {
    expect(parseOpeningAmountInput('0', 'IDR')).toBeNull()
    expect(parseOpeningAmountInput('0', 'IDR', { allowZero: true })).toBe(0)
  })
})

describe('buildOpeningBalanceLines', () => {
  it('includes only fields with a positive amount, mapped to the fixed account code + side', () => {
    const lines = buildOpeningBalanceLines(
      { '1900': '5000000', '3000': '4000000', '1000': '', '2000': '0' },
      'IDR',
    )
    expect(lines).toEqual([
      { accountCode: '1900', amountMinor: 5_000_000, side: 'DEBIT' },
      { accountCode: '3000', amountMinor: 4_000_000, side: 'CREDIT' },
    ])
  })

  it('returns an empty array when nothing was entered', () => {
    expect(buildOpeningBalanceLines({}, 'IDR')).toEqual([])
  })
})

function row(overrides: Partial<AssetRowInput> = {}): AssetRowInput {
  return { ...newAssetRow(), ...overrides }
}

describe('newAssetRow', () => {
  it('starts blank with a zero accumulated depreciation and a 60-month default life', () => {
    const r = newAssetRow()
    expect(r.name).toBe('')
    expect(r.cost).toBe('')
    expect(r.accumulated).toBe('0')
    expect(r.months).toBe('60')
    expect(r.key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('mints a fresh key per row', () => {
    expect(newAssetRow().key).not.toBe(newAssetRow().key)
  })
})

describe('assetRowStarted', () => {
  it('is false for a blank-cost row and true once anything is typed into cost', () => {
    expect(assetRowStarted(row())).toBe(false)
    expect(assetRowStarted(row({ cost: '10000000' }))).toBe(true)
    expect(assetRowStarted(row({ cost: 'abc' }))).toBe(true)
  })
})

describe('assetRowError', () => {
  it('is null for a not-yet-started row (nothing to validate yet)', () => {
    expect(assetRowError(row(), 'IDR')).toBeNull()
  })

  it('flags invalid cost content typed by the user (never silently discarded)', () => {
    expect(assetRowError(row({ cost: 'abc' }), 'IDR')).toBe('cost')
    expect(assetRowError(row({ cost: '0' }), 'IDR')).toBe('cost')
    expect(assetRowError(row({ cost: '-5' }), 'IDR')).toBe('cost')
  })

  it('flags a missing name once cost is entered', () => {
    expect(assetRowError(row({ cost: '10000000', name: '  ' }), 'IDR')).toBe('name')
  })

  it('flags accumulated depreciation exceeding cost (salvage is fixed at zero)', () => {
    expect(
      assetRowError(row({ cost: '10000000', name: 'Oven', accumulated: '12000000' }), 'IDR'),
    ).toBe('accumulated')
  })

  it('flags a remaining life outside [1, 600] months', () => {
    expect(assetRowError(row({ cost: '10000000', name: 'Oven', months: '0' }), 'IDR')).toBe(
      'months',
    )
    expect(assetRowError(row({ cost: '10000000', name: 'Oven', months: '601' }), 'IDR')).toBe(
      'months',
    )
  })

  it('is null for a fully valid started row', () => {
    expect(
      assetRowError(
        row({ cost: '10000000', name: 'Oven', accumulated: '2000000', months: '48' }),
        'IDR',
      ),
    ).toBeNull()
  })
})

describe('assetRowNetBookValueMinor', () => {
  it('is cost minus accumulated depreciation for a valid row', () => {
    expect(
      assetRowNetBookValueMinor(
        row({ cost: '10000000', name: 'Oven', accumulated: '3000000' }),
        'IDR',
      ),
    ).toBe(7_000_000)
  })

  it('is zero for a not-yet-started or invalid row (never distorts the live plug estimate)', () => {
    expect(assetRowNetBookValueMinor(row(), 'IDR')).toBe(0)
    expect(
      assetRowNetBookValueMinor(row({ cost: '10000000', name: '' }), 'IDR'),
    ).toBe(0)
  })
})

describe('buildAssetRegistrationBody', () => {
  it('builds the POST /api/v1/assets/opening body with salvage fixed at zero', () => {
    const body = buildAssetRegistrationBody(
      row({ cost: '10000000', name: 'Oven', accumulated: '2000000', months: '48' }),
      '2026-08-01',
      'IDR',
    )
    expect(body).toEqual({
      name: 'Oven',
      asOfDate: '2026-08-01',
      costMinor: 10_000_000,
      salvageMinor: 0,
      openingAccumulatedMinor: 2_000_000,
      remainingLifeMonths: 48,
      currency: 'IDR',
    })
  })

  it('returns null for a blank row', () => {
    expect(buildAssetRegistrationBody(row(), '2026-08-01', 'IDR')).toBeNull()
  })

  it('returns null for a started-but-invalid row', () => {
    expect(
      buildAssetRegistrationBody(row({ cost: '10000000', name: '' }), '2026-08-01', 'IDR'),
    ).toBeNull()
  })
})

describe('computePlugMinor', () => {
  it('is debits minus credits when nothing else is entered', () => {
    expect(computePlugMinor({ '1900': '5000000', '3000': '3000000' }, [], 'IDR')).toBe(2_000_000)
  })

  it('is negative (a debit to Opening Balance Equity) when credits exceed debits', () => {
    expect(computePlugMinor({ '1900': '5000000', '3000': '6000000' }, [], 'IDR')).toBe(-1_000_000)
  })

  it('folds in each started fixed asset row as an additional debit (its net book value)', () => {
    const rows = [row({ cost: '10000000', name: 'Oven', accumulated: '4000000' })]
    expect(computePlugMinor({ '3000': '6000000' }, rows, 'IDR')).toBe(6_000_000 - 6_000_000)
    // NBV = 10,000,000 - 4,000,000 = 6,000,000 debit; credits = 6,000,000 -> plug = 0
  })

  it('ignores a blank or invalid asset row', () => {
    expect(computePlugMinor({}, [row(), row({ cost: '10000000', name: '' })], 'IDR')).toBe(0)
  })

  it('is zero when nothing has been entered at all', () => {
    expect(computePlugMinor({}, [newAssetRow()], 'IDR')).toBe(0)
  })
})
