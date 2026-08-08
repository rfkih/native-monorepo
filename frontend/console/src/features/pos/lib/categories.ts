/**
 * categories.ts — the POS "virtual category" model, extracted from Pos.tsx (redesign P1).
 *
 * SINGLE SOURCE for the two recently-fixed semantics — do not fork these again:
 *  - null activeCategoryId = the "All" tab (commit 7277394): resolve null to '' so the item
 *    filter's `!cat → show everything` branch fires.
 *  - a managed category adopts free-text items by name CASE-INSENSITIVELY (commit f050be1):
 *    `legacyKey` is ALWAYS lowercased, and the filter compares lowercased text against it.
 *    (BillDetail.tsx carried a stale case-sensitive copy of this logic — deleted in P1.)
 */
import type { CategoryResponse, MenuItem } from '../api'
import { canonicalCategoryKey } from './categoryCanon'

export interface VirtualCategory {
  id: string
  name: string
  /** The LANGUAGE-CANONICAL match key (categoryCanon) for the free-text name bridge. */
  legacyKey: string
}

/**
 * Merge backend-managed categories with the free-text categories still carried on items
 * (the write path has no categoryId yet). A free-text category whose name matches a managed
 * category (case-insensitively) is "covered" — the managed row already adopts its items.
 */
export function deriveCategories(
  items: MenuItem[],
  backendCategories: CategoryResponse[],
): VirtualCategory[] {
  // Managed rows that are the SAME template in different languages collapse to one tab
  // (categoryCanon — owner report: 'Main Course' vs 'Menu Utama').
  const result: VirtualCategory[] = []
  const seenCanon = new Set<string>()
  for (const c of backendCategories) {
    const canon = canonicalCategoryKey(c.name)
    if (seenCanon.has(canon)) continue
    seenCanon.add(canon)
    result.push({ id: c.id, name: c.name, legacyKey: canon })
  }
  const backendIds = new Set(backendCategories.map((c) => c.id))
  const legacyKeys = new Set<string>()
  for (const item of items) {
    if (!item.categoryId || !backendIds.has(item.categoryId)) {
      if (item.category) {
        const canon = canonicalCategoryKey(item.category)
        if (!legacyKeys.has(canon)) {
          legacyKeys.add(canon)
          if (!seenCanon.has(canon)) {
            seenCanon.add(canon)
            // legacyKey is the LANGUAGE-CANONICAL match key (the item filter compares canon).
            result.push({
              id: item.category,
              name: item.category,
              legacyKey: canon,
            })
          }
        }
      }
    }
  }
  return result
}

/**
 * The menu-grid item filter (verbatim from Pos.tsx's visibleItems memo body).
 * A non-empty search wins over the category tab; `resolvedCategoryId: ''` (the "All" tab)
 * matches no category id, so the `!cat` branch shows everything.
 */
export function visibleMenuItems(
  items: MenuItem[],
  orderedCategories: VirtualCategory[],
  resolvedCategoryId: string,
  searchLower: string,
): MenuItem[] {
  if (searchLower) {
    return items.filter((item) => item.name.toLowerCase().includes(searchLower))
  }
  return items.filter((item) => {
    if (orderedCategories.length === 0) return true
    const cat = orderedCategories.find((c) => c.id === resolvedCategoryId)
    if (!cat) return true
    if (item.categoryId) return item.categoryId === resolvedCategoryId
    // Name bridge: items carry a free-text category (the write path has no categoryId yet), so
    // a category adopts items whose text matches LANGUAGE-CANONICALLY (categoryCanon) — 'Main
    // Course' items belong to the 'Menu Utama' tab and vice versa.
    return canonicalCategoryKey(item.category ?? '') === cat.legacyKey
  })
}
