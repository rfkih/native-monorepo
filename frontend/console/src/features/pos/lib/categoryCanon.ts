/**
 * categoryCanon — language-canonical category identity.
 *
 * Owner report: "Main Course" and "Menu Utama" formed TWO separate groups. Root cause: the
 * starter category templates are TRANSLATED AT SAVE TIME — the stored category string depends
 * on whichever UI language the operator happened to be using when the item was created, so the
 * same template splits into one group per language.
 *
 * Canonical rule: a name equal to a starter-template translation in ANY supported language
 * maps to the same `tpl:<key>`; every other (custom) name canonicalises to its own trimmed
 * lowercase self. Grouping, matching, and dedupe compare canonical keys; DISPLAY resolves a
 * template back through the CURRENT UI language, while custom names render verbatim. Stored
 * data is untouched — this is purely a read-side identity, so no migration and no behavior
 * change for custom categories.
 */
import i18next from 'i18next'
import { SUPPORTED_LANGS } from '@/i18n'

/** Starter category templates offered in the picker + the manager (i18n keys). */
export const CATEGORY_TEMPLATE_KEYS = [
  'appetizers',
  'mains',
  'sides',
  'desserts',
  'beverages',
  'coffeeTea',
  'snacks',
  'specials',
] as const

// lowercased translated name (every supported language) → template key. Translations are
// static bundles loaded at init, so the map is built once.
let lookup: Map<string, string> | null = null
function templateLookup(): Map<string, string> {
  if (lookup) return lookup
  const map = new Map<string, string>()
  for (const lng of SUPPORTED_LANGS) {
    for (const key of CATEGORY_TEMPLATE_KEYS) {
      const name = i18next.t(`menu.categoryTemplates.${key}`, { lng })
      map.set(String(name).trim().toLowerCase(), key)
    }
  }
  lookup = map
  return map
}

/** The identity used for grouping/matching/dedupe — never for display. */
export function canonicalCategoryKey(name: string): string {
  const n = name.trim().toLowerCase()
  const tpl = templateLookup().get(n)
  return tpl ? `tpl:${tpl}` : n
}

/** The name to RENDER: templates follow the current UI language, custom names are verbatim. */
export function displayCategoryName(name: string, t: (key: string) => string): string {
  const tpl = templateLookup().get(name.trim().toLowerCase())
  return tpl ? t(`menu.categoryTemplates.${tpl}`) : name
}
