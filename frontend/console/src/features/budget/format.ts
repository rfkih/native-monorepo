/** Plain (non-component) budget helpers: maps RFC-7807 problems to friendly i18n keys. */

import { ApiError } from '@/lib/api'

export function budgetErrorKey(
  err: unknown,
):
  | 'budget.errors.unknownAccount'
  | 'budget.errors.duplicate'
  | 'budget.errors.invalid'
  | 'budget.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 400 && type.includes('budget-unknown-account')) {
      return 'budget.errors.unknownAccount'
    }
    if (err.status === 409 && type.includes('budget-conflict')) return 'budget.errors.duplicate'
    if (err.status === 400) return 'budget.errors.invalid'
  }
  return 'budget.errors.generic'
}
