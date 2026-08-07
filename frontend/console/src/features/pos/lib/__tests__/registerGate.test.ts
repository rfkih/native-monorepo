import { describe, expect, it } from 'vitest'
import { noConfirmedOpenSession, shouldAutoPromptRegister, type RegisterQueryState } from '../registerGate'

function state(partial: Partial<RegisterQueryState>): RegisterQueryState {
  return { offline: false, isLoading: false, isError: false, session: null, ...partial }
}

describe('noConfirmedOpenSession', () => {
  it('is true once the query settles online with a confirmed-closed (null) session', () => {
    expect(noConfirmedOpenSession(state({ session: null }))).toBe(true)
  })

  it('is false when an open session is resolved', () => {
    expect(noConfirmedOpenSession(state({ session: { id: 'abc' } }))).toBe(false)
  })

  it('fails OPEN while offline — the whole flow is a no-op offline (ADR 0028)', () => {
    expect(noConfirmedOpenSession(state({ offline: true, session: null }))).toBe(false)
  })

  it('fails OPEN while the query is loading — a flaky read must never block a sale', () => {
    expect(noConfirmedOpenSession(state({ isLoading: true, session: undefined }))).toBe(false)
  })

  it('fails OPEN when the query errored — the server remains the source of truth for money', () => {
    expect(noConfirmedOpenSession(state({ isError: true, session: undefined }))).toBe(false)
  })

  it('fails OPEN on an unresolved (undefined) session even if not flagged loading/error', () => {
    expect(noConfirmedOpenSession(state({ session: undefined }))).toBe(false)
  })
})

describe('shouldAutoPromptRegister', () => {
  it('prompts once: no confirmed open session and not yet prompted', () => {
    expect(shouldAutoPromptRegister(state({ session: null }), false)).toBe(true)
  })

  it('never re-prompts once already prompted this visit', () => {
    expect(shouldAutoPromptRegister(state({ session: null }), true)).toBe(false)
  })

  it('does not prompt when a session is open', () => {
    expect(shouldAutoPromptRegister(state({ session: { id: 'abc' } }), false)).toBe(false)
  })

  it('does not prompt offline even if never prompted', () => {
    expect(shouldAutoPromptRegister(state({ offline: true, session: null }), false)).toBe(false)
  })

  it('does not prompt while loading or errored', () => {
    expect(shouldAutoPromptRegister(state({ isLoading: true, session: undefined }), false)).toBe(false)
    expect(shouldAutoPromptRegister(state({ isError: true, session: undefined }), false)).toBe(false)
  })
})
