/**
 * useIsPhone — the console-wide phone breakpoint: true below Tailwind's `sm` (640px),
 * matching the POS phone cutoff (BillDetail's `isTablet`) so class-based `sm:` styling
 * and JS branches flip at exactly the same width.
 */
import { useMediaQuery } from '@/features/pos/lib/useMediaQuery'

export function useIsPhone(): boolean {
  return !useMediaQuery('(min-width: 640px)')
}
