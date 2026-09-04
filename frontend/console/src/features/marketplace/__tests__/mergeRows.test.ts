import { describe, expect, it } from 'vitest'
import { mergeMarketplaceRows, totalMarketplaceRow } from '../mergeRows'
import type { ChannelSalesSummary, ChannelSettlementSummary } from '../marketplaceApi'
import type { PlatformOutstanding } from '@/features/platform/platformApi'

describe('mergeMarketplaceRows', () => {
  it('joins a channel present in all three sources', () => {
    const sales: ChannelSalesSummary[] = [
      { channelCode: 'GOFOOD', grossSalesMinor: 100_000, transactionCount: 5, currency: 'IDR' },
    ]
    const settlements: ChannelSettlementSummary[] = [
      {
        channelCode: 'GOFOOD',
        settledGrossMinor: 100_000,
        feeMinor: 20_000,
        netMinor: 80_000,
        settlementCount: 1,
        currency: 'IDR',
      },
    ]
    const outstanding: PlatformOutstanding[] = [
      { channelCode: 'GOFOOD', currency: 'IDR', outstandingMinor: 5_000 },
    ]

    const rows = mergeMarketplaceRows(sales, settlements, outstanding, 'IDR')

    expect(rows).toEqual([
      {
        channelCode: 'GOFOOD',
        currency: 'IDR',
        grossSalesMinor: 100_000,
        transactionCount: 5,
        feeMinor: 20_000,
        netSettledMinor: 80_000,
        settlementCount: 1,
        outstandingMinor: 5_000,
      },
    ])
  })

  it('keeps a channel present in sales only, with zeroed settlement/outstanding columns', () => {
    const sales: ChannelSalesSummary[] = [
      { channelCode: 'GRABFOOD', grossSalesMinor: 50_000, transactionCount: 2, currency: 'IDR' },
    ]

    const rows = mergeMarketplaceRows(sales, [], [], 'IDR')

    expect(rows).toEqual([
      {
        channelCode: 'GRABFOOD',
        currency: 'IDR',
        grossSalesMinor: 50_000,
        transactionCount: 2,
        feeMinor: 0,
        netSettledMinor: 0,
        settlementCount: 0,
        outstandingMinor: 0,
      },
    ])
  })

  it('keeps a channel present in outstanding only (a balance carried with no activity this period)', () => {
    const outstanding: PlatformOutstanding[] = [
      { channelCode: 'SHOPEEFOOD', currency: 'IDR', outstandingMinor: -3_000 },
    ]

    const rows = mergeMarketplaceRows([], [], outstanding, 'IDR')

    expect(rows).toEqual([
      {
        channelCode: 'SHOPEEFOOD',
        currency: 'IDR',
        grossSalesMinor: 0,
        transactionCount: 0,
        feeMinor: 0,
        netSettledMinor: 0,
        settlementCount: 0,
        outstandingMinor: -3_000,
      },
    ])
  })

  it('falls back to the given currency when a channel has none of its own', () => {
    // Defensive floor only (see the function's doc) — exercised via a synthetic empty-currency row.
    const sales: ChannelSalesSummary[] = [
      { channelCode: 'GOFOOD', grossSalesMinor: 1_000, transactionCount: 1, currency: '' },
    ]
    const rows = mergeMarketplaceRows(sales, [], [], 'USD')
    expect(rows[0].currency).toBe('USD')
  })

  it('sorts the joined rows by channelCode', () => {
    const outstanding: PlatformOutstanding[] = [
      { channelCode: 'SHOPEEFOOD', currency: 'IDR', outstandingMinor: 1 },
      { channelCode: 'GOFOOD', currency: 'IDR', outstandingMinor: 2 },
      { channelCode: 'GRABFOOD', currency: 'IDR', outstandingMinor: 3 },
    ]
    const rows = mergeMarketplaceRows([], [], outstanding, 'IDR')
    expect(rows.map((r) => r.channelCode)).toEqual(['GOFOOD', 'GRABFOOD', 'SHOPEEFOOD'])
  })

  it('sums duplicate channelCode entries within a single source rather than overwriting', () => {
    const sales: ChannelSalesSummary[] = [
      { channelCode: 'GOFOOD', grossSalesMinor: 10_000, transactionCount: 1, currency: 'IDR' },
      { channelCode: 'GOFOOD', grossSalesMinor: 5_000, transactionCount: 1, currency: 'IDR' },
    ]
    const rows = mergeMarketplaceRows(sales, [], [], 'IDR')
    expect(rows[0].grossSalesMinor).toBe(15_000)
    expect(rows[0].transactionCount).toBe(2)
  })

  it('returns an empty array for three empty sources', () => {
    expect(mergeMarketplaceRows([], [], [], 'IDR')).toEqual([])
  })
})

describe('totalMarketplaceRow', () => {
  it('sums every column across rows', () => {
    const rows = mergeMarketplaceRows(
      [
        { channelCode: 'GOFOOD', grossSalesMinor: 100_000, transactionCount: 5, currency: 'IDR' },
        { channelCode: 'GRABFOOD', grossSalesMinor: 50_000, transactionCount: 3, currency: 'IDR' },
      ],
      [
        {
          channelCode: 'GOFOOD',
          settledGrossMinor: 100_000,
          feeMinor: 20_000,
          netMinor: 80_000,
          settlementCount: 1,
          currency: 'IDR',
        },
      ],
      [{ channelCode: 'GRABFOOD', currency: 'IDR', outstandingMinor: -2_000 }],
      'IDR',
    )

    expect(totalMarketplaceRow(rows, 'IDR')).toEqual({
      currency: 'IDR',
      grossSalesMinor: 150_000,
      transactionCount: 8,
      feeMinor: 20_000,
      netSettledMinor: 80_000,
      settlementCount: 1,
      outstandingMinor: -2_000,
    })
  })

  it('returns an all-zero totals row at the fallback currency when there are no rows', () => {
    expect(totalMarketplaceRow([], 'USD')).toEqual({
      currency: 'USD',
      grossSalesMinor: 0,
      transactionCount: 0,
      feeMinor: 0,
      netSettledMinor: 0,
      settlementCount: 0,
      outstandingMinor: 0,
    })
  })
})
