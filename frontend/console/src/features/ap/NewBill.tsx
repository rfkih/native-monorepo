import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Skeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { useOrgUnits } from '@/features/org/api'
import { apiFetch } from '@/lib/api'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { allowsFraction, shownUnit, type UnitBearing } from '@/features/inventory/lib/units'
import type { Ingredient } from '@/features/inventory/ingredientApi'
import { parseIngredientLink } from './lib/ingredientLink'
import { useCreateBill, useVendors, type CreateBillLineBody } from './api'
import { SELECT_CLASSES } from './parts'

const TAX_RATE = 0.11

interface DraftLine {
  key: string
  description: string
  quantity: string
  unitPriceMajor: string
  /** ADR 0067 Phase B, §3 — flags this line as a capitalizable inventory purchase. Defaults false;
   *  the backend ignores it unless the company has activated perpetual inventory accounting. */
  inventory: boolean
  /**
   * ADR 0072 P4 — the OPTIONAL ingredient linkage, meaningful only when `inventory` is ticked (the
   * checkbox's `onChange` clears these on untick, so stale hidden state never resurfaces on a
   * re-tick). `ingredientQtyInput` is typed in the ingredient's DISPLAY unit — converted to the
   * BASE unit at submit via `parseIngredientLink`, exactly like the company-expense form.
   */
  ingredientId: string
  ingredientName: string
  ingredientQtyInput: string
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
    ingredientQtyInput: '',
  }
}

/**
 * The outlet's ingredient catalog, scoped to the linkage picker below (ADR 0072 P4) — mirrors
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
 * A line is submittable once it has a description, a positive integer quantity, and a positive
 * unit price. ADR 0072 P4 — when `inventory` is ticked, an OPTIONAL ingredient linkage is also
 * parsed via `parseIngredientLink`: nothing entered stays a plain inventory-flagged line; a
 * PARTIAL entry (e.g. an ingredient picked with no quantity) invalidates the WHOLE line rather
 * than silently dropping the half-entered linkage.
 */
function parseLine(
  line: DraftLine,
  exponent: number,
  ingredientOf: (id: string) => UnitBearing | null,
): { body: CreateBillLineBody; lineTotalMinor: number } | null {
  const description = line.description.trim()
  const quantity = Number(line.quantity)
  const unitPriceMajor = Number(line.unitPriceMajor)
  if (!description) return null
  if (!Number.isInteger(quantity) || quantity <= 0) return null
  if (!Number.isFinite(unitPriceMajor) || unitPriceMajor <= 0) return null
  const unitPriceMinor = Math.round(unitPriceMajor * 10 ** exponent)
  if (unitPriceMinor <= 0) return null

  const body: CreateBillLineBody = { description, quantity, unitPriceMinor, inventory: line.inventory }
  if (line.inventory) {
    const linked = parseIngredientLink(
      {
        ingredientId: line.ingredientId,
        ingredientName: line.ingredientName,
        qtyInput: line.ingredientQtyInput,
      },
      ingredientOf(line.ingredientId),
    )
    if (!linked.valid) return null
    if (linked.link) {
      body.ingredientId = linked.link.ingredientId
      body.ingredientName = linked.link.ingredientName
      body.ingredientQtyBase = linked.link.ingredientQtyBase
    }
  }

  return { body, lineTotalMinor: quantity * unitPriceMinor }
}

/**
 * New bill — pick a vendor, toggle tax, edit line items (add/remove rows), optionally link an
 * inventory-flagged line to an ingredient (ADR 0072 P4 — the linked line auto-receives stock once
 * the bill is POSTED, no separate Terima step), and see a client-side live preview of subtotal/
 * tax/total. The server recomputes and is authoritative; this preview is a convenience only.
 * Currency is always the company's base currency (rule: no currency toggle in the dashboard).
 */
export function NewBill() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const navigate = useNavigate()
  const locale = localeOf(i18n.language)

  const [vendorId, setVendorId] = useState('')
  const [taxable, setTaxable] = useState(false)
  const [lines, setLines] = useState<DraftLine[]>([newLine()])
  // ADR 0072 P4 — filters the ingredient picker ONLY; a bill carries no outlet, so this never
  // reaches the request body.
  const [ingredientOutletId, setIngredientOutletId] = useState('')

  // A new outlet invalidates every picked ingredient (a different outlet's catalog) — clear every
  // line's linkage rather than leaving a stale id that would silently fail to resolve. Adjusted
  // DURING render (the React-endorsed "adjusting state when a prop changes" pattern), not inside a
  // `useEffect`, which would cause an extra cascading render for the same result — mirrors
  // `NewCompanyExpense.tsx`'s identical `linesResetForOutlet` idiom.
  const [linesResetForOutlet, setLinesResetForOutlet] = useState(ingredientOutletId)
  if (ingredientOutletId !== linesResetForOutlet) {
    setLinesResetForOutlet(ingredientOutletId)
    setLines((prev) =>
      prev.map((l) => ({ ...l, ingredientId: '', ingredientName: '', ingredientQtyInput: '' })),
    )
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

  const parsedLines = lines.map((l) => ({ draft: l, parsed: parseLine(l, exponent, ingredientOf) }))
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

          {/* ADR 0072 P4 — the ingredient picker's outlet FILTER; console-only, never sent (a bill
              has no outlet column). Only shown once a line is flagged inventory, to keep the
              common (no-linkage) case uncluttered. */}
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
              const parsed = parseLine(line, exponent, ingredientOf)
              const lineIngredient = ingredientOf(line.ingredientId)
              const linkResult = parseIngredientLink(
                {
                  ingredientId: line.ingredientId,
                  ingredientName: line.ingredientName,
                  qtyInput: line.ingredientQtyInput,
                },
                lineIngredient,
              )
              return (
                <div
                  key={line.key}
                  className="grid grid-cols-1 items-end gap-2.5 rounded-xl border border-line p-3 sm:grid-cols-[1fr_80px_140px_140px_auto]"
                >
                  <Field label={t('ap.newBill.descriptionLabel')} htmlFor={`line-desc-${line.key}`}>
                    <TextInput
                      id={`line-desc-${line.key}`}
                      value={line.description}
                      onChange={(e) => updateLine(line.key, { description: e.target.value })}
                      placeholder={t('ap.newBill.descriptionPlaceholder')}
                    />
                  </Field>
                  <Field label={t('ap.newBill.quantityLabel')} htmlFor={`line-qty-${line.key}`}>
                    <TextInput
                      id={`line-qty-${line.key}`}
                      type="number"
                      min="1"
                      step="1"
                      value={line.quantity}
                      onChange={(e) => updateLine(line.key, { quantity: e.target.value })}
                    />
                  </Field>
                  <Field label={t('ap.newBill.unitPriceLabel')} htmlFor={`line-price-${line.key}`}>
                    <TextInput
                      id={`line-price-${line.key}`}
                      type="number"
                      min="0"
                      step={unitPriceStep}
                      value={line.unitPriceMajor}
                      onChange={(e) => updateLine(line.key, { unitPriceMajor: e.target.value })}
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
                      accounting), so the form stays ready ahead of activation. Unticking clears any
                      ingredient linkage below it (ADR 0072 P4) so stale state never resurfaces. */}
                  <label className="flex cursor-pointer items-center gap-2 sm:col-span-5">
                    <input
                      type="checkbox"
                      checked={line.inventory}
                      onChange={(e) =>
                        updateLine(line.key, {
                          inventory: e.target.checked,
                          ...(e.target.checked
                            ? {}
                            : { ingredientId: '', ingredientName: '', ingredientQtyInput: '' }),
                        })
                      }
                      className="size-4 accent-emerald"
                    />
                    <span className="text-xs font-medium text-ink-2">
                      {t('ap.newBill.inventoryLabel')}
                    </span>
                    <span className="text-xs text-ink-3">{t('ap.newBill.inventoryHint')}</span>
                  </label>

                  {/* ADR 0072 P4 — the OPTIONAL ingredient linkage, only for an inventory-flagged
                      line. Reveals a picker (scoped to the outlet filter above) + a qty input in
                      the ingredient's DISPLAY unit; a linked line auto-receives stock once the
                      bill is POSTED. */}
                  {line.inventory ? (
                    <div className="rounded-xl border border-dashed border-line-strong bg-paper p-3 sm:col-span-5">
                      <p className="text-xs text-ink-3">{t('ap.newBill.ingredientLinkNote')}</p>
                      {!ingredientOutletId ? (
                        <p className="mt-2 text-xs text-ink-3">
                          {t('ap.newBill.ingredientLinkPickOutlet')}
                        </p>
                      ) : ingredientsQuery.isLoading ? (
                        <Skeleton className="mt-2 h-[52px] rounded-xl" />
                      ) : ingredientsQuery.isError ? (
                        <p className="mt-2 text-xs text-loss">{t('ap.newBill.ingredientLinkError')}</p>
                      ) : ingredients.length === 0 ? (
                        <p className="mt-2 text-xs text-ink-3">{t('ap.newBill.ingredientLinkNone')}</p>
                      ) : (
                        <div className="mt-2 grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                          <Field label={t('ap.newBill.ingredientLabel')} htmlFor={`line-ing-${line.key}`}>
                            <select
                              id={`line-ing-${line.key}`}
                              className={SELECT_CLASSES}
                              value={line.ingredientId}
                              onChange={(e) => {
                                const chosen = ingredients.find((i) => i.id === e.target.value)
                                updateLine(line.key, {
                                  ingredientId: e.target.value,
                                  ingredientName: chosen?.name ?? '',
                                  // A different ingredient may carry a different unit/factor — the
                                  // previously typed quantity would silently mean something else.
                                  ingredientQtyInput: '',
                                })
                              }}
                            >
                              <option value="">{t('ap.newBill.ingredientNone')}</option>
                              {ingredients.map((i) => (
                                <option key={i.id} value={i.id}>
                                  {i.name}
                                </option>
                              ))}
                            </select>
                          </Field>
                          <Field
                            label={t('ap.newBill.ingredientQtyLabel', {
                              unit: lineIngredient ? shownUnit(lineIngredient) : '',
                            })}
                            htmlFor={`line-ing-qty-${line.key}`}
                          >
                            <TextInput
                              id={`line-ing-qty-${line.key}`}
                              type="number"
                              min="0"
                              step={lineIngredient && allowsFraction(lineIngredient) ? 'any' : '1'}
                              inputMode={
                                lineIngredient && allowsFraction(lineIngredient) ? 'decimal' : 'numeric'
                              }
                              value={line.ingredientQtyInput}
                              onChange={(e) =>
                                updateLine(line.key, { ingredientQtyInput: e.target.value })
                              }
                              placeholder="0"
                              disabled={!line.ingredientId}
                            />
                          </Field>
                        </div>
                      )}
                      {!linkResult.valid ? (
                        <p className="mt-2 text-xs text-loss">{t('ap.newBill.ingredientLinkInvalid')}</p>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              )
            })}
          </div>
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
