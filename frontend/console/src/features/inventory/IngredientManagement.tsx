/**
 * IngredientManagement — the per-outlet ingredient (bahan) catalog behind the stock opname
 * (ADR 0046 phase 1). Full-screen /ingredients route modeled on MenuManagement: NoCompany
 * guard → OutletGate (restaurant, real outlet id per ADR 0012) → keyed inner remount.
 *
 * Quantities are integers in the ingredient's own unit; the unit picker offers g/ml/pcs/pack
 * only (no kg/L — fractional quantities cannot exist, see ingredientApi.ts). Cost is
 * optional and entered in MAJOR units of the company base currency (converted exponent-aware
 * via parseDiscountInput, rendered via formatMoney — rule 8); an uncosted ingredient is
 * counted at opname but never posts to the books.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowLeft, Moon, Package, Plus, Sun, TriangleAlert, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, TextInput } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { OutletGate } from '@/components/OutletGate'
import { OutletPicker } from '@/components/OutletPicker'
import { ApiError } from '@/lib/api'
import { useSession, type CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { parseDiscountInput } from '@/features/pos/lib/discountInput'
import { minorToMajorInput } from '@/features/pos/lib/registerFloat'
import { formatQty } from '@/features/stocktake/lib/qty'
import {
  INGREDIENT_UNITS,
  useAddIngredientStock,
  useCreateIngredient,
  useDeactivateIngredient,
  useIngredients,
  useSetIngredientStock,
  useUpdateIngredient,
  type Ingredient,
} from './ingredientApi'

export function IngredientManagement() {
  const { company } = useSession()
  const { t } = useTranslation()
  if (!company) {
    return (
      <div className="grid min-h-screen place-items-center bg-paper px-5">
        <Card className="w-full max-w-md p-10 text-center">
          <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
          <p className="mt-2 text-sm text-ink-3">{t('dashboard.noCompanyHint')}</p>
        </Card>
      </div>
    )
  }
  // Keyed remount on outlet change — dialog/edit state must not bleed across outlets.
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(session) => (
        <IngredientManagementInner
          key={session.businessId}
          session={session}
          baseCurrency={company.baseCurrency}
        />
      )}
    </OutletGate>
  )
}

function IngredientManagementInner({
  session,
  baseCurrency,
}: {
  session: CompanySession
  baseCurrency: string
}) {
  const { t, i18n } = useTranslation()
  const { theme, toggle } = useTheme()
  const locale = localeOf(i18n.language)

  const query = useIngredients(session)
  const [showCreate, setShowCreate] = useState(false)
  const [editing, setEditing] = useState<Ingredient | null>(null)
  const [receiving, setReceiving] = useState<Ingredient | null>(null)
  const [setting, setSetting] = useState<Ingredient | null>(null)

  const ingredients = query.data ?? []

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      {/* Header — mirrors MenuManagement chrome */}
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        <Link
          to="/pos"
          aria-label={t('inventory.backToPos')}
          title={t('inventory.backToPos')}
          className="grid size-[38px] shrink-0 place-items-center rounded-full border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          <ArrowLeft className="size-[18px]" />
        </Link>

        <div className="min-w-0 flex-1">
          <div className="font-display text-[17px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {t('inventory.title')}
          </div>
          <div className="truncate text-xs text-ink-3">{session.name}</div>
        </div>

        {/* Phone: the picker drops to its own full-width second row (order-last inside the
            flex-wrap) — inline it crushed the title to nothing on a 360px header. */}
        <div className="min-w-0 max-sm:order-last max-sm:w-full">
          <OutletPicker />
        </div>

        <Button onClick={() => setShowCreate(true)} className="shrink-0">
          <Plus className="size-4" />
          <span className="hidden sm:inline">{t('inventory.addAction')}</span>
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
          {query.isLoading ? (
            <ListSkeleton rows={6} />
          ) : query.isError ? (
            <Card className="p-8 text-center text-sm text-loss">
              <TriangleAlert className="mx-auto mb-2 size-5" />
              {t('inventory.loadError')}
            </Card>
          ) : ingredients.length === 0 ? (
            <Card className="mx-auto max-w-md p-10 text-center">
              <div className="mx-auto grid size-12 place-items-center rounded-full bg-brand-50 text-brand-600">
                <Package className="size-5" aria-hidden="true" />
              </div>
              <h2 className="mt-3 font-display text-lg font-semibold text-ink">
                {t('inventory.emptyTitle')}
              </h2>
              <p className="mt-1.5 text-sm text-ink-3">{t('inventory.emptyHint')}</p>
              <Button className="mt-5" onClick={() => setShowCreate(true)}>
                <Plus className="size-4" /> {t('inventory.addAction')}
              </Button>
            </Card>
          ) : (
            <Card className="divide-y divide-line p-2">
              {ingredients.map((ing) => (
                <IngredientRow
                  key={ing.id}
                  ingredient={ing}
                  locale={locale}
                  onReceive={() => setReceiving(ing)}
                  onSet={() => setSetting(ing)}
                  onEdit={() => setEditing(ing)}
                />
              ))}
            </Card>
          )}
        </div>
      </main>

      {showCreate ? (
        <IngredientFormDialog
          session={session}
          baseCurrency={baseCurrency}
          ingredient={null}
          onClose={() => setShowCreate(false)}
        />
      ) : null}
      {editing ? (
        <IngredientFormDialog
          session={session}
          baseCurrency={baseCurrency}
          ingredient={editing}
          onClose={() => setEditing(null)}
        />
      ) : null}
      {receiving ? (
        <ReceiveDialog session={session} ingredient={receiving} locale={locale} onClose={() => setReceiving(null)} />
      ) : null}
      {setting ? (
        <SetQtyDialog session={session} ingredient={setting} locale={locale} onClose={() => setSetting(null)} />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

function IngredientRow({
  ingredient,
  locale,
  onReceive,
  onSet,
  onEdit,
}: {
  ingredient: Ingredient
  locale: string
  onReceive: () => void
  onSet: () => void
  onEdit: () => void
}) {
  const { t } = useTranslation()
  const low = ingredient.stockQty === 0
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl px-3 py-3 transition-colors hover:bg-hover">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-ink">{ingredient.name}</div>
        {ingredient.unitCostMinor != null && ingredient.costCurrency != null ? (
          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
            {t('inventory.costPerUnit', {
              cost: formatMoney(ingredient.unitCostMinor, ingredient.costCurrency, locale),
              unit: ingredient.unit,
            })}
          </div>
        ) : (
          <div className="mt-0.5 text-xs text-ink-3">{t('inventory.noCost')}</div>
        )}
      </div>

      <div
        className={cn(
          'tnum shrink-0 rounded-xl border px-2.5 py-1.5 font-mono text-xs font-semibold',
          low ? 'border-loss/30 bg-tint-loss text-loss' : 'border-line bg-paper text-ink-2',
        )}
      >
        {formatQty(ingredient.stockQty, locale)} {ingredient.unit}
      </div>

      {/* Phone: a full-width three-up grid with taller touch targets; desktop keeps the
          compact inline cluster. */}
      <div className="flex shrink-0 items-center gap-1.5 max-sm:grid max-sm:w-full max-sm:grid-cols-3">
        <button
          type="button"
          onClick={onReceive}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-semibold text-brand-600 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.receiveAction')}
        </button>
        <button
          type="button"
          onClick={onSet}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.setAction')}
        </button>
        <button
          type="button"
          onClick={onEdit}
          className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500 max-sm:py-2.5"
        >
          {t('inventory.editAction')}
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Create / edit dialog (edit also hosts the two-step remove)
// ---------------------------------------------------------------------------

/** The 409 `ingredient-name-conflict` problem — a duplicate ACTIVE name at this outlet. */
function isNameConflict(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-name-conflict')
  )
}

/**
 * The 409 `ingredient-in-recipe` problem (ADR 0050 phase A) — deactivation refused because a
 * live menu-item recipe still references this ingredient. The server's `detail` NAMES the
 * referencing items, so the caller shows it verbatim rather than a generic message.
 */
function isIngredientInRecipe(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('ingredient-in-recipe')
  )
}

function IngredientFormDialog({
  session,
  baseCurrency,
  ingredient,
  onClose,
}: {
  session: CompanySession
  baseCurrency: string
  ingredient: Ingredient | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const create = useCreateIngredient(session)
  const update = useUpdateIngredient(session)
  const deactivate = useDeactivateIngredient(session)

  const [name, setName] = useState(ingredient?.name ?? '')
  const [unit, setUnit] = useState<string>(ingredient?.unit ?? 'pcs')
  const [costInput, setCostInput] = useState(
    ingredient?.unitCostMinor != null
      ? minorToMajorInput(ingredient.unitCostMinor, ingredient.costCurrency ?? baseCurrency)
      : '',
  )
  const [initialQty, setInitialQty] = useState('0')
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [nameError, setNameError] = useState<string | null>(null)

  const busy = create.isPending || update.isPending || deactivate.isPending
  const mutationError = create.error ?? update.error ?? deactivate.error

  function handleSubmit() {
    if (!name.trim()) {
      setNameError(t('inventory.nameRequired'))
      return
    }
    const costMinor = costInput.trim() === '' ? null : parseDiscountInput(costInput, baseCurrency)
    if (ingredient) {
      update.mutate(
        {
          id: ingredient.id,
          name: name.trim(),
          unit,
          unitCostMinor: costMinor,
          costCurrency: costMinor != null ? baseCurrency : null,
        },
        { onSuccess: onClose },
      )
    } else {
      const qty = Number.parseInt(initialQty, 10)
      create.mutate(
        {
          name: name.trim(),
          unit,
          unitCostMinor: costMinor,
          costCurrency: costMinor != null ? baseCurrency : null,
          initialStockQty: Number.isFinite(qty) && qty > 0 ? qty : 0,
        },
        { onSuccess: onClose },
      )
    }
  }

  return (
    <DialogShell
      title={ingredient ? t('inventory.editTitle') : t('inventory.addTitle')}
      onClose={onClose}
    >
      <div className="space-y-4">
        <Field label={t('inventory.nameLabel')} htmlFor="ing-name" error={nameError ?? undefined}>
          <TextInput
            id="ing-name"
            autoFocus
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (nameError) setNameError(null)
            }}
            placeholder={t('inventory.namePlaceholder')}
          />
        </Field>

        <Field label={t('inventory.unitLabel')} hint={t('inventory.unitHint')}>
          <Segmented
            fluid
            ariaLabel={t('inventory.unitLabel')}
            value={unit}
            onChange={setUnit}
            options={INGREDIENT_UNITS.map((u) => ({ value: u as string, label: u }))}
          />
        </Field>

        <Field
          label={t('inventory.costLabel', { currency: baseCurrency })}
          htmlFor="ing-cost"
          hint={t('inventory.costHint')}
        >
          <TextInput
            id="ing-cost"
            type="number"
            min="0"
            inputMode="numeric"
            value={costInput}
            onChange={(e) => setCostInput(e.target.value)}
            placeholder={t('inventory.costPlaceholder')}
          />
        </Field>

        {ingredient == null ? (
          <Field label={t('inventory.initialQtyLabel')} htmlFor="ing-initial">
            <TextInput
              id="ing-initial"
              type="number"
              min="0"
              step="1"
              inputMode="numeric"
              value={initialQty}
              onChange={(e) => setInitialQty(e.target.value)}
              placeholder="0"
            />
          </Field>
        ) : null}

        {mutationError ? (
          <p className="text-xs text-loss" role="alert">
            {isNameConflict(mutationError)
              ? t('inventory.nameTaken')
              : isIngredientInRecipe(mutationError)
                ? (mutationError instanceof ApiError && mutationError.problem?.detail) ||
                  t('recipe.ingredientInRecipeError')
                : t('inventory.errorGeneric')}
          </p>
        ) : null}

        <div className="flex gap-2">
          <Button variant="outline" className="flex-1" onClick={onClose} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" onClick={handleSubmit} disabled={busy}>
            {busy ? <Spinner /> : ingredient ? t('common.save') : t('inventory.addAction')}
          </Button>
        </div>

        {ingredient ? (
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              if (!confirmRemove) {
                setConfirmRemove(true)
                return
              }
              deactivate.mutate(ingredient.id, { onSuccess: onClose })
            }}
            className="w-full rounded-lg py-2 text-center text-xs font-semibold text-loss transition-colors hover:bg-tint-loss focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-loss"
          >
            {confirmRemove ? t('inventory.removeConfirm') : t('inventory.removeAction')}
          </button>
        ) : null}
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// Receive (+delta) and set-absolute dialogs
// ---------------------------------------------------------------------------

function ReceiveDialog({
  session,
  ingredient,
  locale,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const add = useAddIngredientStock(session)
  const [amountInput, setAmountInput] = useState('')
  const amount = Number.parseInt(amountInput, 10)
  const valid = Number.isFinite(amount) && amount !== 0

  return (
    <DialogShell title={t('inventory.receiveTitle', { name: ingredient.name })} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-ink-3">
          {t('inventory.receiveHint', {
            qty: formatQty(ingredient.stockQty, locale),
            unit: ingredient.unit,
          })}
        </p>
        <Field label={t('inventory.receiveAmountLabel', { unit: ingredient.unit })} htmlFor="ing-recv">
          <TextInput
            id="ing-recv"
            type="number"
            step="1"
            inputMode="numeric"
            autoFocus
            value={amountInput}
            onChange={(e) => setAmountInput(e.target.value)}
            placeholder="0"
          />
        </Field>
        {add.isError ? (
          <p className="text-xs text-loss" role="alert">
            {t('inventory.errorGeneric')}
          </p>
        ) : null}
        <Button
          className="w-full"
          disabled={!valid || add.isPending}
          onClick={() =>
            add.mutate({ id: ingredient.id, amount }, { onSuccess: onClose })
          }
        >
          {add.isPending ? <Spinner /> : t('inventory.receiveSubmit')}
        </Button>
      </div>
    </DialogShell>
  )
}

function SetQtyDialog({
  session,
  ingredient,
  locale,
  onClose,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const set = useSetIngredientStock(session)
  const [qtyInput, setQtyInput] = useState(String(ingredient.stockQty))
  const qty = Number.parseInt(qtyInput, 10)
  const valid = Number.isFinite(qty) && qty >= 0

  return (
    <DialogShell title={t('inventory.setTitle', { name: ingredient.name })} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-ink-3">
          {t('inventory.setHint', {
            qty: formatQty(ingredient.stockQty, locale),
            unit: ingredient.unit,
          })}
        </p>
        <Field label={t('inventory.setQtyLabel', { unit: ingredient.unit })} htmlFor="ing-setqty">
          <TextInput
            id="ing-setqty"
            type="number"
            min="0"
            step="1"
            inputMode="numeric"
            autoFocus
            value={qtyInput}
            onChange={(e) => setQtyInput(e.target.value)}
            placeholder="0"
          />
        </Field>
        {set.isError ? (
          <p className="text-xs text-loss" role="alert">
            {t('inventory.errorGeneric')}
          </p>
        ) : null}
        <Button
          className="w-full"
          disabled={!valid || set.isPending}
          onClick={() => set.mutate({ id: ingredient.id, quantity: qty }, { onSuccess: onClose })}
        >
          {set.isPending ? <Spinner /> : t('inventory.setSubmit')}
        </Button>
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// Minimal centered dialog shell (MenuManagement dialog idiom)
// ---------------------------------------------------------------------------

function DialogShell({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  return (
    // Phone: bottom-anchored sheet (the app's dialog idiom below sm); desktop stays centered.
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 backdrop-blur-sm sm:grid sm:place-items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-t-2xl border border-line bg-surface p-5 pb-[calc(1.25rem+var(--safe-area-inset-bottom,0px))] shadow-lg max-sm:max-w-full sm:rounded-card sm:pb-5">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-display text-lg font-semibold text-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
