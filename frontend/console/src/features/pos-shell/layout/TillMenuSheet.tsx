/**
 * TillMenuSheet — the till-menu overflow of the POS status bar (redesign P4): the per-DAY
 * actions (customer display, gift cards, back-office links, theme, sign out) that used to
 * crowd the header as 10 always-visible icons. Anchored top-right under the band.
 *
 * Focus contract (the SheetOverlay rules): initial focus on the panel, Escape closes, backdrop
 * click closes, phone/browser Back closes (useBackDismiss). pos-shell rule: stateless — items and
 * their handlers come from the caller.
 */
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { cn } from '@/lib/cn'

export interface TillMenuItem {
  key: string
  icon: React.ReactNode
  /** Already-translated label. */
  label: string
  /** Either an action… */
  onSelect?: () => void
  /** …or a route link. */
  to?: string
  disabled?: boolean
  /** Tooltip while disabled (e.g. the offline reason). */
  disabledTitle?: string
  /** Renders in the loss color (sign out). */
  danger?: boolean
}

export function TillMenuSheet({ items, onClose }: { items: TillMenuItem[]; onClose: () => void }) {
  const { t } = useTranslation()
  const panelRef = useRef<HTMLDivElement>(null)

  useBackDismiss(onClose)
  useScrollLock()
  useEffect(() => {
    panelRef.current?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const itemClass = (it: TillMenuItem) =>
    cn(
      'flex min-h-11 w-full items-center gap-3 rounded-xl px-3 py-1.5 text-left text-[14px] font-medium transition-colors',
      'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
      it.danger ? 'text-loss hover:bg-tint-loss' : 'text-ink hover:bg-hover',
      it.disabled && 'cursor-not-allowed opacity-40 hover:bg-transparent',
    )

  // Touch devices never surface title= tooltips, so a disabled row must EXPLAIN itself in-line —
  // otherwise the Android till shows a greyed button with no visible reason (e.g. while offline).
  const itemText = (it: TillMenuItem) => (
    <span className="min-w-0 flex-1">
      <span className="block truncate">{it.label}</span>
      {it.disabled && it.disabledTitle ? (
        <span className="block truncate text-[11px] font-normal text-ink-3">{it.disabledTitle}</span>
      ) : null}
    </span>
  )

  return (
    <div className="fixed inset-0 z-50" role="presentation">
      {/* Transparent backdrop — the band stays visible; outside click closes. */}
      <button type="button" aria-label={t('common.close')} onClick={onClose} className="absolute inset-0 cursor-default" />
      <div
        ref={panelRef}
        tabIndex={-1}
        role="menu"
        aria-label={t('posShell.tillMenu')}
        className="motion-safe:animate-in motion-safe:fade-in-0 motion-safe:zoom-in-95 absolute right-3 top-[60px] w-64 rounded-2xl border border-line bg-surface p-1.5 shadow-lg outline-none"
      >
        {items.map((it) =>
          it.to && !it.disabled ? (
            <Link key={it.key} to={it.to} role="menuitem" className={itemClass(it)} onClick={onClose}>
              <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-ink-50 text-ink-2">{it.icon}</span>
              {itemText(it)}
            </Link>
          ) : (
            <button
              key={it.key}
              type="button"
              role="menuitem"
              disabled={it.disabled}
              title={it.disabled ? (it.disabledTitle ?? it.label) : undefined}
              onClick={() => {
                if (it.disabled) return
                onClose()
                it.onSelect?.()
              }}
              className={itemClass(it)}
            >
              <span
                className={cn(
                  'grid size-8 shrink-0 place-items-center rounded-lg',
                  it.danger ? 'bg-tint-loss text-loss' : 'bg-ink-50 text-ink-2',
                )}
              >
                {it.icon}
              </span>
              {itemText(it)}
            </button>
          ),
        )}
      </div>
    </div>
  )
}
