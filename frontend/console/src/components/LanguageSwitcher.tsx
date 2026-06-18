import { useTranslation } from 'react-i18next'
import { Segmented } from '@/components/ui/Segmented'
import { SUPPORTED_LANGS, setLanguage, type Lang } from '@/i18n'

export function LanguageSwitcher() {
  const { i18n } = useTranslation()
  const current: Lang = SUPPORTED_LANGS.includes(i18n.language as Lang)
    ? (i18n.language as Lang)
    : 'en'
  return (
    <Segmented
      ariaLabel="Language"
      value={current}
      onChange={setLanguage}
      options={SUPPORTED_LANGS.map((lang) => ({ value: lang, label: lang.toUpperCase() }))}
    />
  )
}
