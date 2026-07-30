/**
 * CouponField — shared POS coupon apply/clear control (Phase 3, ADR 0026). Used by BOTH the
 * restaurant POS (features/pos/Pos.tsx) and the service POS (features/servicepos/ServicePos.tsx) —
 * lives in components/ (not inside either feature) so neither imports the other's tree.
 *
 * The caller owns the committed `code` (threaded into useQuote/checkout as `couponCode`) and the
 * `status` the last live quote resolved it to (`breakdown.couponStatus`) — this component is a
 * controlled, presentation-only apply/clear affordance:
 *   - no code committed  → a text input + "Apply" button (Enter also applies).
 *   - a code committed   → a chip showing the code + the resolved status:
 *       APPLIED           → a green chip (a quote never throws for a coupon — ADR 0026 §4).
 *       INVALID/EXHAUSTED → an amber/loss chip + inline error copy.
 *       null (still resolving after Apply, or cart empty) → a neutral pending chip.
 *
 * Strings rule (rule 9): every label is an i18n key.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, TriangleAlert, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { cn } from '@/lib/cn'

export type CouponStatus = 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null

interface Props {
  /** The committed coupon code (parent state fed into the quote/checkout calls), or null. */
  code: string | null
  /** The last live quote's resolved couponStatus for `code` — null while resolving / no code. */
  status: CouponStatus
  onApply: (code: string) => void
  onClear: () => void
  disabled?: boolean
  className?: string
}

export function CouponField({ code, status, onApply, onClear, disabled, className }: Props) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState('')

  function submit() {
    const trimmed = draft.trim().toUpperCase()
    if (!trimmed || disabled) return
    onApply(trimmed)
    setDraft('')
  }

  if (!code) {
    return (
      <div className={cn('flex items-center gap-2', className)}>
        <input
          type="text"
          value={draft}
          disabled={disabled}
          onChange={(e) => setDraft(e.target.value.toUpperCase())}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t('pos.coupon.placeholder')}
          aria-label={t('pos.coupon.label')}
          className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 font-mono text-[13px] uppercase tracking-wide text-ink placeholder:font-sans placeholder:normal-case placeholder:tracking-normal placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
        <button
          type="button"
          onClick={submit}
          disabled={disabled || draft.trim() === ''}
          className="h-10 shrink-0 rounded-xl border border-emerald-line bg-emerald-tint px-3.5 text-[13px] font-bold text-emerald-2 transition-colors hover:bg-brand-100/60 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {t('pos.coupon.apply')}
        </button>
      </div>
    )
  }

  const tone = status === 'APPLIED' ? 'profit' : status === 'INVALID' || status === 'EXHAUSTED' ? 'loss' : 'neutral'

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <div className="flex items-center gap-2">
        <Badge tone={tone} className="flex-1 justify-between px-3 py-1.5 text-[13px]">
          <span className="flex min-w-0 items-center gap-1.5">
            {status === 'APPLIED' ? (
              <CheckCircle2 className="size-3.5 shrink-0" aria-hidden />
            ) : status === 'INVALID' || status === 'EXHAUSTED' ? (
              <TriangleAlert className="size-3.5 shrink-0" aria-hidden />
            ) : null}
            <span className="truncate font-mono">{code}</span>
          </span>
          {status === 'APPLIED' ? <span className="shrink-0 font-semibold">{t('pos.coupon.applied')}</span> : null}
        </Badge>
        <button
          type="button"
          onClick={onClear}
          disabled={disabled}
          aria-label={t('pos.coupon.clear')}
          className="grid size-9 shrink-0 place-items-center rounded-xl text-ink-3 transition-colors hover:bg-hover hover:text-loss disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <X className="size-4" />
        </button>
      </div>
      {status === 'INVALID' ? (
        <p className="text-xs text-loss" role="alert">
          {t('pos.coupon.invalid')}
        </p>
      ) : status === 'EXHAUSTED' ? (
        <p className="text-xs text-loss" role="alert">
          {t('pos.coupon.exhausted')}
        </p>
      ) : null}
    </div>
  )
}
