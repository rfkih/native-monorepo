/**
 * homeDoors — which page sits behind each figure on the home (ADR 0082 amendment).
 *
 * A figure card is a door to the page that EXPLAINS it: today's transactions, the average bill
 * and the hero's net are all the till's sales-history sheet; the open bills are the till's order
 * switcher; the gross margin is Laba-rugi, where HPP lives (ADR 0065). The till's sheets are not
 * routes, so those doors carry `?sheet=` (features/pos/lib/sheetParam.ts).
 *
 * Pure so the one gate is testable: Laba-rugi needs the `dashboard` page grant, exactly like the
 * "Laba rugi" door tile below the figures. TodayHome renders only for a POS login, so the till
 * doors need no gate of their own.
 */
export type HomeFigure = 'hero' | 'txn' | 'avg' | 'bills' | 'margin'

export const POS_HISTORY_DOOR = '/pos?sheet=history'
export const POS_ORDERS_DOOR = '/pos?sheet=orders'
export const PNL_DOOR = '/statements/income'

export function homeDoorFor(key: HomeFigure, gates: { pnlOk: boolean }): string | null {
  switch (key) {
    case 'hero':
    case 'txn':
    case 'avg':
      return POS_HISTORY_DOOR
    case 'bills':
      return POS_ORDERS_DOOR
    case 'margin':
      return gates.pnlOk ? PNL_DOOR : null
  }
}
