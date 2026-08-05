/**
 * Curated Indonesian bank list for the bank-account dialog's institution dropdown. Bank BRAND
 * names are proper nouns (data, not translatable copy — the country-picker precedent applies to
 * labels only); the dropdown's label/placeholder/"Other" strings live in i18n (rule 9). "Other"
 * reveals a free-text input so an unlisted bank is never a dead end.
 */
export const INDONESIAN_BANKS = [
  'BCA',
  'Bank Mandiri',
  'BRI',
  'BNI',
  'BSI (Bank Syariah Indonesia)',
  'CIMB Niaga',
  'Bank Danamon',
  'PermataBank',
  'Maybank Indonesia',
  'OCBC Indonesia',
  'Bank BTN',
  'Panin Bank',
  'Bank Mega',
  'Bank Jago',
  'SeaBank',
  'Bank Neo Commerce',
] as const

/** Sentinel option value for "Other" — never sent to the API as a bank name. */
export const OTHER_BANK = '__OTHER__'
