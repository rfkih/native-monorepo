/**
 * lineKey.ts — cart-line identity, extracted from Pos.tsx (redesign P1).
 *
 * Two taps of the same item with the same option set merge into one cart line; the option ids
 * are sorted so selection ORDER never splits a line.
 */
export function lineKey(menuItemId: string, selectedOptionIds: string[]): string {
  return `${menuItemId}::${[...selectedOptionIds].sort().join(',')}`
}
