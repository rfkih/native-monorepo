/**
 * homeDoors — which page sits behind each figure on the home (ADR 0082 amendment).
 *
 * A figure card is a door to the page that EXPLAINS it: today's transactions, the average bill
 * and the hero's net are all the till's sales-history sheet; the open bills are the till's order
 * switcher; the gross margin is Laba-rugi, where HPP lives (ADR 0065). The till's sheets are not
 * routes, so those doors carry `?sheet=` (features/pos/lib/sheetParam.ts).
 *
 * Pure so the gates are testable — and they are the ROUTES' gates, not the home's, because a door
 * to a page the router will bounce is worse than a plain card:
 *   - `tillOk`: `/pos` is routed on the POS role AND the `pos` page grant (App.tsx `posAllowed`),
 *     the More page adds the `pos` tier feature, and only the RESTAURANT till reads `?sheet=`
 *     (the service verticals' ServicePos has no history sheet or order switcher).
 *   - `pnlOk`: `/statements/income` is routed on the reports role AND the `reports` page grant
 *     (App.tsx `reportsAllowed`) — NOT `dashboard`, which every home already has.
 */
export type HomeFigure = 'hero' | 'txn' | 'avg' | 'bills' | 'margin'

export interface HomeDoorGates {
  /** POS role + `pos` page grant + `pos` tier feature + restaurant vertical. */
  tillOk: boolean
  /** Reports role + `reports` page grant. */
  pnlOk: boolean
}

export const POS_HISTORY_DOOR = '/pos?sheet=history'
export const POS_ORDERS_DOOR = '/pos?sheet=orders'
export const PNL_DOOR = '/statements/income'

export function homeDoorFor(key: HomeFigure, gates: HomeDoorGates): string | null {
  switch (key) {
    case 'hero':
    case 'txn':
    case 'avg':
      return gates.tillOk ? POS_HISTORY_DOOR : null
    case 'bills':
      return gates.tillOk ? POS_ORDERS_DOOR : null
    case 'margin':
      return gates.pnlOk ? PNL_DOOR : null
  }
}
