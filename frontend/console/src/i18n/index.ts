import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { en } from './locales/en'
import { id } from './locales/id'
import { isIndonesiaByTimeZone, offeredLangs } from '@/lib/geo'

export const SUPPORTED_LANGS = ['en', 'id'] as const
export type Lang = (typeof SUPPORTED_LANGS)[number]

const STORAGE_KEY = 'native.console.lang'

/**
 * The user's EXPLICIT language choice (a manual toggle via {@link setLanguage}), or `null` if they
 * have never chosen one. Only an explicit choice is persisted — auto-selection (location default,
 * adopting the company default, clamping) never writes storage, so this stays a true signal of "the
 * user picked this" vs "we picked it for them".
 */
function explicitLang(): Lang | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === 'en' || saved === 'id' ? saved : null
  } catch {
    return null
  }
}

/**
 * The language to show, given the current context's Indonesia-ness and (optionally) the active
 * company's own default language — the single source of the English-first policy (ADR 0059):
 *
 *  1. an explicit user choice that is STILL offered here wins (respects a manual toggle);
 *  2. otherwise adopt the company's default language when it is offered;
 *  3. otherwise auto-select by location — Indonesian in Indonesia, English everywhere else.
 *
 * Anything not offered in this context collapses to English (e.g. a stored Indonesian preference, or
 * a company default of `id`, on a non-Indonesian company).
 */
export function resolveLanguage(indonesia: boolean, companyDefault?: string | null): Lang {
  const offered = offeredLangs(indonesia)
  const saved = explicitLang()
  if (saved && offered.includes(saved)) return saved
  const locationDefault: Lang = offered.includes('id') ? 'id' : 'en'
  const wanted: Lang =
    companyDefault === 'en' || companyDefault === 'id' ? companyDefault : locationDefault
  return offered.includes(wanted) ? wanted : 'en'
}

/** The boot language — resolved from the browser time zone alone (no company is known yet). */
function initialLang(): Lang {
  return resolveLanguage(isIndonesiaByTimeZone())
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, id: { translation: id } },
  lng: initialLang(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

/** Applies a language WITHOUT recording an explicit preference (auto-select / clamp use this). */
function applyLanguage(lang: Lang): void {
  if (i18n.language !== lang) void i18n.changeLanguage(lang)
}

/**
 * Reconciles the active language with the current context (ADR 0059) — called on boot and whenever
 * the active company changes. Applies {@link resolveLanguage}; never persists (only an explicit
 * {@link setLanguage} does). This is also where a company's stored `defaultLanguage` finally reaches
 * the UI — previously it was read into the session but never applied.
 */
export function reconcileLanguage(indonesia: boolean, companyDefault?: string | null): void {
  applyLanguage(resolveLanguage(indonesia, companyDefault))
}

/** Records an EXPLICIT user language choice (a manual toggle) and applies it. */
export function setLanguage(lang: Lang): void {
  void i18n.changeLanguage(lang)
  try {
    localStorage.setItem(STORAGE_KEY, lang)
  } catch {
    /* ignore */
  }
}

/** BCP-47 tag for Intl formatting (money, dates) — derived from the active i18n language. */
export function localeOf(lang: string): string {
  return lang === 'id' ? 'id-ID' : 'en-US'
}

export default i18n
