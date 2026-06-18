import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { en } from './locales/en'
import { id } from './locales/id'

export const SUPPORTED_LANGS = ['en', 'id'] as const
export type Lang = (typeof SUPPORTED_LANGS)[number]

const STORAGE_KEY = 'native.console.lang'

function initialLang(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'en' || saved === 'id') return saved
  } catch {
    /* ignore */
  }
  return navigator.language?.startsWith('id') ? 'id' : 'en'
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, id: { translation: id } },
  lng: initialLang(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

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
