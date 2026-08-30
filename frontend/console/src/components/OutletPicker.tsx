/**
 * OutletPicker — compact outlet selector for POS, Menu, and Kitchen headers.
 *
 * Rules:
 * - 0 active outlets → renders nothing (the OutletGate blocks the surface — there is no
 *   fallback to the company's first business since ADR 0012).
 * - ≥1 outlets → renders a button (bg-emerald-tint, 1.5px border-emerald, outlet name + chevron)
 *   that opens an accessible dropdown listing outlets; the currently active one is checked.
 * - If outlets exist but none is selected (or the stored id is stale/foreign), defaults to the
 *   FIRST outlet (alphabetical by name) and persists it — the terminal is never left ambiguous.
 * - Selecting an outlet persists it to sessionStorage for this tab and closes the dropdown.
 * - Switching from one non-null outlet to another shows a lightweight confirm so the cashier
 *   is aware that unsent terminal state will be cleared by the remount.
 */

import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronDown, Store } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { cn } from '@/lib/cn'
import { useSession } from '@/lib/session'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'

// ---------------------------------------------------------------------------
// OutletPicker component
// ---------------------------------------------------------------------------

export function OutletPicker() {
  const { t } = useTranslation()
  const { company, activeOutletId, setActiveOutlet } = useSession()
  // Shared resolution (company outlets ∩ own assignments + self-heal) — the same hook the
  // OutletGate renders on, so the picker and the gate can never disagree (ADR 0012).
  const { outlets } = useResolvedOutlets()

  const [open, setOpen] = useState(false)
  // Pending outlet id — waiting for user to confirm the switch.
  const [pendingId, setPendingId] = useState<string | null>(null)

  const triggerRef = useRef<HTMLButtonElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // Close on outside click or Escape; return focus to trigger on close.
  useEffect(() => {
    if (!open) return
    function handleOutsideClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    function handleEscape(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('mousedown', handleOutsideClick)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handleOutsideClick)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [open])

  // Back/hardware-back dismisses the pending switch-confirm, not the outlet dropdown itself.
  useBackDismiss(cancelSwitch, pendingId != null)

  // No outlets → no picker (the OutletGate blocks the surface in that state; this guard is
  // defense-in-depth for the brief window while queries resolve).
  if (!company || outlets.length === 0) return null

  // The displayed outlet: prefer the stored id; fall back to first (while self-heal effect fires).
  const activeOutlet = outlets.find((o) => o.id === activeOutletId) ?? outlets[0]

  function requestSwitch(targetId: string) {
    if (targetId === activeOutlet.id) {
      // Same outlet — just close.
      setOpen(false)
      triggerRef.current?.focus()
      return
    }
    if (activeOutletId !== null) {
      // Switching from one non-null outlet to another — ask first.
      setPendingId(targetId)
      setOpen(false)
    } else {
      setActiveOutlet(targetId)
      setOpen(false)
      triggerRef.current?.focus()
    }
  }

  function confirmSwitch() {
    if (pendingId) {
      setActiveOutlet(pendingId)
    }
    setPendingId(null)
    triggerRef.current?.focus()
  }

  function cancelSwitch() {
    setPendingId(null)
    triggerRef.current?.focus()
  }

  return (
    <>
      <div ref={containerRef} className="relative min-w-0 max-w-full">
        {/* Trigger button */}
        <button
          ref={triggerRef}
          type="button"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-label={t('pos.outletSelectorLabel', { name: activeOutlet.name })}
          onClick={() => setOpen((v) => !v)}
          className={cn(
            'flex h-[40px] w-full min-w-0 max-w-[200px] items-center gap-2 rounded-xl',
            'border-[1.5px] border-emerald-line bg-emerald-tint px-3 text-left',
            'text-[13px] font-semibold text-emerald-2',
            'transition-all hover:bg-emerald-tint/70',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
          )}
        >
          <Store className="size-3.5 shrink-0 text-emerald-2" aria-hidden="true" />
          <span className="min-w-0 truncate">{activeOutlet.name}</span>
          <ChevronDown
            className={cn('size-3.5 shrink-0 transition-transform', open && 'rotate-180')}
            aria-hidden="true"
          />
        </button>

        {/* Dropdown */}
        {open ? (
          <div
            role="listbox"
            aria-label={t('pos.outletSelectorAriaLabel')}
            className={cn(
              'absolute left-0 top-[calc(100%+6px)] z-50 min-w-[200px] overflow-hidden',
              'rounded-xl border border-line bg-surface shadow-xl',
              'motion-safe:animate-in motion-safe:fade-in-0 motion-safe:zoom-in-95',
            )}
          >
            {outlets.map((outlet) => {
              const isActive = outlet.id === activeOutlet.id
              return (
                <button
                  key={outlet.id}
                  type="button"
                  role="option"
                  aria-selected={isActive}
                  onClick={() => {
                    requestSwitch(outlet.id)
                  }}
                  className={cn(
                    'flex h-11 w-full items-center gap-3 px-4 text-left text-[14px] transition-colors',
                    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
                    isActive
                      ? 'bg-emerald-tint font-semibold text-emerald-2'
                      : 'text-ink hover:bg-hover',
                  )}
                >
                  <span className="min-w-0 flex-1 truncate">{outlet.name}</span>
                  {isActive ? (
                    <Check className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
                  ) : null}
                </button>
              )
            })}
          </div>
        ) : null}
      </div>

      {/* Confirm-switch dialog — shown without coupling to POS cart internals */}
      {pendingId ? (
        <div
          className="fixed inset-0 z-[70] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          aria-label={t('pos.switchOutletConfirmTitle')}
        >
          <div className="w-full max-w-xs rounded-[20px] border border-line bg-surface p-6 shadow-xl">
            <p className="text-[15px] font-semibold text-ink">
              {t('pos.switchOutletConfirmTitle')}
            </p>
            <p className="mt-2 text-sm text-ink-3">
              {t('pos.switchOutletConfirmBody')}
            </p>
            <div className="mt-5 flex gap-2">
              <button
                type="button"
                onClick={cancelSwitch}
                className="flex-1 rounded-xl border border-line bg-surface py-2.5 text-sm font-semibold text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                {t('common.cancel')}
              </button>
              <button
                type="button"
                onClick={confirmSwitch}
                className="flex-1 rounded-xl bg-emerald py-2.5 text-sm font-semibold text-on-emerald transition-colors hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                {t('pos.switchOutletConfirmAction')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}
