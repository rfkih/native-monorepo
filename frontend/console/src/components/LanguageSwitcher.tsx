import { useTranslation } from 'react-i18next'
import { Segmented } from '@/components/ui/Segmented'
import { setLanguage, type Lang } from '@/i18n'
import { useOfferedLangs } from '@/lib/geo'

/**
 * The language toggle — renders only the languages OFFERED in the current context (ADR 0059:
 * English everywhere, Indonesian only in Indonesia). Outside Indonesia there is a single language,
 * so the switcher renders nothing rather than a one-option control.
 */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation()
  const offered = useOfferedLangs()
  if (offered.length < 2) return null
  const current: Lang = offered.includes(i18n.language as Lang) ? (i18n.language as Lang) : 'en'
  return (
    <Segmented
      ariaLabel={t('common.language')}
      value={current}
      onChange={(v) => setLanguage(v as Lang)}
      options={offered.map((lang) => ({ value: lang, label: lang.toUpperCase() }))}
    />
  )
}
