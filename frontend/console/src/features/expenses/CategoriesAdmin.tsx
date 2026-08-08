/**
 * `/expenses/categories` — the expense-category admin catalog (ADR 0030; endpoints since Phase E1,
 * console since Phase E7): list (ACTIVE only — the same set `GET /api/v1/expense-categories`
 * returns for the employee picker), create/edit dialog, deactivate, and a seed-defaults
 * empty-state action. Mirrors `features/ar/Customers.tsx`'s CRUD list shape.
 *
 * Deactivating a category removes it from this list too (the read endpoint returns ACTIVE only —
 * an E1 boundary, unchanged here); there is no reactivate path in v1 without a direct API call.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ChevronRight, Plus, Sparkles, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Badge } from '@/components/ui/Badge'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { useSession } from '@/lib/session'
import {
  GL_HINT_OPTIONS,
  useCategories,
  useCreateCategory,
  useSeedDefaultCategories,
  useUpdateCategory,
  type ExpenseCategory,
  type GlHint,
} from './api'

export function CategoriesAdmin() {
  const { t } = useTranslation()
  const { company } = useSession()
  const [dialog, setDialog] = useState<{ mode: 'create' } | { mode: 'edit'; category: ExpenseCategory } | null>(
    null,
  )

  const ready = !!company
  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''

  const categories = useCategories({ companyId, actor, enabled: ready })
  const seedDefaults = useSeedDefaultCategories({ companyId, actor })

  if (!company) {
    return (
      <EmptyState
        title={t('expenses.categories.noCompany')}
        hint={t('expenses.categories.noCompanyHint')}
      />
    )
  }

  const rows = categories.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <nav
        aria-label={t('expenses.categories.breadcrumbLabel')}
        className="flex items-center gap-1.5 text-sm"
      >
        <Link
          to="/expenses"
          className="font-medium text-ink-3 transition-colors hover:text-brand-700"
        >
          {t('nav.expenses')}
        </Link>
        <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
        <span className="font-semibold text-ink">{t('expenses.categories.title')}</span>
      </nav>

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('expenses.categories.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('expenses.categories.subtitle')}</p>
        </div>
        <Button type="button" onClick={() => setDialog({ mode: 'create' })}>
          <Plus className="size-4" />
          {t('expenses.categories.newCategory')}
        </Button>
      </div>

      {categories.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('expenses.categories.error')}
        </Card>
      ) : categories.isLoading ? (
        <ListSkeleton rows={5} />
      ) : rows.length === 0 ? (
        <Card className="p-10 text-center">
          <h2 className="font-display text-xl font-semibold text-ink">
            {t('expenses.categories.empty.title')}
          </h2>
          <p className="mx-auto mt-2 max-w-sm text-sm text-ink-3">
            {t('expenses.categories.empty.hint')}
          </p>
          <Button
            type="button"
            className="mx-auto mt-4"
            onClick={() => seedDefaults.mutate()}
            disabled={seedDefaults.isPending}
          >
            <Sparkles className="size-4" />
            {seedDefaults.isPending
              ? t('expenses.categories.empty.seeding')
              : t('expenses.categories.empty.seedDefaults')}
          </Button>
          {seedDefaults.isError ? (
            <p className="mt-2 text-sm text-loss">{t('expenses.categories.error')}</p>
          ) : null}
        </Card>
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('expenses.categories.colName')}</th>
                <th className="px-4 py-3">{t('expenses.categories.colGlHint')}</th>
                <th className="px-4 py-3">{t('expenses.categories.colTaxable')}</th>
                <th className="px-4 py-3 text-right">{t('expenses.categories.colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-semibold text-ink">{c.name}</td>
                  <td className="px-4 py-3 text-ink-2">
                    {c.glHint ? (
                      <Badge tone="neutral">{t(`expenses.categories.glHint.${c.glHint}`)}</Badge>
                    ) : (
                      <Badge tone="neutral">{t('expenses.categories.glHint.general')}</Badge>
                    )}
                  </td>
                  <td className="px-4 py-3 text-ink-2">
                    {c.taxable ? t('expenses.categories.yes') : t('expenses.categories.no')}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      type="button"
                      onClick={() => setDialog({ mode: 'edit', category: c })}
                      className="text-sm font-semibold text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-emerald"
                    >
                      {t('expenses.categories.edit')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {dialog ? (
        <CategoryDialog
          companyId={companyId}
          actor={actor}
          category={dialog.mode === 'edit' ? dialog.category : null}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

function CategoryDialog({
  companyId,
  actor,
  category,
  onClose,
}: {
  companyId: string
  actor: string
  /** `null` = create mode. */
  category: ExpenseCategory | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const isEditing = !!category

  const [name, setName] = useState(category?.name ?? '')
  const [glHint, setGlHint] = useState<GlHint>((category?.glHint as GlHint) ?? '')
  const [taxable, setTaxable] = useState(category?.taxable ?? false)
  const [active, setActive] = useState(category?.active ?? true)

  const createCategory = useCreateCategory({ companyId, actor })
  const updateCategory = useUpdateCategory({ companyId, actor })
  const saving = createCategory.isPending || updateCategory.isPending
  const saveError = createCategory.isError || updateCategory.isError

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    if (isEditing) {
      updateCategory.mutate(
        { id: category.id, name: name.trim(), glHint, taxable, active },
        { onSuccess: () => onClose() },
      )
    } else {
      createCategory.mutate(
        { name: name.trim(), glHint, taxable },
        { onSuccess: () => onClose() },
      )
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {isEditing ? t('expenses.categories.form.editTitle') : t('expenses.categories.form.newTitle')}
        </h2>

        <Field label={t('expenses.categories.form.name')} htmlFor="category-name">
          <TextInput
            id="category-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={128}
            required
            autoFocus
          />
        </Field>

        <Field
          label={t('expenses.categories.form.glHint')}
          htmlFor="category-glhint"
          hint={t('expenses.categories.form.glHintHint')}
        >
          <Select
            id="category-glhint"
            value={glHint}
            onChange={(e) => setGlHint(e.target.value as GlHint)}
          >
            {GL_HINT_OPTIONS.map((hint) => (
              <option key={hint} value={hint}>
                {t(`expenses.categories.glHint.${hint === '' ? 'general' : hint}`)}
              </option>
            ))}
          </Select>
        </Field>

        <label className="flex items-center gap-2.5 text-sm text-ink-2">
          <input
            type="checkbox"
            checked={taxable}
            onChange={(e) => setTaxable(e.target.checked)}
            className="size-4 rounded border-line text-emerald focus-visible:outline-2 focus-visible:outline-emerald"
          />
          {t('expenses.categories.form.taxable')}
        </label>

        {isEditing ? (
          <label className="flex items-center gap-2.5 text-sm text-ink-2">
            <input
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
              className="size-4 rounded border-line text-emerald focus-visible:outline-2 focus-visible:outline-emerald"
            />
            {t('expenses.categories.form.active')}
          </label>
        ) : null}

        {saveError ? <p className="text-sm text-loss">{t('expenses.categories.form.error')}</p> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={saving || !name.trim()}>
            {saving
              ? t('expenses.categories.form.saving')
              : isEditing
                ? t('expenses.categories.form.submitEdit')
                : t('expenses.categories.form.submitCreate')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
