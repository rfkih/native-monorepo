/**
 * assignments.ts — the pure predicate behind the HR employee drawer's "Penugasan / Assignments"
 * section.
 *
 * `GET /api/v1/employees` returns one row per (employee × assignment CURRENT as of today) — the
 * backend's window check is `effective_from <= asOf AND effective_to >= asOf`. That means an
 * assignment that has already been ended (via "Akhiri", which sets a definite `effectiveTo`) can
 * still satisfy that window — and so still appear in the list — for as long as its chosen end date
 * hasn't passed yet (e.g. ended "today" or a few days out). The codebase's own convention for
 * "active/open" (see `parts.tsx`'s uses of `OPEN_ENDED`, e.g. `TerminateDialog`) is the far-future
 * sentinel: a row only counts as genuinely active while its `effectiveTo` is still the sentinel —
 * anything else has been given a real end date and should read as ended, not active.
 *
 * Kept React-free (no hooks, no components) so it is trivially unit-testable — mirrors
 * `accessRoleMatch.ts`'s own pure-predicate style.
 */
import { OPEN_ENDED } from './api'

export interface AssignmentLike {
  assignmentId: string | null
  effectiveTo: string | null
}

/**
 * True only for a row that both HAS an assignment (the `/api/v1/employees` LEFT JOIN leaves
 * `assignmentId`/`effectiveTo` null when an employee has none in scope) and whose `effectiveTo` is
 * still the open-ended sentinel — i.e. never given a real end date.
 */
export function isActiveAssignment(row: AssignmentLike): boolean {
  return row.assignmentId != null && row.effectiveTo === OPEN_ENDED
}
