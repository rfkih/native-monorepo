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

export interface VirtualCategory {
  id: string
  name: string
  /** The LOWERCASED match key for the free-text name bridge (see module doc). */
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
  const result: VirtualCategory[] = backendCategories.map((c) => ({
    id: c.id,
    name: c.name,
    legacyKey: c.name.toLowerCase(),
  }))
  const backendIds = new Set(backendCategories.map((c) => c.id))
  const legacyKeys = new Set<string>()
  for (const item of items) {
    if (!item.categoryId || !backendIds.has(item.categoryId)) {
      if (item.category && !legacyKeys.has(item.category)) {
        legacyKeys.add(item.category)
        const covered = backendCategories.some(
          (c) => c.name.toLowerCase() === item.category.toLowerCase(),
        )
        if (!covered) {
          // legacyKey is the LOWERCASED match key (the item filter compares lowercased text).
          result.push({
            id: item.category,
            name: item.category,
            legacyKey: item.category.toLowerCase(),
          })
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
    // a managed category adopts items whose text matches its name CASE-INSENSITIVELY. legacyKey
    // is always lowercased in deriveCategories — compare apples to apples.
    return (item.category ?? '').toLowerCase() === cat.legacyKey
  })
}
