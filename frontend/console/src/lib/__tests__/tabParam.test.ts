/**
 * tabParam — pure-module tests (vitest, node env). The React wiring is exercised by
 * scripts/nav-smoke.mjs; the decisions live here.
 */
import { describe, expect, it } from 'vitest'
import { needsCanonicalTab, resolveTab, withTabParam } from '../tabParam'

const PEOPLE = ['employees', 'attendance', 'payroll'] as const

describe('resolveTab', () => {
  it('takes the tab the URL names', () => {
    expect(resolveTab('payroll', PEOPLE, 'employees')).toBe('payroll')
  })

  it('falls back when the URL names nothing', () => {
    expect(resolveTab(null, PEOPLE, 'employees')).toBe('employees')
    expect(resolveTab('', PEOPLE, 'employees')).toBe('employees')
  })

  it('falls back on a tab this login cannot see', () => {
    // A manager loses `payroll` (PAYROLL is owner/hr only, ADR 0052) — an old bookmark or a shared
    // link must still open People, not render an empty page.
    expect(resolveTab('payroll', ['employees', 'attendance'], 'employees')).toBe('employees')
  })

  it('falls back on a junk value', () => {
    expect(resolveTab('../etc', PEOPLE, 'employees')).toBe('employees')
    expect(resolveTab('Payroll', PEOPLE, 'employees')).toBe('employees') // case-sensitive by design
  })
})

describe('needsCanonicalTab', () => {
  it('asks for a write when the URL is silent or stale', () => {
    expect(needsCanonicalTab(null, 'employees')).toBe(true)
    expect(needsCanonicalTab('payroll', 'employees')).toBe(true) // resolved away from an unusable tab
  })

  it('asks for nothing once the URL already states it — this is what stops the write loop', () => {
    expect(needsCanonicalTab('employees', 'employees')).toBe(false)
  })
})

describe('withTabParam', () => {
  it('sets the tab', () => {
    expect(withTabParam(new URLSearchParams(''), 'payroll').toString()).toBe('tab=payroll')
  })

  it('replaces an existing tab rather than appending a second one', () => {
    expect(withTabParam(new URLSearchParams('tab=employees'), 'payroll').toString()).toBe(
      'tab=payroll',
    )
  })

  it('keeps every other parameter the page carries', () => {
    const out = withTabParam(new URLSearchParams('period=2026-09&unit=abc'), 'attendance')
    expect(out.get('period')).toBe('2026-09')
    expect(out.get('unit')).toBe('abc')
    expect(out.get('tab')).toBe('attendance')
  })

  it('does not mutate the input', () => {
    const input = new URLSearchParams('tab=employees')
    withTabParam(input, 'payroll')
    expect(input.get('tab')).toBe('employees')
  })
})
