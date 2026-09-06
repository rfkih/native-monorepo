import { describe, expect, it } from 'vitest'
import { en } from '@/i18n/locales/en'
import { id } from '@/i18n/locales/id'
import {
  jakartaMonthBounds,
  SEVERITY_ORDER,
  type LeakSignalType,
} from '../salesIntegrityApi'

/**
 * The leak report's window is the one piece of client logic that can silently produce a WRONG
 * report rather than a broken one: get the bounds off by a few hours and the page still renders,
 * still looks authoritative, and quietly covers the wrong days.
 */
describe('jakartaMonthBounds', () => {
  it('anchors the window to Jakarta midnight, not the browser or UTC', () => {
    const { from, to } = jakartaMonthBounds('2026-09')

    // Jakarta is a fixed UTC+7 with no DST, so the 1st at 00:00 WIB is 17:00 UTC on 31 August.
    // Using UTC midnight instead would push the whole window seven hours late, silently dropping
    // the last evening of the month out of its own report and pulling in the previous one's.
    expect(from).toBe('2026-08-31T17:00:00.000Z')
    expect(to).toBe('2026-09-30T17:00:00.000Z')
  })

  it('rolls the year correctly at the December boundary', () => {
    const { from, to } = jakartaMonthBounds('2026-12')

    expect(from).toBe('2026-11-30T17:00:00.000Z')
    expect(to).toBe('2026-12-31T17:00:00.000Z')
  })

  it('handles January, where the window starts in the previous year', () => {
    const { from, to } = jakartaMonthBounds('2027-01')

    expect(from).toBe('2026-12-31T17:00:00.000Z')
    expect(to).toBe('2027-01-31T17:00:00.000Z')
  })

  it('produces a half-open window whose end is the next window start', () => {
    // The backend treats the window as [from, to), so consecutive months must abut exactly —
    // a gap would drop a day's evidence and an overlap would count it twice.
    expect(jakartaMonthBounds('2026-09').to).toBe(jakartaMonthBounds('2026-10').from)
  })
})

describe('SEVERITY_ORDER', () => {
  it('sorts the signals worth acting on first', () => {
    const sorted = (['LOW', 'HIGH', 'MEDIUM'] as const)
      .slice()
      .sort((a, b) => SEVERITY_ORDER[a] - SEVERITY_ORDER[b])

    expect(sorted).toEqual(['HIGH', 'MEDIUM', 'LOW'])
  })
})

/**
 * Every signal the backend can raise must have copy in BOTH locales. Without this, adding a
 * detector server-side and forgetting the Indonesian block ships an owner a card titled
 * `EXACT_ZERO_CLOSE_RUN` — the failure is silent in review and glaring in production.
 */
describe('signal copy parity', () => {
  const SIGNAL_TYPES: LeakSignalType[] = [
    'MISSING_TRACKED_ITEMS',
    'INGREDIENT_SHORTFALL',
    'DARK_HOUR',
    'SALES_OUTSIDE_SESSION',
    'TRADING_DAY_WITHOUT_CLOSE',
    'PERSISTENT_CASH_SHORT',
    'UNEXPLAINED_CASH_OVER',
    'HIGH_VOID_RATE',
    'HIGH_REFUND_RATE',
    'HIGH_DISCOUNT_RATE',
    'CANCELLED_BILLS_WITH_ITEMS',
    'CASH_TENDER_SKEW',
    'SESSION_LEFT_OPEN',
    'EXACT_ZERO_CLOSE_RUN',
  ]

  it.each([
    ['en', en.salesIntegrity],
    ['id', id.salesIntegrity],
  ])('%s has a title, body and advice for every signal', (_lang, block) => {
    for (const type of SIGNAL_TYPES) {
      const copy = (block.signal as Record<string, { title: string; body: string; advice: string }>)[
        type
      ]
      expect(copy, `missing copy for ${type}`).toBeDefined()
      expect(copy.title.length).toBeGreaterThan(0)
      expect(copy.body.length).toBeGreaterThan(0)
      expect(copy.advice.length).toBeGreaterThan(0)
    }
  })

  // The signals that fill `quantity`. The other three (persistent short, unexplained over, session
  // left open) send null and carry their money in `valueMinor` instead.
  const SIGNALS_WITH_QUANTITY: LeakSignalType[] = [
    'MISSING_TRACKED_ITEMS',
    'INGREDIENT_SHORTFALL',
    'DARK_HOUR',
    'SALES_OUTSIDE_SESSION',
    'TRADING_DAY_WITHOUT_CLOSE',
    'EXACT_ZERO_CLOSE_RUN',
    'CANCELLED_BILLS_WITH_ITEMS',
    'HIGH_VOID_RATE',
    'HIGH_REFUND_RATE',
    'HIGH_DISCOUNT_RATE',
    'CASH_TENDER_SKEW',
  ]

  it.each([
    ['en', en.salesIntegrity],
    ['id', id.salesIntegrity],
  ])('%s phrases every quantity per signal, never one generic label', (_lang, block) => {
    // A single shared "{{qty}} missing" was wrong for eight of these: five consecutive CLEAN closes
    // read as "5 missing", and sales that WERE recorded read as "45 missing" — on a page an owner
    // uses to decide whether a member of staff is stealing.
    for (const type of SIGNALS_WITH_QUANTITY) {
      const copy = (block.signal as Record<string, { qty?: string }>)[type]
      expect(copy?.qty, `missing qty copy for ${type}`).toBeDefined()
      expect(copy.qty).toContain('{{qty}}')
    }
    // Only the ingredient shortfall carries a unit; the rest count something the signal names.
    const shortfall = (block.signal as Record<string, { qty: string }>).INGREDIENT_SHORTFALL
    expect(shortfall.qty).toContain('{{unit}}')
  })

  it.each([
    ['en', en.salesIntegrity],
    ['id', id.salesIntegrity],
  ])('%s names every severity level', (_lang, block) => {
    expect(Object.keys(block.severity).sort()).toEqual(['HIGH', 'LOW', 'MEDIUM'])
  })

  it.each([
    ['en', en.salesIntegrity],
    ['id', id.salesIntegrity],
  ])('%s never interpolates the reserved i18next plural key', (_lang, block) => {
    // Passing {{count}} makes i18next resolve a plural form, and with no _one/_other variants
    // English silently renders "1 bills were cancelled". The copy is worded count-last and
    // interpolates {{n}} instead, so no plural machinery is involved at any number.
    const strings = [
      block.coverage.manualCorrections,
      ...Object.values(block.signal as Record<string, { body: string }>).map((c) => c.body),
    ]
    for (const text of strings) {
      expect(text, text).not.toContain('{{count}}')
      expect(text, text).toContain('{{n}}')
    }
  })

  it('leads with the "signal, not proof" disclaimer in both locales', () => {
    // The page can end with somebody being accused of theft. A locale that lost this string would
    // present an estimate as a finding, which is the one failure mode this feature must not have.
    expect(en.salesIntegrity.disclaimer.length).toBeGreaterThan(40)
    expect(id.salesIntegrity.disclaimer.length).toBeGreaterThan(40)
  })
})
