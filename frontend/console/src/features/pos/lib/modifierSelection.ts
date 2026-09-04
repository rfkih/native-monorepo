/**
 * Pure selection reducer for the modifier modal (ModifierModal.tsx).
 *
 * A SINGLE modifier group holds at most one option id. An *optional* single-select group
 * can also be cleared — the cashier re-taps the chosen option to "skip" the group — which
 * surfaces as an empty option id (`''`) from ChoiceCards (a radio does not emit `change` on
 * re-click, so deselect is wired via `onClick` and reported as `''`). A *required* group is
 * never cleared, so it never receives `''`.
 *
 * Mapping the empty id to an *empty* Set (not `Set([''])`) is load-bearing: a phantom `''`
 * id would otherwise flow through collectOptionIds() into the confirmed line and to the server.
 */
export function singleSelectionSet(optionId: string): Set<string> {
  return optionId ? new Set([optionId]) : new Set()
}
