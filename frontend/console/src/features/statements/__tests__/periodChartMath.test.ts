import { describe, expect, it } from 'vitest'
import {
  centreScroll,
  chartScale,
  chartTrack,
  columnCentre,
  columnGeometry,
  seriesFailures,
  seriesTone,
  seriesValues,
  trailingPeriods,
} from '../periodChartMath'

describe('trailingPeriods — the window the chart plots', () => {
  it('ends at the period, oldest first, across a year boundary', () => {
    expect(trailingPeriods('2026-02', 4)).toEqual(['2025-11', '2025-12', '2026-01', '2026-02'])
  })

  it('twelve months is the default budget the mockup counted', () => {
    const w = trailingPeriods('2026-09', 12)
    expect(w).toHaveLength(12)
    expect(w[0]).toBe('2025-10')
    expect(w[11]).toBe('2026-09')
  })

  it('a zero or negative window is empty, never a crash', () => {
    expect(trailingPeriods('2026-09', 0)).toEqual([])
    expect(trailingPeriods('2026-09', -3)).toEqual([])
  })
})

describe('chartScale — one axis, one baseline', () => {
  it('an all-positive series puts the baseline on the floor', () => {
    const s = chartScale([10, 40, 25], 100)
    expect(s.upperH).toBe(100)
    expect(s.range).toBe(40)
    expect(s.y(40)).toBe(0)
    expect(s.y(0)).toBe(100)
  })

  it('an all-negative series puts the baseline on the ceiling', () => {
    const s = chartScale([-10, -30], 100)
    expect(s.upperH).toBe(0)
    expect(s.y(0)).toBe(0)
    expect(s.y(-30)).toBe(100)
  })

  it('a mixed series splits the height by the larger excursion each side', () => {
    // +60 above, −40 below → 60% of the height is above the baseline.
    const s = chartScale([60, -40, 10], 100)
    expect(s.range).toBe(100)
    expect(s.upperH).toBe(60)
    expect(s.y(60)).toBe(0)
    expect(s.y(-40)).toBe(100)
    expect(s.y(0)).toBe(60)
  })

  it('gaps (204 months) do not take part, and an empty series gets a unit range', () => {
    expect(chartScale([null, 20, null], 100).range).toBe(20)
    expect(chartScale([null, null], 100).range).toBe(1)
    expect(chartScale([0, 0], 100).range).toBe(1)
  })
})

describe('chartScale — a series with nothing in it', () => {
  it('keeps the baseline on the floor for an all-zero window (a new company\'s first Income tab)', () => {
    const scale = chartScale([0, 0, 0], 100)
    expect(scale.upperH).toBe(100)
    expect(scale.y(0)).toBe(100)
    // Every column is a sliver standing on the baseline, not hanging from a top-edge hairline.
    expect(columnGeometry(0, scale)).toEqual({ h: 3, anchor: 'bottom', offset: 0 })
  })

  it('treats an all-gap window the same way', () => {
    expect(chartScale([null, null], 80).upperH).toBe(80)
  })
})

describe('columnGeometry — which way a column grows', () => {
  const s = chartScale([60, -40], 100)

  it('a profit month grows UP from the baseline', () => {
    expect(columnGeometry(30, s)).toEqual({ h: 30, anchor: 'bottom', offset: 40 })
  })

  it('a loss month hangs DOWN from the baseline', () => {
    expect(columnGeometry(-20, s)).toEqual({ h: 20, anchor: 'top', offset: 60 })
  })

  it('a near-zero month is still a sliver you can tap, never nothing', () => {
    expect(columnGeometry(0, s).h).toBe(3)
    expect(columnGeometry(0.4, s).h).toBe(3)
    expect(columnGeometry(0, s, 5).h).toBe(5)
  })
})

describe('seriesTone — colour means something or it means nothing', () => {
  it('signed series wear profit/loss by sign', () => {
    expect(seriesTone('net', 1)).toBe('profit')
    expect(seriesTone('net', -1)).toBe('loss')
    expect(seriesTone('cash', 0)).toBe('profit')
    expect(seriesTone('cash', -5)).toBe('loss')
  })

  it('magnitude series wear ink whatever the value', () => {
    expect(seriesTone('netWorth', 100)).toBe('ink')
    expect(seriesTone('netWorth', -100)).toBe('ink')
    expect(seriesTone('expense', 100)).toBe('ink')
  })
})

describe('chartTrack + centreScroll — the scrolling period control', () => {
  it('twelve 44px slots overflow a phone on purpose', () => {
    const t = chartTrack(12)
    expect(t.trackW).toBe(528 + 32)
    expect(t.colW).toBeCloseTo((528 - 6 * 11) / 12)
    // The inset is part of the track: the first centre sits one inset in, not at the edge.
    expect(columnCentre(0, t)).toBeCloseTo(16 + t.colW / 2)
  })

  it('a short window stretches to the minimum width instead of huddling left', () => {
    const t = chartTrack(6)
    expect(t.trackW).toBe(380 + 32)
    expect(t.colW).toBeCloseTo((380 - 6 * 5) / 6)
  })

  it('centres the selected column and clamps at both ends', () => {
    const t = chartTrack(12)
    const viewport = 380
    const last = 11
    // The last column cannot be centred — the track has run out — so it pins to the end.
    expect(centreScroll(last, t, viewport)).toBe(t.trackW - viewport)
    // The first column pins to the start.
    expect(centreScroll(0, t, viewport)).toBe(0)
    // A middle column lands with its centre at the viewport's centre.
    const mid = 6
    expect(centreScroll(mid, t, viewport)).toBeCloseTo(columnCentre(mid, t) - viewport / 2)
  })

  it('a viewport wider than the track never scrolls', () => {
    expect(centreScroll(3, chartTrack(6), 1000)).toBe(0)
  })
})

describe('seriesValues — gaps stay gaps', () => {
  it('maps present months through the picker and leaves 204 months null', () => {
    const points = [{ data: { netMinor: 5 } }, { data: null }, { data: { netMinor: -2 } }]
    expect(seriesValues(points, (d) => d.netMinor)).toEqual([5, null, -2])
  })
})

describe('seriesFailures — a failed month is not an empty month', () => {
  it('masks exactly the points that failed, and nothing that merely has no data', () => {
    const points = [
      { data: { netMinor: 5 }, failed: false },
      { data: null, failed: true },
      { data: null, failed: false },
      { data: null },
    ]
    expect(seriesFailures(points)).toEqual([false, true, false, false])
    // Both the failed and the empty month are gaps in the value series — the mask is the only
    // thing telling them apart.
    expect(seriesValues(points, (d) => d.netMinor)).toEqual([5, null, null, null])
  })
})
