/**
 * tabParam — the `?tab=` contract (ADR 0075 rule N5: state that changes the main content lives in
 * the URL, two-directionally, via `replace`).
 *
 * The console used to sync one way only, URL → state. The sidebar has three People entries
 * (`/people?tab=employees|payroll|attendance`) and marks one active by matching pathname AND
 * search, but clicking the page's own Segmented control never wrote back — so the nav went on
 * highlighting a tab the page was no longer showing, the tab could not be linked or bookmarked,
 * and Back did not undo a tab switch, it left the page entirely.
 *
 * Two-directional needs `replace`, not `push`: pushing would make Back walk the tabs one at a time
 * before it ever left the page, which is more surprising than the bug it fixes. `replace` also
 * keeps this out of the scroll rules (N4 leaves REPLACE alone) and out of the route animation
 * (TransitionedRoutes does not animate REPLACE), so a tab switch stays instant.
 */

/**
 * Which tab a URL is asking for, given the tabs that actually exist for this login. An unknown,
 * missing, or no-longer-permitted value resolves to `fallback` rather than rendering nothing —
 * a stale bookmark from before a role change must still open the page.
 */
export function resolveTab<T extends string>(
  urlTab: string | null,
  tabs: readonly T[],
  fallback: T,
): T {
  return (tabs as readonly string[]).includes(urlTab ?? '') ? (urlTab as T) : fallback
}

/**
 * True when the URL does not yet state the tab being shown, so the page should canonicalise it.
 *
 * Writing the resolved tab back on arrival is what lets `Shell`'s nav keep its plain exact-match
 * `isActive` (navGroups items carry a literal `?tab=`): the URL always names a tab, so there is no
 * "no tab means the first one" special case to teach the sidebar. It is a REPLACE of a URL the
 * user never typed, so nothing about it is visible.
 */
export function needsCanonicalTab(urlTab: string | null, resolved: string): boolean {
  return urlTab !== resolved
}

/** `search` with `tab` set, preserving every other parameter the page may carry. */
export function withTabParam(search: URLSearchParams, tab: string): URLSearchParams {
  const next = new URLSearchParams(search)
  next.set('tab', tab)
  return next
}
