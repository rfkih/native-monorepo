/**
 * MenuManagement — admin screen for cashier/owner/manager to manage menu items,
 * modifier groups/options, and item availability.
 *
 * Standalone full-screen page at /menu, outside the Shell, mirroring the POS chrome.
 *
 * Rules enforced:
 *   - No hardcoded user-facing strings (rule 9): every label is a react-i18next key.
 *   - Money is integer minor units on the wire (rule 8): formatMoney + isoMinorExponent used.
 *   - Locale-aware via Intl through formatMoney.
 *   - TanStack Query for all server state; invalidates menu query after mutations so POS stays
 *     in sync.
 *   - prefers-reduced-motion respected via CSS (Tailwind animate-* classes honour the media query).
 */

import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  BookOpen,
  ChefHat,
  ChevronDown,
  ChevronRight,
  ImageIcon,
  Infinity as InfinityIcon,
  Moon,
  Pencil,
  Plus,
  Sun,
  Trash2,
  TriangleAlert,
  X,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { useSession, type CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, formatPercent, isoMinorExponent } from '@/lib/money'
import { OutletPicker } from '@/components/OutletPicker'
import { useMenu, useSetStock, useAddStock } from '@/features/pos/api'
import {
  CATEGORY_TEMPLATE_KEYS,
  canonicalCategoryKey,
  displayCategoryName,
} from '@/features/pos/lib/categoryCanon'
import { OutletGate } from '@/components/OutletGate'
import type { MenuItem, ModifierGroupResponse, ModifierOptionResponse } from '@/features/pos/api'
import {
  useAdminModifierGroups,
  useCategories,
  useCreateCategory,
  useDeactivateCategory,
  useCreateMenuItem,
  useUpdateMenuItem,
  useDeleteItem,
  use86Item,
  useUn86Item,
  useCreateModifierGroup,
  useUpdateModifierGroup,
  useDeleteModifierGroup,
  useCreateModifierOption,
  useUpdateModifierOption,
  useDeleteModifierOption,
  use86Option,
  useUn86Option,
} from './api'
import { resizeImageFile } from './image'
import { RecipeDrawer } from './RecipeDrawer'
import {
  computeMarginRatio,
  useAutoLinkAll,
  useHppSummary,
  type AutoLinkResult,
  type HppSummaryRow,
} from './recipeApi'

// ---------------------------------------------------------------------------
// Entry guard
// ---------------------------------------------------------------------------

export function MenuManagement() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  // The gate resolves a REAL outlet id so menu edits target the correct outlet (ADR 0012 —
  // no business-unit fallback). key forces a full remount when the effective outlet
  // changes, resetting any local dialog/edit state so it cannot bleed across outlets.
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(session) => <MenuManagementInner key={session.businessId} session={session} />}
    </OutletGate>
  )
}

// ---------------------------------------------------------------------------
// NoCompany fallback (mirrors Pos.tsx pattern)
// ---------------------------------------------------------------------------

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <Card className="w-full max-w-md p-10 text-center">
        <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('menu.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-5 inline-block rounded-xl bg-emerald px-4 py-2.5 text-sm font-bold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Dialog overlay (mirrors Team.tsx pattern)
// ---------------------------------------------------------------------------

function DialogOverlay({
  children,
  onClose,
}: {
  children: React.ReactNode
  onClose: () => void
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-md p-6 max-sm:sheet-up max-sm:max-h-[92dvh] max-sm:overflow-y-auto max-sm:rounded-b-none max-sm:rounded-t-[26px]">{children}</Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// ImagePicker — reusable file-input component with preview + remove
// ---------------------------------------------------------------------------

function ImagePicker({
  value,
  onChange,
  itemName,
}: {
  /** Current data URL (or null when no image is set). */
  value: string | null
  onChange: (url: string | null) => void
  /** Used as context in error messages and alt text. */
  itemName?: string
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [processing, setProcessing] = useState(false)

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!e.target.files) return
    // Reset the input so the same file can be re-selected after a remove.
    e.target.value = ''
    if (!file) return

    setError(null)
    setProcessing(true)
    try {
      const url = await resizeImageFile(file)
      onChange(url)
    } catch (err) {
      const key = err instanceof Error ? err.message : 'menu.errorGeneric'
      setError(t(key))
    } finally {
      setProcessing(false)
    }
  }

  return (
    <div className="space-y-2">
      <span className="block text-sm font-medium text-ink">{t('menu.image.pickLabel')}</span>

      {value ? (
        <div className="flex items-start gap-3">
          <img
            src={value}
            alt={itemName ? t('menu.image.previewAlt', { name: itemName }) : t('menu.image.placeholderAlt')}
            className="size-20 shrink-0 rounded-xl object-cover border border-line"
          />
          <div className="flex flex-col gap-1.5 pt-1">
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              disabled={processing}
              className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {processing ? <Spinner /> : <ImageIcon className="size-3.5" aria-hidden="true" />}
              {t('menu.image.changeButton')}
            </button>
            <button
              type="button"
              onClick={() => { onChange(null); setError(null) }}
              className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-loss transition-colors hover:bg-tint-loss/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-loss"
            >
              <X className="size-3.5" aria-hidden="true" />
              {t('menu.image.removeButton')}
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={processing}
          className={cn(
            'flex w-full items-center justify-center gap-2 rounded-xl border-2 border-dashed border-line',
            'px-4 py-6 text-sm text-ink-3 transition-colors',
            'hover:border-brand-400 hover:bg-brand-50/40 hover:text-brand-700',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
            'disabled:cursor-not-allowed disabled:opacity-50',
          )}
        >
          {processing ? (
            <Spinner />
          ) : (
            <ImageIcon className="size-5 shrink-0" aria-hidden="true" />
          )}
          {processing ? t('common.loading') : t('menu.image.pickButton')}
        </button>
      )}

      {error ? (
        <p className="text-xs text-loss" role="alert">{error}</p>
      ) : null}

      {/* Hidden file input */}
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={handleFileChange}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Create item dialog
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Menu categories — a picklist (managed categories ∪ starter templates) that
// replaces the old free-text field, plus a dedicated manager to build the list.
// ---------------------------------------------------------------------------

// Starter templates + language-canonical identity live in pos/lib/categoryCanon (owner
// report: 'Main Course' vs 'Menu Utama' split — canonical grouping fixes it read-side).

const SELECT_CLASS =
  'h-[52px] w-full rounded-xl border border-line bg-surface px-4 text-[15px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'


/** A category dropdown: the business's managed categories first, then starter templates. */
function CategorySelect({
  session,
  value,
  onChange,
  id,
}: {
  session: CompanySession
  value: string
  onChange: (value: string) => void
  id: string
}) {
  const { t } = useTranslation()
  const categories = useCategories(session)
  const managed = (categories.data ?? []).filter((c) => c.active).map((c) => c.name)
  const templates = CATEGORY_TEMPLATE_KEYS.map((k) => t(`menu.categoryTemplates.${k}`))
  // Keep any current value that is neither managed nor a template (e.g. an item created before
  // this change) so editing never silently drops it. Dedupe LANGUAGE-CANONICALLY so the en and
  // id names of one template never appear as two options; labels follow the current language.
  const seen = new Set<string>()
  const options: { value: string; label: string }[] = []
  for (const n of [...(value ? [value] : []), ...managed, ...templates]) {
    const trimmed = n.trim()
    if (!trimmed) continue
    const canon = canonicalCategoryKey(trimmed)
    if (seen.has(canon)) continue
    seen.add(canon)
    options.push({ value: trimmed, label: displayCategoryName(trimmed, t) })
  }
  return (
    <select
      id={id}
      className={SELECT_CLASS}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      required
    >
      <option value="" disabled>
        {t('menu.createItem.categoryPlaceholder')}
      </option>
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  )
}

/** The "separate menu" for building the category list: add from templates or custom, and hide. */
function CategoryManagerDialog({
  session,
  onClose,
}: {
  session: CompanySession
  onClose: () => void
}) {
  const { t } = useTranslation()
  const categories = useCategories(session)
  const create = useCreateCategory(session)
  const deactivate = useDeactivateCategory(session)
  const [custom, setCustom] = useState('')

  const managed = (categories.data ?? []).filter((c) => c.active)
  // Canonical (categoryCanon): a managed "Menu Utama" suppresses the "Main Course" suggestion
  // too — they are the same template in different languages.
  const managedCanon = new Set(managed.map((c) => canonicalCategoryKey(c.name)))
  const suggestions = CATEGORY_TEMPLATE_KEYS.map((k) => t(`menu.categoryTemplates.${k}`)).filter(
    (n) => !managedCanon.has(canonicalCategoryKey(n)),
  )

  function add(name: string) {
    const trimmed = name.trim()
    if (!trimmed || managedCanon.has(canonicalCategoryKey(trimmed)) || create.isPending) return
    create.mutate({ name: trimmed, displayOrder: managed.length })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('menu.categories.title')}</h2>
        <p className="text-sm text-ink-3">{t('menu.categories.subtitle')}</p>

        {managed.length === 0 ? (
          <p className="text-sm text-ink-3">{t('menu.categories.empty')}</p>
        ) : (
          <ul className="space-y-1.5">
            {managed.map((c) => (
              <li
                key={c.id}
                className="flex items-center justify-between rounded-xl bg-surface px-3 py-2 text-sm"
              >
                <span className="text-ink">{c.name}</span>
                <button
                  type="button"
                  onClick={() => deactivate.mutate(c.id)}
                  className="text-xs text-ink-3 hover:text-loss"
                >
                  {t('menu.categories.hide')}
                </button>
              </li>
            ))}
          </ul>
        )}

        {suggestions.length > 0 ? (
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-ink-3">{t('menu.categories.templatesHint')}</p>
            <div className="flex flex-wrap gap-2">
              {suggestions.map((name) => (
                <button
                  key={name}
                  type="button"
                  onClick={() => add(name)}
                  className="rounded-full border border-line px-3 py-1 text-xs text-ink-2 hover:border-emerald hover:text-emerald"
                >
                  + {name}
                </button>
              ))}
            </div>
          </div>
        ) : null}

        <div className="flex gap-2">
          <input
            className={cn(SELECT_CLASS, 'flex-1')}
            value={custom}
            onChange={(e) => setCustom(e.target.value)}
            placeholder={t('menu.categories.customPlaceholder')}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                add(custom)
                setCustom('')
              }
            }}
          />
          <Button
            type="button"
            onClick={() => {
              add(custom)
              setCustom('')
            }}
            disabled={custom.trim().length === 0}
          >
            {t('menu.categories.add')}
          </Button>
        </div>

        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.close')}
          </Button>
        </div>
      </div>
    </DialogOverlay>
  )
}

/**
 * Optional unit-cost parsing shared by the create/edit item dialogs (ADR 0038 phase 3 — inventory
 * stocktake). Blank clears the cost (null — the item is counted at stocktake but posts no valued
 * shrinkage journal); a supplied value must be >= 0. Mirrors the price field's major→minor
 * conversion (rule 8) but is never required.
 */
function parseUnitCostInput(raw: string, exp: number): { minor: number | null; invalid: boolean } {
  if (raw.trim() === '') return { minor: null, invalid: false }
  const val = Number(raw)
  if (isNaN(val) || val < 0) return { minor: null, invalid: true }
  return { minor: Math.round(val * 10 ** exp), invalid: false }
}

function CreateItemDialog({
  session,
  onClose,
}: {
  session: CompanySession
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useCreateMenuItem(session)
  const [name, setName] = useState('')
  const [category, setCategory] = useState('')
  const [priceInput, setPriceInput] = useState('')
  const [priceError, setPriceError] = useState<string | null>(null)
  const [unitCostInput, setUnitCostInput] = useState('')
  const [unitCostError, setUnitCostError] = useState<string | null>(null)
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  // "Lacak stok" (default ON): the new item is 1:1-auto-linked to a same-named ingredient so its
  // sales deplete stock from day one and the opname prefill moves.
  const [autoTrack, setAutoTrack] = useState(true)

  const exp = isoMinorExponent(session.baseCurrency)

  function handlePriceChange(raw: string) {
    setPriceInput(raw)
    const val = Number(raw)
    if (raw !== '' && (isNaN(val) || val <= 0)) {
      setPriceError(t('menu.createItem.priceInvalid'))
    } else {
      setPriceError(null)
    }
  }

  function handleUnitCostChange(raw: string) {
    setUnitCostInput(raw)
    setUnitCostError(parseUnitCostInput(raw, exp).invalid ? t('menu.createItem.unitCostInvalid') : null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(priceInput)
    if (isNaN(val) || val <= 0) return
    const priceMinor = Math.round(val * 10 ** exp)
    const unitCost = parseUnitCostInput(unitCostInput, exp)
    if (unitCost.invalid) return
    mutation.mutate(
      {
        name: name.trim(),
        category: category.trim(),
        priceMinor,
        currency: session.baseCurrency,
        imageUrl: imageUrl ?? null,
        unitCostMinor: unitCost.minor,
        autoTrackStock: autoTrack,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && category.trim().length > 0 &&
    priceInput.length > 0 && !priceError && !unitCostError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.createItem.title')}
        </h2>

        <Field label={t('menu.createItem.nameLabel')} htmlFor="menu-item-name">
          <TextInput
            id="menu-item-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createItem.namePlaceholder')}
            required
          />
        </Field>

        <Field label={t('menu.createItem.categoryLabel')} htmlFor="menu-item-category">
          <CategorySelect
            session={session}
            id="menu-item-category"
            value={category}
            onChange={setCategory}
          />
        </Field>

        <Field
          label={t('menu.createItem.priceLabel', { currency: session.baseCurrency })}
          htmlFor="menu-item-price"
          error={priceError ?? undefined}
        >
          <TextInput
            id="menu-item-price"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={priceInput}
            onChange={(e) => handlePriceChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        <Field
          label={t('menu.createItem.unitCostLabel', { currency: session.baseCurrency })}
          htmlFor="menu-item-unit-cost"
          hint={t('menu.createItem.unitCostHint')}
          error={unitCostError ?? undefined}
        >
          <TextInput
            id="menu-item-unit-cost"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={unitCostInput}
            onChange={(e) => handleUnitCostChange(e.target.value)}
            placeholder="0"
            className="tnum font-mono"
          />
        </Field>

        <ImagePicker value={imageUrl} onChange={setImageUrl} itemName={name.trim() || undefined} />

        {/* "Lacak stok" — default ON: sales of the new item deplete a same-named 1:1 ingredient. */}
        <label className="flex items-start gap-2.5 rounded-xl border border-line bg-paper px-3 py-2.5">
          <input
            type="checkbox"
            checked={autoTrack}
            onChange={(e) => setAutoTrack(e.target.checked)}
            className="mt-0.5 size-4 shrink-0 accent-emerald"
          />
          <span className="min-w-0">
            <span className="block text-sm font-medium text-ink">
              {t('menu.createItem.autoTrackLabel')}
            </span>
            <span className="block text-xs leading-relaxed text-ink-3">
              {t('menu.createItem.autoTrackHint')}
            </span>
          </span>
        </label>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.createItem.submitting') : t('menu.createItem.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// "Lacak stok semua menu" — bulk 1:1 auto-link (POST /api/v1/menu/recipes/auto-link)
// ---------------------------------------------------------------------------

function AutoLinkDialog({
  session,
  locale,
  onClose,
}: {
  session: CompanySession
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const autoLink = useAutoLinkAll(session)
  // Held after success so the result stays visible (X ditautkan / Y dilewati).
  const [result, setResult] = useState<AutoLinkResult | null>(null)
  const nf = new Intl.NumberFormat(locale)

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('menu.autoLink.title')}</h2>
        {result ? (
          <>
            <p className="rounded-xl border border-emerald-line bg-emerald-tint/40 px-3 py-2.5 text-sm text-ink">
              {t('menu.autoLink.result', {
                linked: nf.format(result.linkedCount),
                skipped: nf.format(result.skippedCount),
              })}
            </p>
            {result.blockedCount > 0 ? (
              <p className="rounded-xl border border-amber-line bg-tint-amber px-3 py-2.5 text-sm text-amber-2">
                {t('menu.autoLink.blocked', { blocked: nf.format(result.blockedCount) })}
              </p>
            ) : null}
            <p className="text-xs leading-relaxed text-ink-3">{t('menu.autoLink.afterHint')}</p>
            <div className="flex justify-end">
              <Button onClick={onClose}>{t('common.close')}</Button>
            </div>
          </>
        ) : (
          <>
            <p className="text-sm leading-relaxed text-ink-2">{t('menu.autoLink.explain')}</p>
            <p className="text-xs leading-relaxed text-ink-3">{t('menu.autoLink.skipNote')}</p>
            {autoLink.isError ? (
              <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
                {t('menu.autoLink.error')}
              </p>
            ) : null}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              <Button
                disabled={autoLink.isPending}
                onClick={() =>
                  autoLink.mutate(undefined, { onSuccess: (res) => setResult(res) })
                }
              >
                {autoLink.isPending ? t('menu.autoLink.running') : t('menu.autoLink.confirm')}
              </Button>
            </div>
          </>
        )}
      </div>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Edit item dialog — PATCH /api/v1/menu/{itemId}
// ---------------------------------------------------------------------------

function EditItemDialog({
  session,
  item,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useUpdateMenuItem(session)
  const exp = isoMinorExponent(session.baseCurrency)

  const [name, setName] = useState(item.name)
  const [category, setCategory] = useState(item.category)
  const [priceInput, setPriceInput] = useState(String(item.priceMinor / 10 ** exp))
  const [priceError, setPriceError] = useState<string | null>(null)
  const [unitCostInput, setUnitCostInput] = useState(
    item.unitCostMinor != null ? String(item.unitCostMinor / 10 ** exp) : '',
  )
  const [unitCostError, setUnitCostError] = useState<string | null>(null)
  // Initialise with existing imageUrl, or null if none.
  const [imageUrl, setImageUrl] = useState<string | null>(item.imageUrl ?? null)

  function handlePriceChange(raw: string) {
    setPriceInput(raw)
    const val = Number(raw)
    if (raw !== '' && (isNaN(val) || val <= 0)) {
      setPriceError(t('menu.createItem.priceInvalid'))
    } else {
      setPriceError(null)
    }
  }

  function handleUnitCostChange(raw: string) {
    setUnitCostInput(raw)
    setUnitCostError(parseUnitCostInput(raw, exp).invalid ? t('menu.createItem.unitCostInvalid') : null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(priceInput)
    if (isNaN(val) || val <= 0) return
    const priceMinor = Math.round(val * 10 ** exp)
    const unitCost = parseUnitCostInput(unitCostInput, exp)
    if (unitCost.invalid) return
    mutation.mutate(
      {
        itemId: item.id,
        name: name.trim(),
        category: category.trim(),
        priceMinor,
        // imageUrl === null means "clear"; a string means "set/replace".
        imageUrl: imageUrl ?? '',
        unitCostMinor: unitCost.minor,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit =
    name.trim().length > 0 &&
    category.trim().length > 0 &&
    priceInput.length > 0 &&
    !priceError &&
    !unitCostError &&
    !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.editItem.title')}
        </h2>

        <Field label={t('menu.createItem.nameLabel')} htmlFor="edit-item-name">
          <TextInput
            id="edit-item-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createItem.namePlaceholder')}
            required
          />
        </Field>

        <Field label={t('menu.createItem.categoryLabel')} htmlFor="edit-item-category">
          <CategorySelect
            session={session}
            id="edit-item-category"
            value={category}
            onChange={setCategory}
          />
        </Field>

        <Field
          label={t('menu.createItem.priceLabel', { currency: session.baseCurrency })}
          htmlFor="edit-item-price"
          error={priceError ?? undefined}
        >
          <TextInput
            id="edit-item-price"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={priceInput}
            onChange={(e) => handlePriceChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        <Field
          label={t('menu.createItem.unitCostLabel', { currency: session.baseCurrency })}
          htmlFor="edit-item-unit-cost"
          hint={t('menu.createItem.unitCostHint')}
          error={unitCostError ?? undefined}
        >
          <TextInput
            id="edit-item-unit-cost"
            type="number"
            inputMode="decimal"
            min="0"
            step={exp === 0 ? '1' : '0.01'}
            value={unitCostInput}
            onChange={(e) => handleUnitCostChange(e.target.value)}
            placeholder="0"
            className="tnum font-mono"
          />
        </Field>

        <ImagePicker value={imageUrl} onChange={setImageUrl} itemName={name.trim() || item.name} />

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.editItem.submitting') : t('menu.editItem.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Create modifier group dialog
// type preset maps to selectionType + required + minSelect + maxSelect
// ---------------------------------------------------------------------------

type SelectionType = 'SINGLE' | 'MULTI'

/** Selection type + required flag → the {minSelect, maxSelect} the API expects. */
function selectRange(
  selectionType: SelectionType,
  required: boolean,
): { minSelect: number; maxSelect: number } {
  return { minSelect: required ? 1 : 0, maxSelect: selectionType === 'SINGLE' ? 1 : 99 }
}

/**
 * The two INDEPENDENTLY adjustable rules for a modifier group, shared by the create + edit dialogs:
 *  - selection: pick one (SINGLE) vs pick many (MULTI)
 *  - requirement: required (the customer must choose) vs optional (they can skip it)
 * Any combination is valid (e.g. "pick many + required", "pick one + optional").
 */
function GroupRuleFields({
  selectionType,
  required,
  onSelectionType,
  onRequired,
}: {
  selectionType: SelectionType
  required: boolean
  onSelectionType: (v: SelectionType) => void
  onRequired: (v: boolean) => void
}) {
  const { t } = useTranslation()
  return (
    <>
      <Field label={t('menu.createGroup.selectionLabel')}>
        <div className="overflow-x-auto">
          <Segmented
            options={[
              { value: 'SINGLE' as const, label: t('menu.createGroup.presetPickOne') },
              { value: 'MULTI' as const, label: t('menu.createGroup.presetPickMany') },
            ]}
            value={selectionType}
            onChange={onSelectionType}
            ariaLabel={t('menu.createGroup.selectionLabel')}
          />
        </div>
      </Field>
      <Field label={t('menu.createGroup.requirementLabel')}>
        <div className="overflow-x-auto">
          <Segmented
            options={[
              { value: 'required' as const, label: t('menu.createGroup.required') },
              { value: 'optional' as const, label: t('menu.createGroup.optional') },
            ]}
            value={required ? 'required' : 'optional'}
            onChange={(v) => onRequired(v === 'required')}
            ariaLabel={t('menu.createGroup.requirementLabel')}
          />
        </div>
      </Field>
      <p className="text-xs text-ink-3">
        {t(required ? 'menu.createGroup.requiredHint' : 'menu.createGroup.optionalHint')}
      </p>
    </>
  )
}

function CreateGroupDialog({
  session,
  itemId,
  nextDisplayOrder,
  onClose,
}: {
  session: CompanySession
  itemId: string
  nextDisplayOrder: number
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useCreateModifierGroup(session)
  const [name, setName] = useState('')
  const [selectionType, setSelectionType] = useState<SelectionType>('SINGLE')
  const [required, setRequired] = useState(true)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const range = selectRange(selectionType, required)
    mutation.mutate(
      {
        itemId,
        name: name.trim(),
        selectionType,
        required,
        minSelect: range.minSelect,
        maxSelect: range.maxSelect,
        displayOrder: nextDisplayOrder,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.createGroup.title')}
        </h2>

        <Field label={t('menu.createGroup.nameLabel')} htmlFor="group-name">
          <TextInput
            id="group-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createGroup.namePlaceholder')}
            required
          />
        </Field>

        <GroupRuleFields
          selectionType={selectionType}
          required={required}
          onSelectionType={setSelectionType}
          onRequired={setRequired}
        />

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.createGroup.submitting') : t('menu.createGroup.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Edit modifier group dialog
// ---------------------------------------------------------------------------

function EditGroupDialog({
  session,
  itemId,
  group,
  onClose,
}: {
  session: CompanySession
  itemId: string
  group: ModifierGroupResponse
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useUpdateModifierGroup(session)
  const [name, setName] = useState(group.name)
  const [selectionType, setSelectionType] = useState<SelectionType>(group.selectionType)
  const [required, setRequired] = useState(group.required)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const range = selectRange(selectionType, required)
    mutation.mutate(
      {
        itemId,
        groupId: group.id,
        name: name.trim(),
        selectionType,
        required,
        minSelect: range.minSelect,
        maxSelect: range.maxSelect,
        displayOrder: group.displayOrder,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.editGroup.title')}
        </h2>

        <Field label={t('menu.createGroup.nameLabel')} htmlFor="edit-group-name">
          <TextInput
            id="edit-group-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createGroup.namePlaceholder')}
            required
          />
        </Field>

        <GroupRuleFields
          selectionType={selectionType}
          required={required}
          onSelectionType={setSelectionType}
          onRequired={setRequired}
        />

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.editGroup.submitting') : t('menu.editGroup.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Delete modifier group confirmation dialog
// ---------------------------------------------------------------------------

function DeleteGroupDialog({
  session,
  itemId,
  group,
  onClose,
}: {
  session: CompanySession
  itemId: string
  group: ModifierGroupResponse
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useDeleteModifierGroup(session)

  function handleConfirm() {
    mutation.mutate({ itemId, groupId: group.id }, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.deleteGroup.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('menu.deleteGroup.body', { name: group.name })}
        </p>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose} disabled={mutation.isPending}>
            {t('common.cancel')}
          </Button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            className={cn(
              'inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold',
              'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
              'focus-visible:outline-loss disabled:cursor-not-allowed disabled:opacity-50',
              'bg-loss text-white hover:bg-loss/90',
            )}
          >
            {mutation.isPending ? t('menu.deleteGroup.submitting') : t('menu.deleteGroup.confirm')}
          </button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Create modifier option dialog
// ---------------------------------------------------------------------------

function CreateOptionDialog({
  session,
  itemId,
  groupId,
  nextDisplayOrder,
  onClose,
}: {
  session: CompanySession
  itemId: string
  groupId: string
  nextDisplayOrder: number
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useCreateModifierOption(session)
  const [name, setName] = useState('')
  const [deltaInput, setDeltaInput] = useState('0')
  const [deltaError, setDeltaError] = useState<string | null>(null)

  const exp = isoMinorExponent(session.baseCurrency)

  function handleDeltaChange(raw: string) {
    setDeltaInput(raw)
    const val = Number(raw)
    if (raw !== '' && isNaN(val)) {
      setDeltaError(t('menu.createOption.deltaInvalid'))
    } else {
      setDeltaError(null)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(deltaInput)
    if (isNaN(val)) return
    const priceDeltaMinor = Math.round(val * 10 ** exp)
    mutation.mutate(
      {
        itemId,
        groupId,
        name: name.trim(),
        priceDeltaMinor,
        displayOrder: nextDisplayOrder,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && !deltaError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.createOption.title')}
        </h2>

        <Field label={t('menu.createOption.nameLabel')} htmlFor="option-name">
          <TextInput
            id="option-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createOption.namePlaceholder')}
            required
          />
        </Field>

        <Field
          label={t('menu.createOption.deltaLabel', { currency: session.baseCurrency })}
          htmlFor="option-delta"
          hint={t('menu.createOption.deltaHint')}
          error={deltaError ?? undefined}
        >
          <TextInput
            id="option-delta"
            type="number"
            inputMode="decimal"
            step={exp === 0 ? '1' : '0.01'}
            value={deltaInput}
            onChange={(e) => handleDeltaChange(e.target.value)}
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending
              ? t('menu.createOption.submitting')
              : t('menu.createOption.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Edit modifier option dialog
// ---------------------------------------------------------------------------

function EditOptionDialog({
  session,
  itemId,
  groupId,
  option,
  onClose,
}: {
  session: CompanySession
  itemId: string
  groupId: string
  option: ModifierOptionResponse
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useUpdateModifierOption(session)
  const exp = isoMinorExponent(session.baseCurrency)

  const [name, setName] = useState(option.name)
  const [deltaInput, setDeltaInput] = useState(
    String(option.priceDeltaMinor / 10 ** exp),
  )
  const [deltaError, setDeltaError] = useState<string | null>(null)

  function handleDeltaChange(raw: string) {
    setDeltaInput(raw)
    const val = Number(raw)
    if (raw !== '' && isNaN(val)) {
      setDeltaError(t('menu.createOption.deltaInvalid'))
    } else {
      setDeltaError(null)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(deltaInput)
    if (isNaN(val)) return
    const priceDeltaMinor = Math.round(val * 10 ** exp)
    mutation.mutate(
      {
        itemId,
        groupId,
        optionId: option.id,
        name: name.trim(),
        priceDeltaMinor,
        displayOrder: option.displayOrder,
      },
      { onSuccess: () => onClose() },
    )
  }

  const canSubmit = name.trim().length > 0 && !deltaError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.editOption.title')}
        </h2>

        <Field label={t('menu.createOption.nameLabel')} htmlFor="edit-option-name">
          <TextInput
            id="edit-option-name"
            type="text"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('menu.createOption.namePlaceholder')}
            required
          />
        </Field>

        <Field
          label={t('menu.createOption.deltaLabel', { currency: session.baseCurrency })}
          htmlFor="edit-option-delta"
          hint={t('menu.createOption.deltaHint')}
          error={deltaError ?? undefined}
        >
          <TextInput
            id="edit-option-delta"
            type="number"
            inputMode="decimal"
            step={exp === 0 ? '1' : '0.01'}
            value={deltaInput}
            onChange={(e) => handleDeltaChange(e.target.value)}
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.editOption.submitting') : t('menu.editOption.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Delete modifier option confirmation dialog
// ---------------------------------------------------------------------------

function DeleteOptionDialog({
  session,
  itemId,
  groupId,
  option,
  onClose,
}: {
  session: CompanySession
  itemId: string
  groupId: string
  option: ModifierOptionResponse
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useDeleteModifierOption(session)

  function handleConfirm() {
    mutation.mutate(
      { itemId, groupId, optionId: option.id },
      { onSuccess: () => onClose() },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.deleteOption.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('menu.deleteOption.body', { name: option.name })}
        </p>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose} disabled={mutation.isPending}>
            {t('common.cancel')}
          </Button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            className={cn(
              'inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold',
              'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
              'focus-visible:outline-loss disabled:cursor-not-allowed disabled:opacity-50',
              'bg-loss text-white hover:bg-loss/90',
            )}
          >
            {mutation.isPending
              ? t('menu.deleteOption.submitting')
              : t('menu.deleteOption.confirm')}
          </button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// SetStockDialog — PUT /api/v1/menu/{itemId}/stock {quantity}
// Used both to "start tracking" (track action on infinite items) and to
// set an explicit new quantity on tracked items.
// ---------------------------------------------------------------------------

function SetStockDialog({
  session,
  item,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useSetStock(session)
  const [input, setInput] = useState(
    item.stockQuantity != null ? String(item.stockQuantity) : '0',
  )
  const [inputError, setInputError] = useState<string | null>(null)

  function handleChange(raw: string) {
    setInput(raw)
    const val = Number(raw)
    if (raw !== '' && (isNaN(val) || val < 0 || !Number.isInteger(val))) {
      setInputError(t('menu.stock.quantityInvalid'))
    } else {
      setInputError(null)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(input)
    if (isNaN(val) || val < 0 || !Number.isInteger(val)) return
    mutation.mutate({ itemId: item.id, quantity: val }, { onSuccess: () => onClose() })
  }

  const canSubmit = input.length > 0 && !inputError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.stock.setDialogTitle')}
        </h2>
        <p className="text-sm text-ink-3">{t('menu.stock.setDialogHint')}</p>

        <Field label={t('menu.stock.quantityLabel')} htmlFor="stock-set-qty" error={inputError ?? undefined}>
          <TextInput
            id="stock-set-qty"
            type="number"
            inputMode="numeric"
            min="0"
            step="1"
            autoFocus
            value={input}
            onChange={(e) => handleChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.stock.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.stock.submitting') : t('menu.stock.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// AddStockDialog — POST /api/v1/menu/{itemId}/stock/add {amount}
// ---------------------------------------------------------------------------

function AddStockDialog({
  session,
  item,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useAddStock(session)
  const [input, setInput] = useState('')
  const [inputError, setInputError] = useState<string | null>(null)

  function handleChange(raw: string) {
    setInput(raw)
    const val = Number(raw)
    if (raw !== '' && (isNaN(val) || !Number.isInteger(val) || val === 0)) {
      setInputError(t('menu.stock.amountInvalid'))
    } else {
      setInputError(null)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const val = Number(input)
    if (isNaN(val) || !Number.isInteger(val) || val === 0) return
    mutation.mutate({ itemId: item.id, amount: val }, { onSuccess: () => onClose() })
  }

  const canSubmit = input.length > 0 && !inputError && !mutation.isPending

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.stock.addDialogTitle')}
        </h2>
        <p className="text-sm text-ink-3">{t('menu.stock.addDialogHint')}</p>

        <Field label={t('menu.stock.amountLabel')} htmlFor="stock-add-amt" error={inputError ?? undefined}>
          <TextInput
            id="stock-add-amt"
            type="number"
            inputMode="numeric"
            step="1"
            autoFocus
            value={input}
            onChange={(e) => handleChange(e.target.value)}
            placeholder="0"
            required
            className="tnum font-mono"
          />
        </Field>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.stock.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('menu.stock.submitting') : t('menu.stock.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Delete item confirmation dialog
// ---------------------------------------------------------------------------

function DeleteItemDialog({
  session,
  item,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  onClose: () => void
}) {
  const { t } = useTranslation()
  const mutation = useDeleteItem(session)

  function handleConfirm() {
    mutation.mutate(item.id, { onSuccess: () => onClose() })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('menu.delete.title')}
        </h2>
        <p className="text-sm text-ink-2">
          {t('menu.delete.body', { name: item.name })}
        </p>

        {mutation.isError ? (
          <p className="rounded-xl border border-loss/30 bg-tint-loss px-3 py-2 text-sm text-loss">
            {t('menu.errorGeneric')}
          </p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose} disabled={mutation.isPending}>
            {t('common.cancel')}
          </Button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            className={cn(
              'inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold',
              'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
              'focus-visible:outline-loss disabled:cursor-not-allowed disabled:opacity-50',
              'bg-loss text-white hover:bg-loss/90',
            )}
          >
            {mutation.isPending ? t('menu.delete.submitting') : t('menu.delete.confirm')}
          </button>
        </div>
      </div>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// StockControl — inline stock display + action buttons for an item row
// ---------------------------------------------------------------------------

function StockControl({
  item,
  session,
}: {
  item: MenuItem
  session: CompanySession
}) {
  const { t } = useTranslation()
  const setStock = useSetStock(session)
  const [dialog, setDialog] = useState<'set' | 'add' | null>(null)

  const tracked = item.stockQuantity != null
  const qty = item.stockQuantity ?? null
  const isSoldOut = tracked && qty === 0

  return (
    <>
      <div
        className={cn(
          'flex shrink-0 items-center gap-1.5 rounded-xl border px-2.5 py-1.5',
          tracked
            ? isSoldOut
              ? 'border-loss/30 bg-tint-loss'
              : qty !== null && qty <= 5
                ? 'border-amber/30 bg-amber-tint'
                : 'border-line bg-paper'
            : 'border-line bg-paper',
        )}
      >
        {/* Stock quantity indicator */}
        {tracked ? (
          <span
            className={cn(
              'tnum font-mono text-xs font-semibold',
              isSoldOut ? 'text-loss' : qty !== null && qty <= 5 ? 'text-amber-2' : 'text-ink-2',
            )}
          >
            {isSoldOut ? t('menu.stock.soldOut') : t('menu.stock.tracked', { count: qty })}
          </span>
        ) : (
          <span className="flex items-center gap-1 text-xs text-ink-3">
            <InfinityIcon className="size-3.5" aria-hidden="true" />
            {t('menu.stock.infinite')}
          </span>
        )}

        {/* Action buttons */}
        <div className="ml-1 flex items-center gap-0.5">
          {tracked ? (
            <>
              <button
                type="button"
                onClick={() => setDialog('add')}
                className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-brand-600 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
                aria-label={t('menu.stock.addStock')}
              >
                {t('menu.stock.addStock')}
              </button>
              <button
                type="button"
                onClick={() => setDialog('set')}
                className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
                aria-label={t('menu.stock.setStock')}
              >
                {t('menu.stock.setStock')}
              </button>
              <button
                type="button"
                onClick={() =>
                  setStock.mutate({ itemId: item.id, quantity: null })
                }
                disabled={setStock.isPending}
                className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
                aria-label={t('menu.stock.makeInfinite')}
              >
                {setStock.isPending ? <Spinner /> : t('menu.stock.makeInfinite')}
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={() => setDialog('set')}
              className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-brand-600 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
              aria-label={t('menu.stock.trackAction')}
            >
              {t('menu.stock.trackAction')}
            </button>
          )}
        </div>
      </div>

      {dialog === 'set' ? (
        <SetStockDialog session={session} item={item} onClose={() => setDialog(null)} />
      ) : null}
      {dialog === 'add' ? (
        <AddStockDialog session={session} item={item} onClose={() => setDialog(null)} />
      ) : null}
    </>
  )
}

// ---------------------------------------------------------------------------
// Modifier options panel — lists options within a group, with availability toggle
// ---------------------------------------------------------------------------

function OptionRow({
  option,
  session,
  itemId,
  groupId,
  locale,
}: {
  option: ModifierOptionResponse
  session: CompanySession
  itemId: string
  groupId: string
  locale: string
}) {
  const { t } = useTranslation()
  const mark86 = use86Option(session)
  const unMark86 = useUn86Option(session)
  const isPending = mark86.isPending || unMark86.isPending
  const [dialog, setDialog] = useState<'edit' | 'delete' | null>(null)

  function handleToggle() {
    if (isPending) return
    if (option.available) {
      mark86.mutate({ itemId, groupId, optionId: option.id })
    } else {
      unMark86.mutate({ itemId, groupId, optionId: option.id })
    }
  }

  const deltaLabel =
    option.priceDeltaMinor !== 0
      ? `${option.priceDeltaMinor > 0 ? '+' : ''}${formatMoney(option.priceDeltaMinor, session.baseCurrency, locale)}`
      : null

  return (
    <>
      <div
        className={cn(
          'flex items-center gap-2 rounded-lg px-3 py-2.5 transition-colors hover:bg-hover',
          !option.available && 'opacity-55',
        )}
      >
        <div className="min-w-0 flex-1">
          <span className="text-sm font-medium text-ink">{option.name}</span>
          {deltaLabel ? (
            <span
              className={cn(
                'tnum ml-2 font-mono text-xs',
                option.priceDeltaMinor > 0 ? 'text-ink-2' : 'text-brand-700',
              )}
            >
              {deltaLabel}
            </span>
          ) : null}
        </div>

        {/* Availability toggle */}
        <button
          type="button"
          onClick={handleToggle}
          disabled={isPending}
          aria-label={
            option.available
              ? t('menu.option.markUnavailable', { name: option.name })
              : t('menu.option.markAvailable', { name: option.name })
          }
          className={cn(
            'shrink-0 rounded-lg px-2.5 py-1 text-xs font-semibold transition-colors',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
            'disabled:cursor-not-allowed disabled:opacity-50',
            option.available
              ? 'text-ink-3 hover:bg-paper hover:text-ink'
              : 'text-brand-600 hover:bg-emerald-tint hover:text-brand-700',
          )}
        >
          {isPending ? (
            <Spinner />
          ) : option.available ? (
            t('menu.option.available')
          ) : (
            t('menu.option.soldOut')
          )}
        </button>

        {/* Edit option */}
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); setDialog('edit') }}
          aria-label={t('menu.editOption.action', { name: option.name })}
          className={cn(
            'grid size-6 shrink-0 place-items-center rounded-md transition-colors',
            'text-ink-3 hover:bg-hover hover:text-ink',
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500',
          )}
        >
          <Pencil className="size-3" aria-hidden="true" />
        </button>

        {/* Delete option */}
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); setDialog('delete') }}
          aria-label={t('menu.deleteOption.action', { name: option.name })}
          className={cn(
            'grid size-6 shrink-0 place-items-center rounded-md transition-colors',
            'text-ink-3 hover:bg-tint-loss/60 hover:text-loss',
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-loss',
          )}
        >
          <Trash2 className="size-3" aria-hidden="true" />
        </button>
      </div>

      {dialog === 'edit' ? (
        <EditOptionDialog
          session={session}
          itemId={itemId}
          groupId={groupId}
          option={option}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'delete' ? (
        <DeleteOptionDialog
          session={session}
          itemId={itemId}
          groupId={groupId}
          option={option}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </>
  )
}

// ---------------------------------------------------------------------------
// Modifier groups panel (inline, expandable per item)
// ---------------------------------------------------------------------------

function ModifierGroupsPanel({
  session,
  item,
  locale,
  onClose,
}: {
  session: CompanySession
  item: MenuItem
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const groupsQuery = useAdminModifierGroups(session, item.id)
  const [showCreateGroup, setShowCreateGroup] = useState(false)
  const [addingOptionToGroup, setAddingOptionToGroup] = useState<string | null>(null)

  const groups = groupsQuery.data ?? []

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={t('menu.options.panelLabel', { name: item.name })}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <div className="flex w-full max-w-lg flex-col rounded-t-2xl bg-surface shadow-xl sm:rounded-2xl">
        {/* Panel header */}
        <div className="flex items-start justify-between border-b border-line px-5 py-4">
          <div className="min-w-0 pr-3">
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('menu.options.panelTitle')}
            </h2>
            <p className="mt-0.5 truncate text-sm text-ink-3">{item.name}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Groups list */}
        <div className="max-h-[65vh] min-h-0 overflow-y-auto overscroll-contain px-4 py-3">
          {groupsQuery.isLoading ? (
            <ListSkeleton rows={2} className="rounded-none border-0" />
          ) : groupsQuery.isError ? (
            <p className="py-6 text-center text-sm text-loss">{t('menu.options.loadError')}</p>
          ) : groups.length === 0 ? (
            <p className="py-6 text-center text-sm text-ink-3">{t('menu.options.noGroups')}</p>
          ) : (
            <div className="space-y-4">
              {groups
                .slice()
                .sort((a, b) => a.displayOrder - b.displayOrder)
                .map((group) => (
                  <GroupBlock
                    key={group.id}
                    group={group}
                    session={session}
                    item={item}
                    locale={locale}
                    onAddOption={() => setAddingOptionToGroup(group.id)}
                  />
                ))}
            </div>
          )}
        </div>

        {/* Footer — add group */}
        <div className="border-t border-line px-4 py-3">
          <Button
            variant="outline"
            className="w-full"
            onClick={() => setShowCreateGroup(true)}
          >
            <Plus className="size-4" />
            {t('menu.options.addGroup')}
          </Button>
        </div>
      </div>

      {/* Sub-dialogs */}
      {showCreateGroup ? (
        <CreateGroupDialog
          session={session}
          itemId={item.id}
          nextDisplayOrder={groups.length}
          onClose={() => setShowCreateGroup(false)}
        />
      ) : null}

      {addingOptionToGroup != null ? (
        <CreateOptionDialog
          session={session}
          itemId={item.id}
          groupId={addingOptionToGroup}
          nextDisplayOrder={
            groups.find((g) => g.id === addingOptionToGroup)?.options.length ?? 0
          }
          onClose={() => setAddingOptionToGroup(null)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Group block within the options panel
// ---------------------------------------------------------------------------

function GroupBlock({
  group,
  session,
  item,
  locale,
  onAddOption,
}: {
  group: ModifierGroupResponse
  session: CompanySession
  item: MenuItem
  locale: string
  onAddOption: () => void
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(true)
  const [groupDialog, setGroupDialog] = useState<'edit' | 'delete' | null>(null)

  const selLabel =
    group.selectionType === 'SINGLE'
      ? t('menu.group.typeSingle')
      : t('menu.group.typeMulti')

  const reqTone = group.required ? ('amber' as const) : ('neutral' as const)

  return (
    <>
      <div className="rounded-xl border border-line bg-paper">
        {/* Group header row — expand/collapse + edit/delete controls */}
        <div className="flex items-center gap-1 px-2 py-1">
          {/* Expand/collapse — takes up the main area */}
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            className="flex flex-1 items-center gap-2 rounded-lg px-2 py-2 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
            aria-expanded={expanded}
          >
            {expanded ? (
              <ChevronDown className="size-4 shrink-0 text-ink-3" aria-hidden="true" />
            ) : (
              <ChevronRight className="size-4 shrink-0 text-ink-3" aria-hidden="true" />
            )}
            <span className="flex-1 font-medium text-ink">{group.name}</span>
            <Badge tone={reqTone} className="text-[11px]">
              {group.required ? t('pos.modifiers.required') : t('pos.modifiers.optional')}
            </Badge>
            <span className="text-xs text-ink-3">{selLabel}</span>
          </button>

          {/* Edit group */}
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); setGroupDialog('edit') }}
            aria-label={t('menu.editGroup.action', { name: group.name })}
            className={cn(
              'grid size-7 shrink-0 place-items-center rounded-lg transition-colors',
              'text-ink-3 hover:bg-hover hover:text-ink',
              'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500',
            )}
          >
            <Pencil className="size-3.5" aria-hidden="true" />
          </button>

          {/* Delete group */}
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); setGroupDialog('delete') }}
            aria-label={t('menu.deleteGroup.action', { name: group.name })}
            className={cn(
              'grid size-7 shrink-0 place-items-center rounded-lg transition-colors',
              'text-ink-3 hover:bg-tint-loss/60 hover:text-loss',
              'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-loss',
            )}
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
          </button>
        </div>

        {/* Options list */}
        {expanded ? (
          <div className="border-t border-line px-2 pb-2">
            {group.options.length === 0 ? (
              <p className="px-3 py-3 text-xs text-ink-3 italic">{t('menu.group.noOptions')}</p>
            ) : (
              <div className="mt-1">
                {group.options
                  .slice()
                  .sort((a, b) => a.displayOrder - b.displayOrder)
                  .map((opt) => (
                    <OptionRow
                      key={opt.id}
                      option={opt}
                      session={session}
                      itemId={item.id}
                      groupId={group.id}
                      locale={locale}
                    />
                  ))}
              </div>
            )}
            <div className="mt-1 px-2">
              <button
                type="button"
                onClick={onAddOption}
                className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-brand-600 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
              >
                <Plus className="size-3.5" aria-hidden="true" />
                {t('menu.group.addOption')}
              </button>
            </div>
          </div>
        ) : null}
      </div>

      {groupDialog === 'edit' ? (
        <EditGroupDialog
          session={session}
          itemId={item.id}
          group={group}
          onClose={() => setGroupDialog(null)}
        />
      ) : null}
      {groupDialog === 'delete' ? (
        <DeleteGroupDialog
          session={session}
          itemId={item.id}
          group={group}
          onClose={() => setGroupDialog(null)}
        />
      ) : null}
    </>
  )
}

// ---------------------------------------------------------------------------
// Item row
// ---------------------------------------------------------------------------

function ItemRow({
  item,
  session,
  locale,
  hppRow,
  onManageOptions,
  onManageRecipe,
}: {
  item: MenuItem
  session: CompanySession
  locale: string
  /** This item's row from useHppSummary — undefined until it has a recipe with at least one line. */
  hppRow: HppSummaryRow | undefined
  onManageOptions: () => void
  onManageRecipe: () => void
}) {
  const { t } = useTranslation()
  const mark86 = use86Item(session)
  const unMark86 = useUn86Item(session)
  const isPending = mark86.isPending || unMark86.isPending
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)
  const [showEditDialog, setShowEditDialog] = useState(false)

  function handleToggle() {
    if (isPending) return
    if (item.available) {
      mark86.mutate(item.id)
    } else {
      unMark86.mutate(item.id)
    }
  }

  return (
    <>
      <div
        className={cn(
          'group flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl px-3 py-3 transition-colors hover:bg-hover',
          !item.available && 'opacity-60',
        )}
      >
        {/* Thumbnail — 40×40 square; subtle placeholder icon when no image */}
        {item.imageUrl ? (
          <img
            src={item.imageUrl}
            alt={item.name}
            className="size-10 shrink-0 rounded-lg object-cover border border-line"
          />
        ) : (
          <span
            className="grid size-10 shrink-0 place-items-center rounded-lg border border-dashed border-line bg-paper text-ink-3/40"
            aria-hidden="true"
          >
            <ImageIcon className="size-4" />
          </span>
        )}

        {/* Item info */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-semibold text-ink">{item.name}</span>
            {!item.available ? (
              <Badge tone="neutral" className="text-[11px]">
                {t('pos.soldOut')}
              </Badge>
            ) : null}
          </div>
          <div className="tnum mt-0.5 font-mono text-xs text-brand-700">
            {formatMoney(item.priceMinor, item.currency, locale)}
            {item.unitCostMinor != null ? (
              <span className="ml-2 text-ink-3">
                {t('menu.item.unitCost', { cost: formatMoney(item.unitCostMinor, item.currency, locale) })}
              </span>
            ) : null}
          </div>
          {/* HPP/margin chip (ADR 0050 phase A) — only once the item has a recipe on file
              (useHppSummary omits items with none). Amber whenever the server flags the
              underlying cost data as incomplete; otherwise a profit-toned readout. */}
          {hppRow ? <HppChip item={item} hppRow={hppRow} locale={locale} /> : null}
        </div>

        {/* Actions */}
        {/* Phone: the cluster takes its own full-width row and WRAPS - with shrink-0 alone
            its max-content width clipped at the card edge, hiding edit/availability/delete
            (owner report: cannot edit the menu on the phone). */}
        <div className="flex shrink-0 flex-wrap items-center gap-2 max-sm:w-full max-sm:min-w-0">
          {/* Stock control — visually distinct from the manual available toggle */}
          <StockControl item={item} session={session} />

          {/* Options button */}
          <button
            type="button"
            onClick={onManageOptions}
            aria-label={t('menu.item.manageOptions', { name: item.name })}
            className="inline-flex items-center gap-1 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <BookOpen className="size-3.5" aria-hidden="true" />
            {t('menu.item.options')}
            {item.modifierGroups.length > 0 ? (
              <span className="tnum grid h-[17px] min-w-[17px] place-items-center rounded-full bg-ink-50 px-1 text-[10px] font-bold text-ink-2">
                {item.modifierGroups.length}
              </span>
            ) : null}
          </button>

          {/* Recipe button (ADR 0050 phase A) — opens RecipeDrawer to build/edit the BOM. */}
          <button
            type="button"
            onClick={onManageRecipe}
            aria-label={t('recipe.manageAction', { name: item.name })}
            className="inline-flex items-center gap-1 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <ChefHat className="size-3.5" aria-hidden="true" />
            {t('recipe.action')}
          </button>

          {/* Edit item button */}
          <button
            type="button"
            onClick={() => setShowEditDialog(true)}
            aria-label={t('menu.editItem.action', { name: item.name })}
            className={cn(
              'grid size-[30px] shrink-0 place-items-center rounded-lg transition-colors max-sm:size-11',
              'text-ink-3 hover:bg-hover hover:text-ink',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
            )}
          >
            <Pencil className="size-3.5" aria-hidden="true" />
          </button>

          {/* Availability toggle — manual override, works for infinite items too */}
          <button
            type="button"
            onClick={handleToggle}
            disabled={isPending}
            aria-label={
              item.available
                ? t('menu.item.markSoldOut', { name: item.name })
                : t('menu.item.markAvailable', { name: item.name })
            }
            className={cn(
              'inline-flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold transition-colors',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
              'disabled:cursor-not-allowed disabled:opacity-50',
              item.available
                ? 'text-ink-2 hover:bg-tint-loss/60 hover:text-loss'
                : 'text-brand-600 hover:bg-emerald-tint hover:text-brand-700',
            )}
          >
            {isPending ? (
              <Spinner />
            ) : item.available ? (
              t('menu.item.available')
            ) : (
              t('menu.item.soldOut')
            )}
          </button>

          {/* Delete button — muted, turns loss-red on hover */}
          <button
            type="button"
            onClick={() => setShowDeleteDialog(true)}
            aria-label={t('menu.delete.action', { name: item.name })}
            className={cn(
              'grid size-[30px] shrink-0 place-items-center rounded-lg transition-colors max-sm:size-11',
              'text-ink-3 hover:bg-tint-loss/60 hover:text-loss',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-loss',
            )}
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
          </button>
        </div>
      </div>

      {showEditDialog ? (
        <EditItemDialog
          session={session}
          item={item}
          onClose={() => setShowEditDialog(false)}
        />
      ) : null}

      {showDeleteDialog ? (
        <DeleteItemDialog
          session={session}
          item={item}
          onClose={() => setShowDeleteDialog(false)}
        />
      ) : null}
    </>
  )
}

/** The HPP/margin readout on ItemRow — a thin wrapper so the two `computeMarginRatio` inputs
 *  (price side vs HPP side) are computed once and stay in sync with the badge's tone. */
function HppChip({
  item,
  hppRow,
  locale,
}: {
  item: MenuItem
  hppRow: HppSummaryRow
  locale: string
}) {
  const { t } = useTranslation()
  const margin =
    hppRow.unitHppMinor != null && hppRow.hppCurrency != null
      ? computeMarginRatio(item.priceMinor, item.currency, hppRow.unitHppMinor, hppRow.hppCurrency)
      : null
  return (
    <Badge tone={hppRow.completeness === 'COMPLETE' ? 'profit' : 'amber'} className="mt-1 text-[11px]">
      {hppRow.unitHppMinor != null && hppRow.hppCurrency != null
        ? t('recipe.chipLabel', {
            hpp: formatMoney(hppRow.unitHppMinor, hppRow.hppCurrency, locale),
            margin: margin != null ? formatPercent(margin, locale) : '—',
          })
        : t('recipe.completenessMissingCost')}
    </Badge>
  )
}

// ---------------------------------------------------------------------------
// Category section
// ---------------------------------------------------------------------------

function CategorySection({
  categoryName,
  items,
  session,
  locale,
  hppByItemId,
  onManageOptions,
  onManageRecipe,
}: {
  categoryName: string
  items: MenuItem[]
  session: CompanySession
  locale: string
  hppByItemId: Map<string, HppSummaryRow>
  onManageOptions: (item: MenuItem) => void
  onManageRecipe: (item: MenuItem) => void
}) {
  const [expanded, setExpanded] = useState(true)

  return (
    <div className="mb-4">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="mb-1 flex items-center gap-1.5 rounded-lg px-1 py-1 text-[11px] font-bold uppercase tracking-[0.05em] text-ink-3 transition-colors hover:text-ink-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDown className="size-3.5" aria-hidden="true" />
        ) : (
          <ChevronRight className="size-3.5" aria-hidden="true" />
        )}
        {categoryName}
        <span className="tnum ml-1 rounded-full bg-ink-50 px-1.5 py-0.5 text-[10px] font-bold text-ink-3">
          {items.length}
        </span>
      </button>

      {expanded ? (
        <Card className="rounded-[20px] p-2.5">
          {items.map((item) => (
            <ItemRow
              key={item.id}
              item={item}
              session={session}
              locale={locale}
              hppRow={hppByItemId.get(item.id)}
              onManageOptions={() => onManageOptions(item)}
              onManageRecipe={() => onManageRecipe(item)}
            />
          ))}
        </Card>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Main inner page
// ---------------------------------------------------------------------------

function MenuManagementInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const { theme, toggle } = useTheme()
  const locale = localeOf(i18n.language)

  const menuQuery = useMenu(session)
  const hppSummaryQuery = useHppSummary(session)
  const [showCreateItem, setShowCreateItem] = useState(false)
  const [showManageCategories, setShowManageCategories] = useState(false)
  // "Lacak stok semua menu" — the bulk 1:1 auto-link confirmation dialog.
  const [showAutoLink, setShowAutoLink] = useState(false)
  const [managingOptionsFor, setManagingOptionsFor] = useState<MenuItem | null>(null)
  const [recipeFor, setRecipeFor] = useState<MenuItem | null>(null)

  const items = menuQuery.data ?? []
  const hppByItemId = new Map((hppSummaryQuery.data ?? []).map((r) => [r.menuItemId, r]))

  // Group items LANGUAGE-CANONICALLY (categoryCanon): en/id variants of the same starter
  // template land in ONE section; the heading follows the current UI language.
  const categoryMap = new Map<string, { name: string; items: MenuItem[] }>()
  for (const item of items) {
    const raw = item.category ?? t('menu.uncategorized')
    const key = canonicalCategoryKey(raw)
    let bucket = categoryMap.get(key)
    if (!bucket) {
      bucket = { name: raw, items: [] }
      categoryMap.set(key, bucket)
    }
    bucket.items.push(item)
  }
  const categories = [...categoryMap.entries()].map(
    ([key, b]) => [key, displayCategoryName(b.name, t), b.items] as const,
  )

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      {/* Header — mirrors POS chrome */}
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        <Link
          to="/pos"
          aria-label={t('menu.backToPos')}
          title={t('menu.backToPos')}
          className="grid size-[38px] shrink-0 place-items-center rounded-full border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          <ArrowLeft className="size-[18px]" />
        </Link>

        <div className="min-w-0 flex-1">
          <div className="font-display text-[17px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {t('menu.title')}
          </div>
          <div className="truncate text-xs text-ink-3">{session.name}</div>
        </div>

        {/* Outlet picker — tracks which outlet menu edits and KDS tickets belong to. Phone:
            drops to its own full-width second row — inline it crushed the title to nothing
            on a 360px header (same fix as IngredientManagement). */}
        <div className="min-w-0 max-sm:order-last max-sm:w-full">
          <OutletPicker />
        </div>

        {/* Phone: both action buttons drop to their own shared row (after the picker row) —
            inline they crushed the title even with the picker moved; full-width halves give
            proper touch targets instead. */}
        <Button
          variant="outline"
          onClick={() => setShowManageCategories(true)}
          className="shrink-0 max-sm:order-last max-sm:flex-1"
        >
          <span className="hidden sm:inline">{t('menu.categories.manage')}</span>
          <span className="sm:hidden">{t('menu.categories.manageShort')}</span>
        </Button>

        {/* "Lacak stok semua menu" — bulk 1:1 auto-link so every menu sale depletes stock. */}
        <Button
          variant="outline"
          onClick={() => setShowAutoLink(true)}
          className="shrink-0 max-sm:order-last max-sm:flex-1"
        >
          <span className="hidden sm:inline">{t('menu.autoLink.action')}</span>
          <span className="sm:hidden">{t('menu.autoLink.actionShort')}</span>
        </Button>

        <Button
          onClick={() => setShowCreateItem(true)}
          className="shrink-0 max-sm:order-last max-sm:flex-1"
        >
          <Plus className="size-4" />
          <span>{t('menu.addItem')}</span>
        </Button>

        <button
          type="button"
          onClick={toggle}
          aria-label={t('a11y.toggleTheme')}
          title={t('a11y.toggleTheme')}
          className="grid size-[38px] shrink-0 place-items-center rounded-full border border-line bg-surface text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          {theme === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
        </button>
      </header>

      {/* Body */}
      <main className="min-h-0 flex-1 overflow-y-auto px-4 py-5 sm:px-5 lg:px-8">
        <div className="mx-auto max-w-3xl">
          {menuQuery.isLoading ? (
            <ListSkeleton rows={6} />
          ) : menuQuery.isError ? (
            <Card className="p-8 text-center text-sm text-loss">
              <TriangleAlert className="mx-auto mb-2 size-5" />
              {t('menu.loadError')}
            </Card>
          ) : items.length === 0 ? (
            <Card className="mx-auto max-w-md p-10 text-center">
              <div className="mx-auto grid size-12 place-items-center rounded-full bg-brand-50 text-brand-600">
                <BookOpen className="size-6" />
              </div>
              <h2 className="mt-4 font-display text-xl font-semibold text-ink">
                {t('menu.emptyTitle')}
              </h2>
              <p className="mx-auto mt-1.5 max-w-xs text-sm text-ink-3">{t('menu.emptyHint')}</p>
              <Button className="mt-5" onClick={() => setShowCreateItem(true)}>
                <Plus className="size-4" />
                {t('menu.addItem')}
              </Button>
            </Card>
          ) : (
            <div>
              {categories.map(([catKey, catName, catItems]) => (
                <CategorySection
                  key={catKey}
                  categoryName={catName}
                  items={catItems}
                  session={session}
                  locale={locale}
                  hppByItemId={hppByItemId}
                  onManageOptions={(item) => setManagingOptionsFor(item)}
                  onManageRecipe={(item) => setRecipeFor(item)}
                />
              ))}
            </div>
          )}
        </div>
      </main>

      {/* Dialogs */}
      {showCreateItem ? (
        <CreateItemDialog
          session={session}
          onClose={() => setShowCreateItem(false)}
        />
      ) : null}

      {showAutoLink ? (
        <AutoLinkDialog session={session} locale={locale} onClose={() => setShowAutoLink(false)} />
      ) : null}

      {showManageCategories ? (
        <CategoryManagerDialog
          session={session}
          onClose={() => setShowManageCategories(false)}
        />
      ) : null}

      {managingOptionsFor ? (
        <ModifierGroupsPanel
          session={session}
          item={managingOptionsFor}
          locale={locale}
          onClose={() => setManagingOptionsFor(null)}
        />
      ) : null}

      {recipeFor ? (
        <RecipeDrawer
          session={session}
          item={recipeFor}
          locale={locale}
          onClose={() => setRecipeFor(null)}
        />
      ) : null}
    </div>
  )
}
