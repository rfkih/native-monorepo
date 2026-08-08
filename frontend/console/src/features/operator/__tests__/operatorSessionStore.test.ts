import { describe, expect, it } from 'vitest'
import {
  OPERATOR_SESSION_STORAGE_KEY,
  clearOperatorSession,
  loadOperatorSession,
  saveOperatorSession,
  type OperatorSessionData,
} from '../operatorSessionStore'

/** A minimal in-memory Storage stand-in — this repo's vitest runs `environment: 'node'` (no DOM /
 * global localStorage), matching lib/__tests__/SessionProvider.test.ts's approach. Only the
 * getItem/setItem/removeItem trio is implemented — exactly what operatorSessionStore's functions
 * accept via `Pick<Storage, ...>`. */
class MemoryStorage {
  private map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) ?? null) : null
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
}

const NOW = Date.parse('2026-08-09T12:00:00Z')

function validSession(overrides: Partial<OperatorSessionData> = {}): OperatorSessionData {
  return {
    token: 'signed-token',
    displayName: 'Ani Wijaya',
    role: 'Cashier',
    expiresAt: new Date(NOW + 60_000).toISOString(), // 1 minute in the future
    ...overrides,
  }
}

describe('loadOperatorSession', () => {
  it('returns null when nothing is stored', () => {
    const storage = new MemoryStorage()
    expect(loadOperatorSession(storage, NOW)).toBeNull()
  })

  it('round-trips a session saved via saveOperatorSession', () => {
    const storage = new MemoryStorage()
    const data = validSession()
    saveOperatorSession(storage, data)
    expect(loadOperatorSession(storage, NOW)).toEqual(data)
  })

  it('returns null and clears the key when the stored JSON is corrupted', () => {
    const storage = new MemoryStorage()
    storage.setItem(OPERATOR_SESSION_STORAGE_KEY, '{not json')
    expect(loadOperatorSession(storage, NOW)).toBeNull()
    expect(storage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull()
  })

  it('returns null and clears the key when the shape is well-formed JSON but missing fields', () => {
    const storage = new MemoryStorage()
    storage.setItem(OPERATOR_SESSION_STORAGE_KEY, JSON.stringify({ token: 'abc' }))
    expect(loadOperatorSession(storage, NOW)).toBeNull()
    expect(storage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull()
  })

  it('returns null and clears the key once expiresAt has passed — never resurrects a stale operator', () => {
    const storage = new MemoryStorage()
    saveOperatorSession(storage, validSession({ expiresAt: new Date(NOW - 1).toISOString() }))
    expect(loadOperatorSession(storage, NOW)).toBeNull()
    expect(storage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull()
  })

  it('treats expiresAt exactly equal to now as expired (boundary is exclusive)', () => {
    const storage = new MemoryStorage()
    saveOperatorSession(storage, validSession({ expiresAt: new Date(NOW).toISOString() }))
    expect(loadOperatorSession(storage, NOW)).toBeNull()
  })

  it('keeps a session that expires one millisecond from now', () => {
    const storage = new MemoryStorage()
    const data = validSession({ expiresAt: new Date(NOW + 1).toISOString() })
    saveOperatorSession(storage, data)
    expect(loadOperatorSession(storage, NOW)).toEqual(data)
  })
})

describe('clearOperatorSession', () => {
  it('removes a previously saved session', () => {
    const storage = new MemoryStorage()
    saveOperatorSession(storage, validSession())
    clearOperatorSession(storage)
    expect(loadOperatorSession(storage, NOW)).toBeNull()
  })

  it('is a no-op (does not throw) when nothing was stored', () => {
    const storage = new MemoryStorage()
    expect(() => clearOperatorSession(storage)).not.toThrow()
  })
})

describe('saveOperatorSession', () => {
  it('never throws even when the underlying storage.setItem throws (e.g. quota exceeded)', () => {
    const throwing: Pick<Storage, 'setItem'> = {
      setItem: () => {
        throw new Error('quota exceeded')
      },
    }
    expect(() => saveOperatorSession(throwing, validSession())).not.toThrow()
  })
})
