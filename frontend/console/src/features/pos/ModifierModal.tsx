/**
 * ModifierModal — lets the cashier pick modifier options before adding an item to the cart.
 *
 * Rules enforced client-side (server also validates):
 *   SINGLE group  → exactly 1 selection (radio via ChoiceCards).
 *   MULTI group   → between minSelect and maxSelect selections (checkable cards).
 *   required      → group must have at least 1 selection before "Add to cart" is enabled.
 *
 * Money rule (rule 8): all price deltas are integer minor units.
 * Strings rule (rule 9): all labels are i18n keys — no hardcoded text.
 */
import { useState, useId } from 'react'
import { useTranslation } from 'react-i18next'
import { X, Check } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { ChoiceCards } from '@/components/ui/ChoiceCards'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { MenuItem, ModifierGroupResponse, ModifierOptionResponse } from './api'

interface Props {
  item: MenuItem
  locale: string
  onConfirm: (selectedOptionIds: string[], effectiveUnitPriceMinor: number) => void
  onClose: () => void
}

/**
 * Compute effective unit price: base price + sum of selected option deltas.
 */
function effectivePrice(
  basePriceMinor: number,
  groups: ModifierGroupResponse[],
  selections: Map<string, Set<string>>,
): number {
  let delta = 0
  for (const group of groups) {
    const sel = selections.get(group.id)
    if (!sel) continue
    for (const opt of group.options) {
      if (sel.has(opt.id)) delta += opt.priceDeltaMinor
    }
  }
  return basePriceMinor + delta
}

/**
 * Collect all selected option IDs across all groups.
 */
function collectOptionIds(
  groups: ModifierGroupResponse[],
  selections: Map<string, Set<string>>,
): string[] {
  const ids: string[] = []
  for (const group of groups) {
    const sel = selections.get(group.id)
    if (!sel) continue
    for (const id of sel) ids.push(id)
  }
  return ids
}

/**
 * Validate that all required groups meet minSelect.
 * Returns null when valid; returns an error key string when invalid.
 */
function validate(
  groups: ModifierGroupResponse[],
  selections: Map<string, Set<string>>,
): boolean {
  for (const group of groups) {
    const sel = selections.get(group.id)
    const count = sel?.size ?? 0
    if (group.required && count < Math.max(1, group.minSelect)) return false
    if (!group.required && count > 0 && count < group.minSelect) return false
    if (count > group.maxSelect) return false
  }
  return true
}

export function ModifierModal({ item, locale, onConfirm, onClose }: Props) {
  const { t } = useTranslation()

  // Map: groupId → Set<optionId>
  const [selections, setSelections] = useState<Map<string, Set<string>>>(() => {
    const m = new Map<string, Set<string>>()
    for (const g of item.modifierGroups) {
      m.set(g.id, new Set())
    }
    return m
  })

  const isValid = validate(item.modifierGroups, selections)
  const effectivePriceMinor = effectivePrice(item.priceMinor, item.modifierGroups, selections)

  function handleConfirm() {
    if (!isValid) return
    onConfirm(collectOptionIds(item.modifierGroups, selections), effectivePriceMinor)
  }

  function setSingleSelection(groupId: string, optionId: string) {
    setSelections((prev) => {
      const next = new Map(prev)
      next.set(groupId, new Set([optionId]))
      return next
    })
  }

  function toggleMultiSelection(group: ModifierGroupResponse, optionId: string) {
    setSelections((prev) => {
      const next = new Map(prev)
      const current = new Set(next.get(group.id) ?? [])
      if (current.has(optionId)) {
        current.delete(optionId)
      } else {
        // Enforce maxSelect: if already at limit, remove oldest selection
        if (current.size >= group.maxSelect) {
          const [first] = current
          current.delete(first)
        }
        current.add(optionId)
      }
      next.set(group.id, current)
      return next
    })
  }

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.modifiers.modalLabel', { name: item.name })}
    >
      <Card className="reveal flex max-h-full w-full max-w-md flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-start justify-between border-b border-line px-5 py-4">
          <div className="min-w-0 pr-3">
            <h2 className="font-display text-lg font-semibold text-ink">{item.name}</h2>
            <p className="mt-0.5 text-sm text-ink-3">{t('pos.modifiers.chooseOptions')}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Groups */}
        <div className="min-h-0 max-h-[60vh] overflow-y-auto overscroll-contain">
          {item.modifierGroups
            .slice()
            .sort((a, b) => a.displayOrder - b.displayOrder)
            .map((group) => (
              <GroupSection
                key={group.id}
                group={group}
                selections={selections.get(group.id) ?? new Set()}
                currency={item.currency}
                locale={locale}
                onSingleSelect={(optionId) => setSingleSelection(group.id, optionId)}
                onMultiToggle={(optionId) => toggleMultiSelection(group, optionId)}
              />
            ))}
        </div>

        {/* Footer */}
        <div className="border-t border-line px-5 py-4">
          <div className="mb-3 flex items-baseline justify-between text-sm">
            <span className="text-ink-3">{t('pos.modifiers.unitPrice')}</span>
            <span className="tnum font-mono font-medium text-ink">
              {formatMoney(effectivePriceMinor, item.currency, locale)}
            </span>
          </div>
          <Button className="w-full" disabled={!isValid} onClick={handleConfirm}>
            {t('pos.modifiers.addToCart')}
          </Button>
        </div>
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// GroupSection
// ---------------------------------------------------------------------------

function GroupSection({
  group,
  selections,
  currency,
  locale,
  onSingleSelect,
  onMultiToggle,
}: {
  group: ModifierGroupResponse
  selections: Set<string>
  currency: string
  locale: string
  onSingleSelect: (optionId: string) => void
  onMultiToggle: (optionId: string) => void
}) {
  const { t } = useTranslation()
  const headingId = useId()

  const availableOptions = group.options
    .filter((o) => o.available)
    .sort((a, b) => a.displayOrder - b.displayOrder)

  const hintText = group.required
    ? t('pos.modifiers.required')
    : t('pos.modifiers.optional')

  // For MULTI, compute validation hints
  const selCount = selections.size
  const showMinHint =
    group.selectionType === 'MULTI' && group.minSelect > 1 && selCount < group.minSelect
  const showMaxHint =
    group.selectionType === 'MULTI' && group.maxSelect > 1

  return (
    <div className="border-b border-line px-5 py-4 last:border-b-0" aria-labelledby={headingId}>
      <div className="mb-3 flex items-center gap-2">
        <h3 id={headingId} className="font-medium text-ink">
          {group.name}
        </h3>
        <Badge tone={group.required ? 'amber' : 'neutral'} className="text-[11px]">
          {hintText}
        </Badge>
        {showMaxHint ? (
          <span className="ml-auto text-xs text-ink-3">
            {t('pos.modifiers.selectUpTo', { n: group.maxSelect })}
          </span>
        ) : null}
      </div>

      {showMinHint ? (
        <p className="mb-2 text-xs text-amber-2" role="alert">
          {t('pos.modifiers.selectAtLeast', { n: group.minSelect })}
        </p>
      ) : null}

      {group.selectionType === 'SINGLE' ? (
        <SingleGroup
          group={group}
          selected={[...selections][0] ?? ''}
          availableOptions={availableOptions}
          currency={currency}
          locale={locale}
          onSelect={onSingleSelect}
        />
      ) : (
        <MultiGroup
          selected={selections}
          availableOptions={availableOptions}
          currency={currency}
          locale={locale}
          onToggle={onMultiToggle}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// SingleGroup — ChoiceCards (radio)
// ---------------------------------------------------------------------------

function SingleGroup({
  group,
  selected,
  availableOptions,
  currency,
  locale,
  onSelect,
}: {
  group: ModifierGroupResponse
  selected: string
  availableOptions: ModifierOptionResponse[]
  currency: string
  locale: string
  onSelect: (optionId: string) => void
}) {
  const choices = availableOptions.map((opt) => ({
    value: opt.id,
    title: opt.name,
    aside:
      opt.priceDeltaMinor !== 0 ? (
        <span
          className={cn(
            'tnum font-mono text-xs',
            opt.priceDeltaMinor > 0 ? 'text-ink-2' : 'text-brand-700',
          )}
        >
          {opt.priceDeltaMinor > 0 ? '+' : ''}
          {formatMoney(opt.priceDeltaMinor, currency, locale)}
        </span>
      ) : null,
  }))

  return (
    <ChoiceCards
      name={`modifier-${group.id}`}
      options={choices}
      value={selected}
      onChange={onSelect}
      columns={2}
    />
  )
}

// ---------------------------------------------------------------------------
// MultiGroup — checkable cards
// ---------------------------------------------------------------------------

function MultiGroup({
  selected,
  availableOptions,
  currency,
  locale,
  onToggle,
}: {
  selected: Set<string>
  availableOptions: ModifierOptionResponse[]
  currency: string
  locale: string
  onToggle: (optionId: string) => void
}) {
  return (
    <div className="grid gap-2.5 sm:grid-cols-2">
      {availableOptions.map((opt) => {
        const checked = selected.has(opt.id)
        return (
          <label
            key={opt.id}
            className={cn(
              'relative flex cursor-pointer items-start gap-3 rounded-2xl border p-4 transition-all',
              checked
                ? 'border-brand-500 bg-brand-50 ring-4 ring-brand-500/12'
                : 'border-line bg-surface hover:border-ink-3',
            )}
          >
            <input
              type="checkbox"
              checked={checked}
              onChange={() => onToggle(opt.id)}
              className="sr-only"
              aria-label={opt.name}
            />
            {/* Custom checkbox indicator */}
            <span
              className={cn(
                'mt-0.5 grid size-4 shrink-0 place-items-center rounded border',
                checked ? 'border-brand-500 bg-brand-500' : 'border-line-strong',
              )}
              aria-hidden="true"
            >
              {checked ? <Check className="size-3 text-white" /> : null}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center justify-between gap-2">
                <span className="font-medium text-ink">{opt.name}</span>
                {opt.priceDeltaMinor !== 0 ? (
                  <span
                    className={cn(
                      'tnum font-mono text-xs',
                      opt.priceDeltaMinor > 0 ? 'text-ink-2' : 'text-emerald-2',
                    )}
                  >
                    {opt.priceDeltaMinor > 0 ? '+' : ''}
                    {formatMoney(opt.priceDeltaMinor, currency, locale)}
                  </span>
                ) : null}
              </span>
            </span>
          </label>
        )
      })}
    </div>
  )
}
