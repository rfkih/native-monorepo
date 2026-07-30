/**
 * Plain (non-component) promotions helpers: day-of-week bitmask <-> day-index set, HH:mm:ss <-> HH:mm
 * time-input conversion, locale-aware weekday/time labels (Intl — rule 9, never a hand-translated
 * weekday name table), and RFC-7807 problem mapping to i18n keys.
 */
import { ApiError } from '@/lib/api'

/** Bit 0 = Monday .. bit 6 = Sunday (ADR 0026 / PromotionEngineService). */
export function dowMaskToDays(mask: number | null): Set<number> {
  const days = new Set<number>()
  if (mask == null) return days
  for (let bit = 0; bit < 7; bit++) {
    if ((mask & (1 << bit)) !== 0) days.add(bit)
  }
  return days
}

export function daysToDowMask(days: Set<number>): number | null {
  if (days.size === 0) return null
  let mask = 0
  for (const d of days) mask |= 1 << d
  return mask
}

/** 'HH:mm:ss' (wire) -> 'HH:mm' (the <input type="time"> value). */
export function toTimeInputValue(hhmmss: string | null): string {
  if (!hhmmss) return ''
  return hhmmss.slice(0, 5)
}

/** 'HH:mm' (the <input type="time"> value) -> 'HH:mm:ss' (wire), or null when blank. */
export function fromTimeInputValue(hhmm: string): string | null {
  if (!hhmm) return null
  return `${hhmm}:00`
}

// A reference Monday (2024-01-01 is a Monday) — used only to hand a real Date to Intl for a
// locale-correct short weekday label; never displayed itself.
const REFERENCE_MONDAY = new Date(Date.UTC(2024, 0, 1))

/** Locale-correct short weekday label for a bit index (0 = Monday .. 6 = Sunday), via Intl. */
export function weekdayShortLabel(dayIndex: number, locale: string): string {
  const d = new Date(REFERENCE_MONDAY)
  d.setUTCDate(d.getUTCDate() + dayIndex)
  return new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' }).format(d)
}

/** Locale-correct time-of-day label for an 'HH:mm:ss' wire string, via Intl. */
export function timeOfDayLabel(hhmmss: string, locale: string): string {
  const [h, m] = hhmmss.split(':').map((n) => Number(n))
  const d = new Date(Date.UTC(2024, 0, 1, h, m))
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(d)
}

export function promoRuleErrorKey(
  err: unknown,
): 'promotions.errors.ruleInvalid' | 'promotions.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 422 && type.includes('promo-rule-invalid')) {
      return 'promotions.errors.ruleInvalid'
    }
  }
  return 'promotions.errors.generic'
}

export function couponErrorKey(
  err: unknown,
):
  | 'promotions.errors.ruleInvalid'
  | 'promotions.errors.duplicateCoupon'
  | 'promotions.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 422 && type.includes('promo-rule-invalid')) {
      return 'promotions.errors.ruleInvalid'
    }
    if (err.status === 409 && type.includes('promotion-conflict')) {
      return 'promotions.errors.duplicateCoupon'
    }
  }
  return 'promotions.errors.generic'
}
