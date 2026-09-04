import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useOrgUnits } from '@/features/org/api'
import { apiFetch } from '@/lib/api'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { allowsFraction, shownUnit, type UnitBearing } from '@/features/inventory/lib/units'
import type { Ingredient } from '@/features/inventory/ingredientApi'
import { CreateIngredientInline } from '@/features/inventory/CreateIngredientInline'
import { parseInventoryLine } from './lib/ingredientLink'
import { useCreateBill, useVendors, type CreateBillLineBody } from './api'
import { SELECT_CLASSES } from './parts'

const TAX_RATE = 0.11

interface DraftLine {
  key: string
  /**
   * Owner UX correction (2026-09-04) — when `inventory` is ticked, this field IS the ingredient
   * combobox: it holds either the search query (unresolved) or the linked ingredient's own name
   * (resolved, `ingredientId` set). Unticking clears the linkage but keeps whatever text is here —
   * it reverts to being a plain free-text description.
   */
  description: string
  /** Plain mode: a whole count. Persediaan mode: the bahan quantity in the ingredient's DISPLAY
   *  unit (decimals allowed, e.g. "2.5" for kg) — same input slot, different meaning. */
  quantity: string
  /** Plain mode: the PER-UNIT price. Persediaan mode: the line's TOTAL price — same input slot,
   *  different meaning (see `parseLine`'s doc). */
  unitPriceMajor: string
  /** ADR 0067 Phase B, §3 — flags this line as a capitalizable inventory purchase. Defaults false;
   *  the backend ignores it unless the company has activated perpetual inventory accounting. */
  inventory: boolean
  /** ADR 0072 P4 — set once the combobox resolves to a real ingredient (picked or freshly
   *  created); blank while the operator is still typing/searching. */
  ingredientId: string
  ingredientName: string
}

function newLine(): DraftLine {
  return {
    key: crypto.randomUUID(),
    description: '',
    quantity: '1',
    unitPriceMajor: '',
    inventory: false,
    ingredientId: '',
    ingredientName: '',
  }
}

/**
 * The outlet's ingredient catalog, scoped to the linkage combobox below (ADR 0072 P4) — mirrors
 * `features/expenses/NewCompanyExpense.tsx`'s identically-named local hook exactly (not
 * `ingredientApi.ts`'s `useIngredients`, which has no `enabled` gate). `businessId` here is a
 * console-only FILTER on the picker — a bill carries no outlet column at all (see
 * `CreateBillLineBody`'s doc), so it is NEVER sent on submit.
 */
function useIngredientsForOutlet(params: {
  companyId: string
  actor: string
  businessId: string
  enabled: boolean
}) {
  const { companyId, actor, businessId, enabled } = params
  return useQuery({
    enabled: enabled && !!businessId,
    queryKey: ['apBillIngredients', companyId, businessId],
    queryFn: async () => {
      const result = await apiFetch<Ingredient[]>('/api/v1/ingredients', {
        tenant: { companyId, actor },
        query: { businessId },
      })
      return result ?? []
    },
  })
}

/**
 * A PLAIN (non-Persediaan) line is submittable once it has a description, a positive integer
 * quantity, and a positive unit price — unchanged. A Persediaan-TICKED line is delegated entirely
 * to `parseInventoryLine` (ADR 0072 P4, reworked): the description field IS the ingredient
 * combobox there, so `quantity`/`unitPriceMajor` carry the bahan qty (display unit) / TOTAL harga
 * instead of a count/per-unit price — see that function's doc for the wire mapping.
 */
function parseLine(
  line: DraftLine,
  exponent: number,
  currency: string,
  ingredientOf: (id: string) => UnitBearing | null,
): { body: CreateBillLineBody; lineTotalMinor: number } | null {
  if (line.inventory) {
    const parsed = parseInventoryLine(
      {
        ingredientId: line.ingredientId,
        ingredientName: line.ingredientName,
        qtyInput: line.quantity,
        totalInput: line.unitPriceMajor,
      },
      ingredientOf(line.ingredientId),
      currency,
    )
    if (!parsed) return null
    return {
      body: {
        description: parsed.description,
        quantity: parsed.quantity,
        unitPriceMinor: parsed.unitPriceMinor,
        inventory: true,
        ingredientId: parsed.ingredientId,
        ingredientName: parsed.ingredientName,
        ingredientQtyBase: parsed.ingredientQtyBase,
      },
      // quantity is always 1 on this path — the total IS the line total.
      lineTotalMinor: parsed.unitPriceMinor,
    }
  }

  const description = line.description.trim()
  const quantity = Number(line.quantity)
  const unitPriceMajor = Number(line.unitPriceMajor)
  if (!description) return null
  if (!Number.isInteger(quantity) || quantity <= 0) return null
  if (!Number.isFinite(unitPriceMajor) || unitPriceMajor <= 0) return null
  const unitPriceMinor = Math.round(unitPriceMajor * 10 ** exponent)
  if (unitPriceMinor <= 0) return null
  return {
    body: { description, quantity, unitPriceMinor, inventory: false },
    lineTotalMinor: quantity * unitPriceMinor,
  }
}

/**
 * New bill — pick a vendor, toggle tax, edit line items (add/remove rows). Owner UX correction
 * (2026-09-04): ticking "Persediaan" on a line turns its DESCRIPTION into a type-ahead combobox
 * over the outlet's ingredient catalog — one step instead of description + a separate picker.
 * Picking (or inline-creating) an ingredient links it; the line's qty/price inputs become the
 * bahan quantity (display unit) and the line's TOTAL price. The linked line auto-receives stock
 * once the bill is POSTED, no separate Terima step. A client-side live preview of subtotal/tax/
 * total follows; the server recomputes and is authoritative. Currency is always the company's base
 * currency (rule: no currency toggle in the dashboard).
 */
export function NewBill() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const navigate = useNavigate()
  const locale = localeOf(i18n.language)

  const [vendorId, setVendorId] = useState('')
  const [taxable, setTaxable] = useState(false)
  const [lines, setLines] = useState<DraftLine[]>([newLine()])
  // ADR 0072 P4 — filters the ingredient combobox ONLY; a bill carries no outlet, so this never
  // reaches the request body.
  const [ingredientOutletId, setIngredientOutletId] = useState('')
  // Which line's combobox dropdown is open — at most one at a time.
  const [openComboboxKey, setOpenComboboxKey] = useState<string | null>(null)
  // Which line's "+ Tambah bahan baru" mini-form is open, and what to prefill it with (whatever
  // was typed that matched nothing).
  const [creatingForKey, setCreatingForKey] = useState<string | null>(null)
  const [createPrefill, setCreatePrefill] = useState('')

  // A new outlet invalidates every picked ingredient (a different outlet's catalog) — clear every
  // line's linkage rather than leaving a stale id that would silently fail to resolve. Adjusted
  // DURING render (the React-endorsed "adjusting state when a prop changes" pattern), not inside a
  // `useEffect`, which would cause an extra cascading render for the same result — mirrors
  // `NewCompanyExpense.tsx`'s identical `linesResetForOutlet` idiom.
  const [linesResetForOutlet, setLinesResetForOutlet] = useState(ingredientOutletId)
  if (ingredientOutletId !== linesResetForOutlet) {
    setLinesResetForOutlet(ingredientOutletId)
    setLines((prev) => prev.map((l) => ({ ...l, ingredientId: '', ingredientName: '' })))
  }

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''

  const vendorsQuery = useVendors({ companyId, actor, enabled: !!company })
  const outletsQuery = useOrgUnits({ companyId, actor, enabled: !!company })
  const ingredientsQuery = useIngredientsForOutlet({
    companyId,
    actor,
    businessId: ingredientOutletId,
    enabled: !!company,
  })
  const mutation = useCreateBill({ companyId, actor })

  if (!company) {
    return <EmptyState title={t('ap.bills.noCompany')} hint={t('ap.bills.noCompanyHint')} />
  }

  const currency = company.baseCurrency
  const exponent = isoMinorExponent(currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const unitPriceStep = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()
  const vendors = vendorsQuery.data ?? []
  const outlets = (outletsQuery.data ?? []).filter((u) => u.active)
  const ingredients = ingredientsQuery.data ?? []
  const ingredientOf = (id: string): UnitBearing | null =>
    ingredients.find((i) => i.id === id) ?? null
  // Owner request — "+ Tambah bahan baru": the session `useCreateIngredient` posts against, scoped
  // to the picker's outlet FILTER (mirrors `OutletGate`'s `{ ...company, businessId }` idiom).
  const ingredientCreateSession: CompanySession = { ...company, businessId: ingredientOutletId }

  const parsedLines = lines.map((l) => ({
    draft: l,
    parsed: parseLine(l, exponent, currency, ingredientOf),
  }))
  const validLineBodies = parsedLines
    .map((p) => p.parsed?.body)
    .filter((b): b is CreateBillLineBody => !!b)
  const subtotalMinor = parsedLines.reduce((sum, p) => sum + (p.parsed?.lineTotalMinor ?? 0), 0)
  const taxMinor = taxable ? Math.round(subtotalMinor * TAX_RATE) : 0
  const totalMinor = subtotalMinor + taxMinor

  const canSubmit = !!vendorId && validLineBodies.length > 0 && !mutation.isPending
  const anyInventoryLine = lines.some((l) => l.inventory)

  function updateLine(key: string, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }
  function addLine() {
    setLines((prev) => [...prev, newLine()])
  }
  function removeLine(key: string) {
    setLines((prev) => (prev.length > 1 ? prev.filter((l) => l.key !== key) : prev))
    if (creatingForKey === key) setCreatingForKey(null)
    if (openComboboxKey === key) setOpenComboboxKey(null)
  }
  /** Resolves `ingredient` onto `key`'s line — from a combobox pick, a fresh inline create, or the
   *  "select existing instead" 409 recovery. The description mirrors the ingredient's name (it IS
   *  the combobox's displayed value once linked). */
  function selectIngredient(key: string, ingredient: Ingredient) {
    updateLine(key, {
      description: ingredient.name,
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
    })
    setOpenComboboxKey(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    mutation.mutate(
      { vendorId, currency, taxable, lines: validLineBodies },
      {
        onSuccess: (created) => {
          if (created) navigate(`/bills/${created.id}`)
        },
      },
    )
  }

  return (
    <div className="mx-auto flex max-w-[820px] flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('ap.newBill.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('ap.newBill.subtitle')}</p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-5">
        <Card className="p-6">
          <Field label={t('ap.newBill.vendorLabel')} htmlFor="bill-vendor">
            {vendors.length === 0 && !vendorsQuery.isLoading ? (
              <p className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm text-ink-3">
                {t('ap.newBill.noVendors')}{' '}
                <Link to="/vendors" className="font-semibold text-brand-700 hover:underline">
                  {t('ap.newBill.addVendorLink')}
                </Link>
              </p>
            ) : (
              <select
                id="bill-vendor"
                value={vendorId}
                onChange={(e) => setVendorId(e.target.value)}
                className={SELECT_CLASSES}
                required
              >
                <option value="" disabled>
                  {t('ap.newBill.selectVendor')}
                </option>
                {vendors.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.name}
                  </option>
                ))}
              </select>
            )}
          </Field>

          <label className="mt-4 flex cursor-pointer items-start gap-3 rounded-2xl border border-line bg-surface p-4">
            <input
              type="checkbox"
              checked={taxable}
              onChange={(e) => setTaxable(e.target.checked)}
              className="mt-0.5 size-4 accent-emerald"
            />
            <span>
              <span className="block text-sm font-medium text-ink">
                {t('ap.newBill.taxableLabel')}
              </span>
              <span className="mt-0.5 block text-xs text-ink-3">
                {t('ap.newBill.taxableHint')}
              </span>
            </span>
          </label>
        </Card>

        <Card className="p-6">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
              {t('ap.newBill.lines')}
            </h2>
            <Button type="button" variant="outline" size="sm" onClick={addLine}>
              <Plus className="size-4" />
              {t('ap.newBill.addLine')}
            </Button>
          </div>

          {/* ADR 0072 P4 — the ingredient combobox's outlet FILTER; console-only, never sent (a
              bill has no outlet column). Only shown once a line is flagged inventory, to keep the
              common (no-linkage) case uncluttered — unchanged from before this rework. */}
          {anyInventoryLine ? (
            <div className="mb-3">
              <Field
                label={t('ap.newBill.ingredientOutletLabel')}
                htmlFor="bill-ingredient-outlet"
                hint={t('ap.newBill.ingredientOutletHint')}
              >
                <select
                  id="bill-ingredient-outlet"
                  className={SELECT_CLASSES}
                  value={ingredientOutletId}
                  onChange={(e) => setIngredientOutletId(e.target.value)}
                >
                  <option value="">{t('ap.newBill.ingredientOutletPlaceholder')}</option>
                  {outlets.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
          ) : null}

          <div className="flex flex-col gap-3">
            {lines.map((line, idx) => {
              const parsed = parseLine(line, exponent, currency, ingredientOf)
              const lineIngredient = line.inventory ? ingredientOf(line.ingredientId) : null
              const outletChosen = !!ingredientOutletId
              return (
                <div
                  key={line.key}
                  className="grid grid-cols-1 items-end gap-2.5 rounded-xl border border-line p-3 sm:grid-cols-[1fr_80px_140px_140px_auto]"
                >
                  {line.inventory ? (
                    <IngredientComboboxField
                      line={line}
                      ingredients={ingredients}
                      ingredientsLoading={ingredientsQuery.isLoading}
                      ingredientsError={ingredientsQuery.isError}
                      outletChosen={outletChosen}
                      open={openComboboxKey === line.key}
                      onOpen={() => setOpenComboboxKey(line.key)}
                      onClose={() =>
                        setOpenComboboxKey((k) => (k === line.key ? null : k))
                      }
                      onQueryChange={(text) => {
                        updateLine(line.key, {
                          description: text,
                          ingredientId: '',
                          ingredientName: '',
                        })
                        setOpenComboboxKey(line.key)
                      }}
                      onSelect={(ingredient) => selectIngredient(line.key, ingredient)}
                      onCreateNew={(typedName) => {
                        setCreatePrefill(typedName)
                        setCreatingForKey(line.key)
                        setOpenComboboxKey(null)
                      }}
                    />
                  ) : (
                    <Field label={t('ap.newBill.descriptionLabel')} htmlFor={`line-desc-${line.key}`}>
                      <TextInput
                        id={`line-desc-${line.key}`}
                        value={line.description}
                        onChange={(e) => updateLine(line.key, { description: e.target.value })}
                        placeholder={t('ap.newBill.descriptionPlaceholder')}
                      />
                    </Field>
                  )}
                  <Field
                    label={
                      line.inventory
                        ? t('ap.newBill.ingredientQtyLabel', {
                            unit: lineIngredient ? shownUnit(lineIngredient) : '',
                          })
                        : t('ap.newBill.quantityLabel')
                    }
                    htmlFor={`line-qty-${line.key}`}
                  >
                    <TextInput
                      id={`line-qty-${line.key}`}
                      type="number"
                      min={line.inventory ? '0' : '1'}
                      step={
                        line.inventory
                          ? lineIngredient && allowsFraction(lineIngredient)
                            ? 'any'
                            : '1'
                          : '1'
                      }
                      inputMode={
                        line.inventory && lineIngredient && allowsFraction(lineIngredient)
                          ? 'decimal'
                          : 'numeric'
                      }
                      value={line.quantity}
                      onChange={(e) => updateLine(line.key, { quantity: e.target.value })}
                      disabled={line.inventory && !line.ingredientId}
                    />
                  </Field>
                  <Field
                    label={
                      line.inventory ? t('ap.newBill.ingredientTotalLabel') : t('ap.newBill.unitPriceLabel')
                    }
                    htmlFor={`line-price-${line.key}`}
                  >
                    <TextInput
                      id={`line-price-${line.key}`}
                      type="number"
                      min="0"
                      step={line.inventory ? 'any' : unitPriceStep}
                      inputMode="numeric"
                      value={line.unitPriceMajor}
                      onChange={(e) => updateLine(line.key, { unitPriceMajor: e.target.value })}
                      disabled={line.inventory && !line.ingredientId}
                    />
                  </Field>
                  <Field label={t('ap.newBill.lineTotal')}>
                    <p className="tnum flex h-[52px] items-center rounded-xl border border-line bg-paper px-3.5 font-mono text-sm text-ink">
                      {formatMoney(parsed?.lineTotalMinor ?? 0, currency, locale)}
                    </p>
                  </Field>
                  <button
                    type="button"
                    aria-label={t('ap.newBill.removeLine', { n: idx + 1 })}
                    onClick={() => removeLine(line.key)}
                    disabled={lines.length === 1}
                    className="grid size-10 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-tint-loss hover:text-loss disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <Trash2 className="size-4" />
                  </button>
                  {/* ADR 0067 Phase B, §3 — per-line inventory flag. Shows unconditionally (the
                      backend ignores it until the company activates perpetual inventory
                      accounting), so the form stays ready ahead of activation. Ticking/unticking
                      resets qty/price — their MEANING flips (count/per-unit price <-> bahan qty/
                      total harga), so a stale number is never silently reinterpreted as the wrong
                      thing; unticking keeps the description text (ADR 0072 P4 UX rework). */}
                  <label className="flex cursor-pointer items-center gap-2 sm:col-span-5">
                    <input
                      type="checkbox"
                      checked={line.inventory}
                      onChange={(e) => {
                        const checked = e.target.checked
                        updateLine(line.key, {
                          inventory: checked,
                          ingredientId: checked ? line.ingredientId : '',
                          ingredientName: checked ? line.ingredientName : '',
                          quantity: checked ? '' : '1',
                          unitPriceMajor: '',
                        })
                        setOpenComboboxKey(null)
                      }}
                      className="size-4 accent-emerald"
                    />
                    <span className="text-xs font-medium text-ink-2">
                      {t('ap.newBill.inventoryLabel')}
                    </span>
                    <span className="text-xs text-ink-3">{t('ap.newBill.inventoryHint')}</span>
                  </label>

                  {line.inventory ? (
                    <p className="text-xs text-ink-3 sm:col-span-5">
                      {t('ap.newBill.ingredientLinkNote')}
                    </p>
                  ) : null}
                  {line.inventory && !parsed ? (
                    <p className="text-xs text-ink-3 sm:col-span-5">
                      {t('ap.newBill.ingredientLineIncomplete')}
                    </p>
                  ) : null}
                </div>
              )
            })}
          </div>

          {creatingForKey ? (
            <CreateIngredientInline
              session={ingredientCreateSession}
              existingIngredients={ingredients}
              initialName={createPrefill}
              onClose={() => setCreatingForKey(null)}
              onCreated={(created) => {
                void ingredientsQuery.refetch()
                selectIngredient(creatingForKey, created)
                setCreatingForKey(null)
              }}
              onSelectExisting={(existing) => {
                selectIngredient(creatingForKey, existing)
                setCreatingForKey(null)
              }}
            />
          ) : null}
        </Card>

        <Card className="p-6">
          <h2 className="mb-3 text-[13px] font-bold uppercase tracking-[0.08em] text-ink-3">
            {t('ap.newBill.preview')}
          </h2>
          <div className="flex flex-col gap-2 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-ink-2">{t('ap.newBill.subtotal')}</span>
              <span className="tnum font-mono text-ink">
                {formatMoney(subtotalMinor, currency, locale)}
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-ink-2">{t('ap.newBill.tax')}</span>
              <span className="tnum font-mono text-ink">
                {formatMoney(taxMinor, currency, locale)}
              </span>
            </div>
            <div className="flex items-center justify-between border-t-[1.5px] border-line-strong pt-2">
              <span className="font-semibold text-ink">{t('ap.newBill.total')}</span>
              <span className="tnum font-mono text-lg font-semibold text-ink">
                {formatMoney(totalMinor, currency, locale)}
              </span>
            </div>
          </div>
          <p className="mt-3 text-xs text-ink-3">{t('ap.newBill.previewNote')}</p>
        </Card>

        {mutation.isError ? (
          <p className="text-sm text-loss">{t('ap.newBill.errorTitle')}</p>
        ) : null}

        <div className="flex justify-end gap-3">
          <Button type="submit" disabled={!canSubmit}>
            {mutation.isPending ? t('ap.newBill.submitting') : t('ap.newBill.submit')}
          </Button>
        </div>
      </form>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Ingredient combobox (Persediaan mode's description field)
// ---------------------------------------------------------------------------

/**
 * The description field once "Persediaan" is ticked: a type-ahead combobox over the outlet's
 * ingredient catalog. Typing filters by name (case-insensitive substring); a match list picks
 * from the catalog, no match at all offers "+ Tambah bahan baru" prefilled with what was typed.
 * Selecting/creating closes the dropdown and resolves the line's linkage.
 *
 * Keeps focus on the input through a suggestion click via `onMouseDown` + `preventDefault` (not a
 * `setTimeout`/`relatedTarget` blur dance) — the click's `onClick` still fires normally on mouseup,
 * so a plain `onBlur` on the input is enough to close the dropdown on a genuine "click elsewhere".
 */
function IngredientComboboxField({
  line,
  ingredients,
  ingredientsLoading,
  ingredientsError,
  outletChosen,
  open,
  onOpen,
  onClose,
  onQueryChange,
  onSelect,
  onCreateNew,
}: {
  line: DraftLine
  ingredients: Ingredient[]
  ingredientsLoading: boolean
  ingredientsError: boolean
  outletChosen: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
  onQueryChange: (text: string) => void
  onSelect: (ingredient: Ingredient) => void
  onCreateNew: (typedName: string) => void
}) {
  const { t } = useTranslation()
  const query = line.description.trim().toLowerCase()
  const matches = query ? ingredients.filter((i) => i.name.toLowerCase().includes(query)) : ingredients
  const listboxId = `line-ing-listbox-${line.key}`

  return (
    <Field label={t('ap.newBill.ingredientLabel')} htmlFor={`line-ing-${line.key}`}>
      <div className="relative">
        <TextInput
          id={`line-ing-${line.key}`}
          value={line.description}
          onFocus={onOpen}
          onBlur={onClose}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder={
            outletChosen
              ? t('ap.newBill.ingredientComboboxPlaceholder')
              : t('ap.newBill.ingredientLinkPickOutlet')
          }
          disabled={!outletChosen}
          autoComplete="off"
          role="combobox"
          aria-expanded={open && outletChosen}
          aria-haspopup="listbox"
          aria-controls={listboxId}
        />
        {open && outletChosen ? (
          <div
            id={listboxId}
            role="listbox"
            aria-label={t('ap.newBill.ingredientLabel')}
            className="absolute left-0 top-[calc(100%+4px)] z-20 max-h-56 w-full overflow-y-auto rounded-xl border border-line bg-surface shadow-xl"
          >
            {ingredientsLoading ? (
              <p className="px-3.5 py-2.5 text-xs text-ink-3">{t('common.loading')}</p>
            ) : ingredientsError ? (
              <p className="px-3.5 py-2.5 text-xs text-loss">{t('ap.newBill.ingredientLinkError')}</p>
            ) : query === '' && matches.length === 0 ? (
              <p className="px-3.5 py-2.5 text-xs text-ink-3">
                {t('ap.newBill.ingredientComboboxEmpty')}
              </p>
            ) : matches.length > 0 ? (
              matches.slice(0, 30).map((i) => (
                <button
                  key={i.id}
                  type="button"
                  role="option"
                  aria-selected={i.id === line.ingredientId}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => onSelect(i)}
                  className="flex w-full items-center px-3.5 py-2.5 text-left text-sm text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
                >
                  {i.name}
                </button>
              ))
            ) : (
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => onCreateNew(line.description.trim())}
                className="flex w-full items-center gap-1.5 px-3.5 py-2.5 text-left text-sm font-semibold text-brand-700 transition-colors hover:bg-hover"
              >
                <Plus className="size-3.5 shrink-0" aria-hidden="true" />
                {t('ap.newBill.ingredientCreateHint', { name: line.description.trim() })}
              </button>
            )}
          </div>
        ) : null}
      </div>
    </Field>
  )
}
