/**
 * Location → which UI languages are OFFERED (ADR 0059 — English-first localization).
 *
 * Product policy: English is the global default; Indonesian is offered ONLY when the context is
 * Indonesia. "Indonesia" is resolved two ways, depending on whether a company is in play:
 *
 *   - **authenticated / in-app** → the ACTIVE company's country is Indonesia. The company already
 *     carries `baseCurrency`, and ADR 0025 derives IDR ⇔ Indonesia biconditionally (ID → IDR, every
 *     other country → USD), so `baseCurrency === 'IDR'` is an EXACT proxy for `country === 'ID'`.
 *     That means no extra field or backend change is needed to gate the UI.
 *   - **anonymous / public** (landing, and signup BEFORE a country is picked) → the browser's IANA
 *     time zone is one of Indonesia's four. Time zone reflects PHYSICAL location, not the phone's UI
 *     language, so an English-locale phone in Jakarta still gets Indonesian offered, and an
 *     Indonesian-locale phone abroad does not.
 *
 * Adding a third language later is just extending {@link offeredLangs} (and the locale files); every
 * gate reads from here, so the policy lives in exactly one place.
 */
import { useSession } from '@/lib/session'
// Type-only import: erased at build time, so this does NOT create a runtime cycle with `@/i18n`
// (which imports the runtime helpers below).
import type { Lang } from '@/i18n'

/** Indonesia's four IANA time zones — WIB: Jakarta/Pontianak, WITA: Makassar, WIT: Jayapura. */
const INDONESIA_TIME_ZONES: ReadonlySet<string> = new Set([
  'Asia/Jakarta',
  'Asia/Pontianak',
  'Asia/Makassar',
  'Asia/Jayapura',
])

/**
 * Whether the browser's resolved IANA time zone is in Indonesia — the best client-only proxy for
 * physical location (there is no geo-IP backend). Fails CLOSED (English-only) if the runtime can't
 * resolve a time zone.
 */
export function isIndonesiaByTimeZone(): boolean {
  try {
    return INDONESIA_TIME_ZONES.has(Intl.DateTimeFormat().resolvedOptions().timeZone)
  } catch {
    return false
  }
}

/**
 * Whether a company is Indonesian — the exact proxy via its immutable, derived base currency
 * (ADR 0025 makes `IDR` ⇔ country Indonesia). `null`/`undefined` currency → not Indonesian.
 */
export function isIndonesiaCompany(baseCurrency: string | null | undefined): boolean {
  return baseCurrency === 'IDR'
}

/**
 * The languages OFFERED in a context. English is ALWAYS offered; Indonesian only when `indonesia`
 * is true. Returned in display order (English first). A literal subset — deliberately does not
 * import `SUPPORTED_LANGS` from `@/i18n`, so there is no runtime import cycle.
 */
export function offeredLangs(indonesia: boolean): readonly Lang[] {
  return indonesia ? (['en', 'id'] as const) : (['en'] as const)
}

/**
 * The languages a company-creation form should offer for a given ISO 3166-1 country: both when the
 * country is Indonesia, English only otherwise — the signup/add-company mirror of {@link
 * offeredLangs}, keyed on the SELECTED country rather than the ambient location.
 */
export function langsForCountry(country: string): readonly Lang[] {
  return offeredLangs(country === 'ID')
}

/**
 * The languages offered in the CURRENT app context: the active company's country when signed in,
 * else the browser time zone. Drives the language switcher's options (and whether it renders at all
 * — a single-language context has nothing to switch).
 */
export function useOfferedLangs(): readonly Lang[] {
  const { company } = useSession()
  const indonesia = company ? isIndonesiaCompany(company.baseCurrency) : isIndonesiaByTimeZone()
  return offeredLangs(indonesia)
}
