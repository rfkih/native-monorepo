/**
 * BillDock — the phone till's ALWAYS-ATTACHED bill (Native Till Android v2).
 *
 * The phone used to keep the ticket inside a sheet: tap an item, tap the chevron, a full-screen
 * sheet covers the catalog, check it, close it, repeat. The tablet answers that with a permanent
 * bill column; 412px has no such width — but it has height. So the bill becomes a bottom deck that
 * is always on screen: collapsed it peeks at the newest lines, the amount due and the pay button;
 * dragged up it becomes the whole ticket, its steppers, its price breakdown and its bill actions.
 * There is no "open the cart" step in any flow.
 *
 * Two callers render this ONE component: Pos.tsx for the local walk-in cart, BillDetail.tsx for an
 * open bill. They own completely different data and mutations, so everything here arrives as
 * already-shaped props — including every money string, formatted by the caller through
 * `formatMoney` (rule 9: no manual number concatenation, ever).
 *
 * pos-shell rule: stateless presentation — no fetching, no useState, so every vertical can reuse
 * it. The one exception is the scroll-to-newest effect below: it is pure DOM presentation with no
 * state of its own, and the alternative (a collapsed deck opening scrolled to the OLDEST line)
 * defeats the whole point of a deck you glance at after each tap.
 */
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Minus, Plus, Send } from 'lucide-react'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { AppliedPromotionChips, type AppliedPromotionEntry } from '@/components/AppliedPromotionChips'

/** One row of the ticket. The walk-in cart's local lines and a bill's server lines both map here. */
export interface DockLine {
  key: string
  name: string
  /** "2 × Rp 12.000" — pre-formatted by the caller. */
  unitLabel: string
  /** "Rp 24.000" — pre-formatted by the caller. */
  totalLabel: string
  qty: number
  paid: boolean
  /** Split mode: this row can be ticked into the check being charged now. */
  selectable: boolean
  selected: boolean
  /** Owner/manager (billPermissions.canRemoveBillLines) — gates the minus half of the stepper. */
  canRemove: boolean
  onInc?: () => void
  onDec?: () => void
  onToggleSelect?: () => void
}

/** An action chip in the expanded deck's rail (split, discount, member, park, attachments). */
export interface DockAction {
  key: string
  icon: React.ReactNode
  /** Already-translated (callers own their i18n namespaces). */
  label: string
  active?: boolean
  onClick: () => void
  disabled?: boolean
  /** Touch screens have no tooltips — a disabled chip explains itself in the caller's own copy. */
  disabledTitle?: string
}

/** The subtotal → discount → service → tax rows. The grand total is the footer's job, not a row. */
export interface DockBreakdown {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  /** Rules are cached/illustrative rather than server-confirmed → the "estimate" badge lights. */
  usesIllustrativeRules: boolean
}

export interface DockCancel {
  canCancel: boolean
  hintVisible: boolean
  /** Already-translated. */
  hint: string
  label: string
  onCancel: () => void
}

export function BillDock({
  title,
  meta,
  hasPaidLines = false,
  expanded,
  onExpandedChange,
  lines,
  emptyHint,
  splitMode = false,
  splitHint,
  actions = [],
  breakdown = null,
  promotions = [],
  extras,
  currency,
  locale,
  dueLabel,
  dueText,
  totalPending = false,
  sendLabel,
  onSend,
  payLabel,
  onPay,
  payDisabled = false,
  cancel = null,
  busy = false,
}: {
  /** The ticket's identity — "Table 07" or "Walk-in sale". */
  title: string
  /** "#A-1182 · 5 items". */
  meta: string
  hasPaidLines?: boolean
  expanded: boolean
  onExpandedChange: (next: boolean) => void
  /** Collapsed: the newest unpaid lines (lib/dockLines.peekLines). Expanded: the whole ticket. */
  lines: DockLine[]
  /** Shown in place of an empty list — an untouched cart is a normal state, not a void. */
  emptyHint: string
  splitMode?: boolean
  splitHint?: string
  actions?: DockAction[]
  breakdown?: DockBreakdown | null
  promotions?: AppliedPromotionEntry[]
  /** Expanded-only slot under the breakdown: the walk-in coupon / member / manual-discount stack. */
  extras?: React.ReactNode
  currency: string
  locale: string
  /** Already-translated label for the due figure (lib/dockLines.dueLabelKey picks the key). */
  dueLabel: string
  dueText: string
  /** The live quote is still settling — the figure may still be the previous cart's. */
  totalPending?: boolean
  /** Kitchen ticket. Omit both to hide the button (the walk-in cart has no KOT). */
  sendLabel?: string
  onSend?: () => void
  payLabel: string
  onPay: () => void
  payDisabled?: boolean
  /** Expanded-only cancel row. `null` where cancelling is not a concept (the walk-in cart). */
  cancel?: DockCancel | null
  /** A mutation is in flight — steppers and destructive actions go inert. */
  busy?: boolean
}) {
  const { t } = useTranslation()
  const listRef = useRef<HTMLDivElement>(null)

  // Collapsed, the list viewport is barely one row tall, so it rides at the BOTTOM — landing at the
  // top would show the oldest line while the cashier is looking for the one they just tapped.
  // Expanding inverts that: the whole ticket is now readable and it should read from line one, so
  // the deck must not inherit the peek's scroll position (which was pinned to the end).
  const newestKey = lines.length > 0 ? lines[lines.length - 1].key : ''
  useEffect(() => {
    const el = listRef.current
    if (!el) return
    el.scrollTop = expanded ? 0 : el.scrollHeight
  }, [newestKey, lines.length, expanded])

  return (
    <>
      {/* Scrim — expanded only. The deck is chrome when collapsed and modal when open. */}
      {expanded ? (
        <button
          type="button"
          aria-label={t('posShell.dock.collapse')}
          onClick={() => onExpandedChange(false)}
          className="scrim-in fixed inset-0 z-[29] cursor-default bg-scrim"
        />
      ) : null}

      <div
        id="pos-summary-dock"
        data-testid="pos-bill-dock"
        data-expanded={expanded}
        className={cn(
          // z-30 is the CHROME tier — the same one SummaryBar occupied, and the tier this deck
          // replaced it in. It must stay below the modal layer: the walk-in payment surface is
          // z-40 (PaymentSurfaceFrame's default), the POS overlays are z-50, dialogs z-[60]. The
          // deck shipped at z-[45], which is above z-40 and below z-50 — so it covered the bottom
          // of the Charge modal (its keypad and Finish button) and nothing else, which is exactly
          // how the bug presented. The scrim rides one below the deck, not at the modal tier.
          'fixed inset-x-0 bottom-0 z-30 flex flex-col overflow-hidden rounded-t-sheet',
          'border-t border-line bg-surface shadow-[0_-14px_34px_rgba(15,23,42,.16)]',
          'pb-[var(--safe-area-inset-bottom,0px)] motion-safe:transition-[height] motion-safe:duration-[260ms]',
          'motion-safe:ease-[cubic-bezier(.16,1,.3,1)]',
          // Peek height. The mockup pins 204px on a 412×915 reference, where the newest row ends
          // up clipped; 228px fits one whole row WITH its stepper (py-3 + a 44px control = 68px),
          // so the row the cashier just tapped is both visible and editable. Below 720px of viewport there is no room for a row AND the
          // catalog, so the deck drops to handle + footer (164px) and hides the list rather than
          // showing a cropped half-row — see the list's own variant below. The two thresholds are
          // deliberately the same number.
          expanded ? 'h-[min(620px,74dvh)]' : 'h-[164px] [@media(min-height:720px)]:h-[228px]',
        )}
      >
        {/* Handle. The WHOLE row is the toggle — grip, title and meta together, ≥44px — exactly
            as the mockup draws it, and nothing else lives on it. The order switcher used to sit
            here (first as the title itself, then as a button beside it); both competed with the
            ticket's name for a tap that almost always means "show me my order". Switching lives
            in the till menu now, and — in bill mode, where it is the only way back to the
            walk-in cart — as the first chip of the expanded action rail. */}
        <div className="flex shrink-0 items-stretch">
          <button
            type="button"
            data-testid="pos-dock-toggle"
            onClick={() => onExpandedChange(!expanded)}
            aria-expanded={expanded}
            aria-label={expanded ? t('posShell.dock.collapse') : t('posShell.dock.expand')}
            className="flex min-h-11 min-w-0 flex-1 flex-col items-center justify-center gap-1.5 pb-1.5 pl-4 pr-2 pt-2 text-left transition-colors active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
          >
            <span className="h-1 w-10 rounded-full bg-ink-300" aria-hidden="true" />
            <span className="flex w-full items-baseline gap-1.5">
              <span className="truncate text-sm font-bold leading-none text-ink">{title}</span>
              <span className="truncate text-xs font-medium leading-none text-ink-3">{meta}</span>
              <span className="flex-1" />
              {hasPaidLines ? (
                <span className="grid h-[19px] shrink-0 place-items-center self-center rounded-full border border-profit-line px-1.5 text-2xs font-bold text-profit-ink">
                  {t('posShell.dock.partiallyPaid')}
                </span>
              ) : null}
            </span>
          </button>
        </div>

        {/* Action rail — expanded only. */}
        {expanded && actions.length > 0 ? (
          <div className="flex shrink-0 gap-2 overflow-x-auto border-b border-line px-4 pb-2.5 pt-2 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            {actions.map((a) => (
              <button
                key={a.key}
                type="button"
                data-testid={`pos-dock-action-${a.key}`}
                onClick={a.onClick}
                disabled={a.disabled}
                aria-pressed={a.active}
                title={a.disabled ? (a.disabledTitle ?? a.label) : undefined}
                className={cn(
                  'flex h-11 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl border px-3 text-xs font-semibold transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                  a.active
                    ? 'border-emerald bg-emerald text-on-emerald'
                    : 'border-line bg-surface text-ink-2 hover:bg-hover',
                  a.disabled && 'cursor-not-allowed opacity-40 hover:bg-surface',
                )}
              >
                {a.icon}
                {a.label}
              </button>
            ))}
          </div>
        ) : null}

        {/* Split explainer — the mode is not obvious from ticked boxes alone. */}
        {expanded && splitMode && splitHint ? (
          <p className="shrink-0 border-b border-line bg-paper px-4 py-2.5 text-xs leading-[1.45] text-ink-2">
            {splitHint}
          </p>
        ) : null}

        {/* Lines */}
        <div
          ref={listRef}
          className={cn(
            'min-h-0 flex-1 overflow-y-auto overscroll-contain',
            // Short viewport, collapsed: no row fits, so show none (see the height note above).
            !expanded && '[@media(max-height:719px)]:hidden',
          )}
        >
          {lines.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-ink-3">{emptyHint}</p>
          ) : (
            <ul>
              {lines.map((l) => (
                <li
                  key={l.key}
                  className={cn(
                    'flex items-center gap-3 border-b border-line px-4 py-3',
                    splitMode && l.selected && !l.paid && 'bg-paper',
                    splitMode && l.paid && 'opacity-50',
                  )}
                >
                  {splitMode && l.selectable ? (
                    <button
                      type="button"
                      onClick={l.onToggleSelect}
                      aria-pressed={l.selected}
                      aria-label={l.name}
                      className="-ml-2.5 grid size-11 shrink-0 place-items-center rounded-xl focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
                    >
                      <span
                        className={cn(
                          'grid size-[22px] place-items-center rounded-md border-2 transition-colors',
                          l.selected ? 'border-emerald bg-emerald' : 'border-ink-300',
                        )}
                      >
                        {l.selected ? (
                          <Check className="size-3 text-on-emerald" strokeWidth={3.4} aria-hidden="true" />
                        ) : null}
                      </span>
                    </button>
                  ) : null}

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span
                        className={cn(
                          'truncate text-sm font-medium leading-[1.35]',
                          l.paid ? 'text-ink-3' : 'text-ink',
                        )}
                      >
                        {l.name}
                      </span>
                      {l.paid ? (
                        <span className="grid h-[18px] shrink-0 place-items-center rounded-full border border-profit-line px-1.5 text-2xs font-bold text-profit-ink">
                          {t('posShell.dock.linePaid')}
                        </span>
                      ) : null}
                    </div>
                    <div className="tnum mt-0.5 font-mono text-xs leading-[1.3] text-ink-3">
                      {l.unitLabel}
                    </div>
                  </div>

                  {/* Stepper — never on a settled line, never mid-split. The mockup drew it
                      expanded-only; it shows in the peek as well because the single most common
                      edit is undoing the tap you just made, and the peek is where you see it. */}
                  {!splitMode && !l.paid ? (
                    <div className="flex shrink-0 items-center overflow-hidden rounded-xl border border-line">
                      {l.canRemove ? (
                        <button
                          type="button"
                          onClick={l.onDec}
                          disabled={busy}
                          aria-label={t('pos.decreaseQty', { name: l.name })}
                          className="grid size-11 place-items-center bg-surface text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald disabled:opacity-40"
                        >
                          <Minus className="size-4" strokeWidth={2.4} aria-hidden="true" />
                        </button>
                      ) : null}
                      <span className="tnum min-w-[30px] text-center font-mono text-sm font-semibold text-ink">
                        {l.qty}
                      </span>
                      <button
                        type="button"
                        onClick={l.onInc}
                        disabled={busy}
                        aria-label={t('pos.increaseQty', { name: l.name })}
                        className="grid size-11 place-items-center bg-surface text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald disabled:opacity-40"
                      >
                        <Plus className="size-4" strokeWidth={2.4} aria-hidden="true" />
                      </button>
                    </div>
                  ) : null}

                  <div
                    className={cn(
                      'tnum min-w-[76px] shrink-0 text-right font-mono text-sm leading-[1.35]',
                      l.paid ? 'text-ink-3' : 'text-ink',
                    )}
                  >
                    {l.totalLabel}
                  </div>
                </li>
              ))}
            </ul>
          )}

          {/* Breakdown + promo chips + the walk-in coupon/member/discount stack — expanded only. */}
          {expanded && (breakdown || promotions.length > 0 || extras) ? (
            <div className="flex flex-col gap-2.5 border-t border-line px-4 pb-4 pt-3.5">
              {breakdown ? (
                <>
                  <BreakdownRow
                    label={t('pos.subtotal')}
                    value={formatMoney(breakdown.subtotalMinor, currency, locale)}
                  />
                  {breakdown.discountMinor > 0 ? (
                    <BreakdownRow
                      label={t('pos.discount')}
                      value={`− ${formatMoney(breakdown.discountMinor, currency, locale)}`}
                      tone="loss"
                    />
                  ) : null}
                  <BreakdownRow
                    label={t('pos.serviceCharge')}
                    value={formatMoney(breakdown.serviceChargeMinor, currency, locale)}
                    estimated={breakdown.usesIllustrativeRules}
                  />
                  <BreakdownRow
                    label={t('pos.tax')}
                    value={formatMoney(breakdown.taxMinor, currency, locale)}
                    estimated={breakdown.usesIllustrativeRules}
                  />
                </>
              ) : null}
              <AppliedPromotionChips promotions={promotions} currency={currency} locale={locale} />
              {extras}
            </div>
          ) : null}
        </div>

        {/* Footer — the due figure and the verbs. Present in BOTH states: this is why the deck
            exists, and it must never scroll out of reach. */}
        <div className="shrink-0 border-t border-line bg-surface px-3.5 pb-4 pt-2.5">
          <div className="flex items-baseline justify-between px-0.5 pb-2.5">
            <span className="text-sm font-bold leading-none text-ink">{dueLabel}</span>
            <span
              data-testid="pos-dock-due"
              aria-busy={totalPending}
              className={cn(
                'tnum text-2xl font-extrabold leading-none tracking-display text-ink transition-opacity',
                totalPending && 'animate-pulse opacity-50',
              )}
            >
              {dueText}
            </span>
          </div>
          <div className="flex gap-2.5">
            {sendLabel && onSend ? (
              <button
                type="button"
                data-testid="pos-send"
                onClick={onSend}
                disabled={busy}
                className="flex h-14 shrink-0 items-center gap-2 rounded-2xl border border-line bg-surface px-4 text-sm font-bold text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
              >
                <Send className="size-[17px]" aria-hidden="true" />
                {sendLabel}
              </button>
            ) : null}
            <button
              type="button"
              data-testid="pos-pay"
              onClick={onPay}
              disabled={payDisabled}
              className={cn(
                'h-14 flex-1 rounded-2xl bg-emerald px-4 text-base font-bold text-on-emerald',
                'transition-transform active:scale-[.985] disabled:opacity-40 disabled:active:scale-100',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              )}
            >
              <span className={totalPending ? 'animate-pulse opacity-70' : undefined}>{payLabel}</span>
            </button>
          </div>

          {/* Cancel — expanded only, and never mid-split. */}
          {expanded && !splitMode && cancel ? (
            <div className="pt-2.5 text-center">
              {cancel.canCancel ? (
                <button
                  type="button"
                  onClick={cancel.onCancel}
                  disabled={busy}
                  className="min-h-11 rounded-xl px-3.5 text-xs font-semibold text-loss-ink transition-colors hover:bg-tint-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
                >
                  {cancel.label}
                </button>
              ) : cancel.hintVisible ? (
                // Touch screens have no tooltips — the lockdown explains itself in-line.
                <p className="px-2 text-xs leading-[1.45] text-ink-3">{cancel.hint}</p>
              ) : null}
            </div>
          ) : null}
        </div>
      </div>
    </>
  )
}

function BreakdownRow({
  label,
  value,
  tone,
  estimated = false,
}: {
  label: string
  value: string
  tone?: 'loss'
  estimated?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center justify-between gap-2.5">
      <span className="flex items-center gap-1.5 text-sm font-medium leading-[1.3] text-ink-3">
        {label}
        {estimated ? (
          <span className="grid h-[18px] place-items-center rounded-full border border-warning-line px-1.5 text-2xs font-bold text-amber">
            {t('posShell.dock.estimated')}
          </span>
        ) : null}
      </span>
      <span className={cn('tnum font-mono text-sm font-semibold', tone === 'loss' ? 'text-loss' : 'text-ink')}>
        {value}
      </span>
    </div>
  )
}
