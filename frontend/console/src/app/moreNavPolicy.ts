/**
 * Pure list policy for the phone "More" surface — its own module (not the component file) so it
 * stays unit-testable and fast-refresh-safe, matching `tabBarPolicy`.
 *
 * Two problems it solves, both of which only exist on a phone:
 *
 *  - **33 links with no filter.** A desktop sidebar can hold the whole tree at once; a 390pt
 *    column cannot, and More is the ONLY navigation a phone login has. So a query filters the
 *    tree, and while filtering nothing is reordered — a search result list that also re-sorts
 *    itself is harder to scan, not easier.
 *  - **Group order ignores who is looking.** The tree renders in `navGroups` order for everybody,
 *    so an accountant scrolls past catalog and sales to reach receivables. At rest, the groups
 *    that ARE this persona's daily work float to the top and are marked; everything else keeps
 *    its original relative order beneath them.
 */

export interface NavLike {
  key: string
  items: readonly { label: string }[]
}

/** Group keys that count as each persona's own daily work, in the order they should surface. */
export const OWN_GROUPS = {
  /** Owner/manager — the operating picture. */
  ops: ['summary', 'reports', 'people'],
  /** Accountant — the books. */
  finance: ['receivables', 'payables', 'cashTax', 'reports'],
} as const

export function normalizeQuery(query: string): string {
  return query.trim().toLowerCase()
}

/**
 * Filter by `query`, else order `ownKeys` first. Groups left empty by a filter are dropped, so a
 * heading never sits above nothing.
 */
export function arrangeNavGroups<G extends NavLike>(
  groups: readonly G[],
  query: string,
  ownKeys: readonly string[],
): G[] {
  const q = normalizeQuery(query)

  if (q.length > 0) {
    return groups
      .map((g) => ({ ...g, items: g.items.filter((it) => it.label.toLowerCase().includes(q)) }))
      .filter((g) => g.items.length > 0)
  }

  const own = ownKeys.map((k) => groups.find((g) => g.key === k)).filter((g) => g != null)
  const rest = groups.filter((g) => !ownKeys.includes(g.key))
  return [...own, ...rest]
}

/** Whether a group should carry the "your work" marker — only meaningful at rest. */
export function isOwnGroup(key: string, query: string, ownKeys: readonly string[]): boolean {
  return normalizeQuery(query).length === 0 && ownKeys.includes(key)
}
