import { describe, expect, it } from 'vitest'
import { nextChargePhase, type ChargePhase } from '../chargePhase'

describe('nextChargePhase', () => {
  it('CREATE_STARTED always moves to creating — the first attempt AND every retry/new-QR tap', () => {
    const phases: ChargePhase[] = ['idle', 'creating', 'active', 'expired', 'error', 'cancelling']
    for (const phase of phases) {
      expect(nextChargePhase(phase, { type: 'CREATE_STARTED' })).toBe('creating')
    }
  })

  it('CREATE_FAILED moves to error', () => {
    expect(nextChargePhase('creating', { type: 'CREATE_FAILED' })).toBe('error')
  })

  describe('expiry from the countdown', () => {
    it('active + COUNTDOWN_EXPIRED → expired', () => {
      expect(nextChargePhase('active', { type: 'COUNTDOWN_EXPIRED' })).toBe('expired')
    })

    it('is a no-op outside active (the ticker only runs while active — defensive either way)', () => {
      expect(nextChargePhase('creating', { type: 'COUNTDOWN_EXPIRED' })).toBe('creating')
      expect(nextChargePhase('expired', { type: 'COUNTDOWN_EXPIRED' })).toBe('expired')
      expect(nextChargePhase('idle', { type: 'COUNTDOWN_EXPIRED' })).toBe('idle')
    })
  })

  describe('new-QR / retry resets', () => {
    it('expired → creating on a fresh CREATE_STARTED (the "new QR" tap)', () => {
      expect(nextChargePhase('expired', { type: 'CREATE_STARTED' })).toBe('creating')
    })

    it('error → creating on a fresh CREATE_STARTED (the retry tap)', () => {
      expect(nextChargePhase('error', { type: 'CREATE_STARTED' })).toBe('creating')
    })
  })

  describe('FAILED → error', () => {
    it('a FAILED charge status while creating moves to error', () => {
      expect(nextChargePhase('creating', { type: 'CHARGE_STATUS', status: 'FAILED' })).toBe('error')
    })

    it('a FAILED charge status while active also moves to error (e.g. a sync/cancel surfaces it)', () => {
      expect(nextChargePhase('active', { type: 'CHARGE_STATUS', status: 'FAILED' })).toBe('error')
    })
  })

  describe('CHARGE_STATUS — the normal (non-cancelling) path', () => {
    it('INITIATED/QR_ISSUED/SUCCEEDED all render active', () => {
      expect(nextChargePhase('creating', { type: 'CHARGE_STATUS', status: 'INITIATED' })).toBe('active')
      expect(nextChargePhase('creating', { type: 'CHARGE_STATUS', status: 'QR_ISSUED' })).toBe('active')
      expect(nextChargePhase('active', { type: 'CHARGE_STATUS', status: 'SUCCEEDED' })).toBe('active')
    })

    it('EXPIRED renders expired (server-side lazy expiry, not just the client ticker)', () => {
      expect(nextChargePhase('active', { type: 'CHARGE_STATUS', status: 'EXPIRED' })).toBe('expired')
    })

    it('CANCELED renders idle', () => {
      expect(nextChargePhase('active', { type: 'CHARGE_STATUS', status: 'CANCELED' })).toBe('idle')
    })
  })

  describe('cancel lifecycle', () => {
    it('CANCEL_STARTED moves active/expired → cancelling', () => {
      expect(nextChargePhase('active', { type: 'CANCEL_STARTED' })).toBe('cancelling')
      expect(nextChargePhase('expired', { type: 'CANCEL_STARTED' })).toBe('cancelling')
    })

    it('CANCEL_STARTED is a no-op outside active/expired (no live charge to cancel)', () => {
      expect(nextChargePhase('idle', { type: 'CANCEL_STARTED' })).toBe('idle')
      expect(nextChargePhase('creating', { type: 'CANCEL_STARTED' })).toBe('creating')
    })

    it('CANCEL_FAILED moves to error', () => {
      expect(nextChargePhase('cancelling', { type: 'CANCEL_FAILED' })).toBe('error')
    })

    it('captured-beats-cancel: a SUCCEEDED status arriving while cancelling returns to active, ' +
      'never idle — the caller must keep polling the vertical read toward the receipt', () => {
      expect(nextChargePhase('cancelling', { type: 'CHARGE_STATUS', status: 'SUCCEEDED' })).toBe('active')
    })

    it('a genuine CANCELED (or EXPIRED) status while cancelling drops to idle — the panel falls ' +
      'back to the manual "Mark as paid" flow', () => {
      expect(nextChargePhase('cancelling', { type: 'CHARGE_STATUS', status: 'CANCELED' })).toBe('idle')
      expect(nextChargePhase('cancelling', { type: 'CHARGE_STATUS', status: 'EXPIRED' })).toBe('idle')
    })

    it('a FAILED status while cancelling ALSO drops to idle, not error — captured-beats-cancel only ' +
      'special-cases SUCCEEDED; every other status is "cancel won"', () => {
      expect(nextChargePhase('cancelling', { type: 'CHARGE_STATUS', status: 'FAILED' })).toBe('idle')
    })
  })
})
