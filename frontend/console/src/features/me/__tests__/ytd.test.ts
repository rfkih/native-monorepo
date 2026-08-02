import { describe, expect, it } from 'vitest'
import { sumYtd, type YtdDetailInput } from '../ytd'

/** A minimal EARNING line — gross is summed straight off `grossMinor`, so lines here only need to
 * exist for shape completeness in a couple of scenarios below. */
function earning(componentKey: string, amountMinor: number): YtdDetailInput['lines'][number] {
  return { componentKey, kind: 'EARNING', bearer: 'EMPLOYEE', amountMinor }
}

function pph21(amountMinor: number, bearer = 'EMPLOYEE'): YtdDetailInput['lines'][number] {
  return { componentKey: 'PPH21', kind: 'DEDUCTION', bearer, amountMinor }
}

describe('sumYtd', () => {
  it('returns zeros and a null currency for an empty run set', () => {
    expect(sumYtd([])).toEqual({ grossMinor: 0, pph21Minor: 0, currency: null })
  })

  it('sums gross and PPh21-withheld across multiple ACTIVE runs (the normal Jan..Nov case)', () => {
    const details: YtdDetailInput[] = [
      { grossMinor: 10_000_000, currency: 'IDR', lines: [earning('BASE', 10_000_000), pph21(262_100)] },
      { grossMinor: 10_000_000, currency: 'IDR', lines: [earning('BASE', 10_000_000), pph21(262_100)] },
    ]
    expect(sumYtd(details)).toEqual({
      grossMinor: 20_000_000,
      pph21Minor: 524_200,
      currency: 'IDR',
    })
  })

  it('a negative December true-up line REDUCES the year-to-date PPh21 total (Track P Phase P3 — this is the scenario P10 review C1 would have double-counted if the backend header list still leaked a superseded/non-POSTED run)', () => {
    const details: YtdDetailInput[] = [
      { grossMinor: 300_000_000, currency: 'IDR', lines: [pph21(84_840_000)] }, // Oct
      { grossMinor: 300_000_000, currency: 'IDR', lines: [pph21(84_840_000)] }, // Nov
      { grossMinor: 5_000_000, currency: 'IDR', lines: [pph21(-62_665_700)] }, // Dec refund
    ]
    const totals = sumYtd(details)
    expect(totals.pph21Minor).toBe(84_840_000 + 84_840_000 - 62_665_700)
    expect(totals.pph21Minor).toBeLessThan(84_840_000 + 84_840_000) // strictly reduced, not ignored
    expect(totals.grossMinor).toBe(605_000_000)
  })

  it('only counts a PPH21 line that is EMPLOYEE-borne AND a DEDUCTION (P10 review S3)', () => {
    const details: YtdDetailInput[] = [
      {
        grossMinor: 10_000_000,
        currency: 'IDR',
        lines: [
          pph21(262_100), // counted
          pph21(999_999_999, 'EMPLOYER'), // wrong bearer — excluded
          { componentKey: 'PPH21', kind: 'EARNING', bearer: 'EMPLOYEE', amountMinor: 999_999_999 }, // wrong kind — excluded
          earning('BASE', 10_000_000), // wrong component — excluded
        ],
      },
    ]
    expect(sumYtd(details).pph21Minor).toBe(262_100)
  })

  it('takes the currency from the first run that carries one (every run is the same base currency in practice)', () => {
    const details: YtdDetailInput[] = [
      { grossMinor: 1_000_000, currency: 'IDR', lines: [] },
      { grossMinor: 2_000_000, currency: 'IDR', lines: [] },
    ]
    expect(sumYtd(details).currency).toBe('IDR')
  })
})
