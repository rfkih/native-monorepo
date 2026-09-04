import { afterEach, describe, expect, it, vi } from 'vitest'
import { isIndonesiaByTimeZone, isIndonesiaCompany, langsForCountry, offeredLangs } from '@/lib/geo'
import { resolveLanguage } from '@/i18n'

/**
 * The English-first localization policy (ADR 0059): English is the global default; Indonesian is
 * offered ONLY in Indonesia — resolved by the active company's country in-app (IDR ⇔ Indonesia, ADR
 * 0025) or the browser time zone on public pages. These run in the `node` test env, where
 * `localStorage` is absent, so `resolveLanguage` sees NO explicit user preference — exactly the
 * auto-select / clamp branches under test.
 */
describe('offeredLangs / langsForCountry', () => {
  it('offers both languages in Indonesia, English-only elsewhere', () => {
    expect(offeredLangs(true)).toEqual(['en', 'id'])
    expect(offeredLangs(false)).toEqual(['en'])
  })

  it('gates the signup language chooser on the selected country', () => {
    expect(langsForCountry('ID')).toEqual(['en', 'id'])
    expect(langsForCountry('US')).toEqual(['en'])
    expect(langsForCountry('SG')).toEqual(['en'])
  })
})

describe('isIndonesiaCompany — exact IDR proxy (ADR 0025)', () => {
  it('is Indonesian iff the base currency is IDR', () => {
    expect(isIndonesiaCompany('IDR')).toBe(true)
    expect(isIndonesiaCompany('USD')).toBe(false)
    expect(isIndonesiaCompany(null)).toBe(false)
    expect(isIndonesiaCompany(undefined)).toBe(false)
  })
})

describe('isIndonesiaByTimeZone — physical-location proxy', () => {
  afterEach(() => vi.restoreAllMocks())

  function mockZone(timeZone: string) {
    vi.spyOn(Intl, 'DateTimeFormat').mockReturnValue({
      resolvedOptions: () => ({ timeZone }),
    } as unknown as Intl.DateTimeFormat)
  }

  it('detects the four Indonesian zones', () => {
    for (const tz of ['Asia/Jakarta', 'Asia/Pontianak', 'Asia/Makassar', 'Asia/Jayapura']) {
      mockZone(tz)
      expect(isIndonesiaByTimeZone()).toBe(true)
    }
  })

  it('is false everywhere else', () => {
    mockZone('America/New_York')
    expect(isIndonesiaByTimeZone()).toBe(false)
    mockZone('Asia/Singapore')
    expect(isIndonesiaByTimeZone()).toBe(false)
  })
})

describe('resolveLanguage — no explicit user choice', () => {
  it('auto-selects Indonesian only in Indonesia', () => {
    expect(resolveLanguage(true)).toBe('id')
    expect(resolveLanguage(false)).toBe('en')
  })

  it("adopts the company's default language when it is offered", () => {
    expect(resolveLanguage(true, 'id')).toBe('id')
    expect(resolveLanguage(true, 'en')).toBe('en')
  })

  it('clamps Indonesian off a non-Indonesian company to English', () => {
    expect(resolveLanguage(false, 'id')).toBe('en')
  })
})

describe('resolveLanguage — explicit user choice (persisted preference)', () => {
  // The `node` env has no localStorage, so stub it to drive `explicitLang()` — these are exactly the
  // precedence branches the pure no-preference cases above cannot reach.
  const original = (globalThis as { localStorage?: unknown }).localStorage
  function stubSaved(value: string | null) {
    ;(globalThis as { localStorage?: unknown }).localStorage = {
      getItem: (key: string) => (key === 'native.console.lang' ? value : null),
      setItem: () => {},
      removeItem: () => {},
    }
  }
  afterEach(() => {
    if (original === undefined) delete (globalThis as { localStorage?: unknown }).localStorage
    else (globalThis as { localStorage?: unknown }).localStorage = original
  })

  it('honors an explicit choice that is still offered here (wins over the company default)', () => {
    stubSaved('en')
    expect(resolveLanguage(true, 'id')).toBe('en')
  })

  it('clamps a stored Indonesian preference to English on a non-Indonesian context', () => {
    stubSaved('id')
    expect(resolveLanguage(false, 'id')).toBe('en')
  })

  it('ignores an unsupported stored value and falls back to the location default', () => {
    stubSaved('fr')
    expect(resolveLanguage(true)).toBe('id')
    expect(resolveLanguage(false)).toBe('en')
  })
})
