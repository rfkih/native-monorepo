import { describe, expect, it } from 'vitest'
import { formatDate, payDisabledReason, voidDisabledReason } from '../format'

describe('formatDate', () => {
  it('returns an em dash for a null/undefined/empty value', () => {
    expect(formatDate(null, 'en')).toBe('—')
    expect(formatDate(undefined, 'en')).toBe('—')
    expect(formatDate('', 'en')).toBe('—')
  })

  it('falls back to the raw string for an unparseable date', () => {
    expect(formatDate('not-a-date', 'en')).toBe('not-a-date')
  })

  it('formats a valid ISO date via Intl', () => {
    const formatted = formatDate('2026-07-20', 'en-US')
    expect(formatted).toContain('2026')
    expect(formatted).toContain('Jul')
  })
})

describe('payDisabledReason', () => {
  it('is disabled (not approved) for any non-APPROVED status', () => {
    expect(payDisabledReason({ status: 'SUBMITTED', reimbursementRunId: null })).toBe(
      'expenses.actions.payDisabled.notApproved',
    )
    expect(payDisabledReason({ status: 'REIMBURSED', reimbursementRunId: null })).toBe(
      'expenses.actions.payDisabled.notApproved',
    )
  })

  it('is disabled (linked) when APPROVED but linked to a payroll run', () => {
    expect(payDisabledReason({ status: 'APPROVED', reimbursementRunId: 'run-1' })).toBe(
      'expenses.actions.payDisabled.linked',
    )
  })

  it('is enabled (null) when APPROVED and unlinked', () => {
    expect(payDisabledReason({ status: 'APPROVED', reimbursementRunId: null })).toBeNull()
  })
})

describe('voidDisabledReason', () => {
  const base = { reimbursementRunId: null as string | null, settledAt: null as string | null }

  it('is disabled (not approved) for any non-APPROVED status', () => {
    expect(voidDisabledReason({ status: 'SUBMITTED', ...base })).toBe(
      'expenses.actions.voidDisabled.notApproved',
    )
  })

  it('is disabled (linked) when APPROVED but linked to a payroll run', () => {
    expect(
      voidDisabledReason({ status: 'APPROVED', reimbursementRunId: 'run-1', settledAt: null }),
    ).toBe('expenses.actions.voidDisabled.linked')
  })

  it('is disabled (settled) when APPROVED, unlinked, but already settled', () => {
    expect(
      voidDisabledReason({
        status: 'APPROVED',
        reimbursementRunId: null,
        settledAt: '2026-07-21T10:00:00Z',
      }),
    ).toBe('expenses.actions.voidDisabled.settled')
  })

  it('checks the link before the settlement stamp', () => {
    // Both set — the backend guard checks reimbursement_run_id first; the client mirrors it.
    expect(
      voidDisabledReason({
        status: 'APPROVED',
        reimbursementRunId: 'run-1',
        settledAt: '2026-07-21T10:00:00Z',
      }),
    ).toBe('expenses.actions.voidDisabled.linked')
  })

  it('is enabled (null) when APPROVED, unlinked, and unsettled', () => {
    expect(voidDisabledReason({ status: 'APPROVED', ...base })).toBeNull()
  })
})
