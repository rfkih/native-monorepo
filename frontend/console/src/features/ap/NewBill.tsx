import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { Plus, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { useCreateBill, useVendors, type CreateBillLineBody } from './api'
import { SELECT_CLASSES } from './parts'

const TAX_RATE = 0.11

interface DraftLine {
  key: string
  description: string
  quantity: string
  unitPriceMajor: string
}

function newLine(): DraftLine {
  return { key: crypto.randomUUID(), description: '', quantity: '1', unitPriceMajor: '' }
}

/** A line is submittable once it has a description, a positive integer quantity, and a positive unit price. */
function parseLine(
  line: DraftLine,
  exponent: number,
): { body: CreateBillLineBody; lineTotalMinor: number } | null {
  const description = line.description.trim()
  const quantity = Number(line.quantity)
  const unitPriceMajor = Number(line.unitPriceMajor)
  if (!description) return null
  if (!Number.isInteger(quantity) || quantity <= 0) return null
  if (!Number.isFinite(unitPriceMajor) || unitPriceMajor <= 0) return null
  const unitPriceMinor = Math.round(unitPriceMajor * 10 ** exponent)
  if (unitPriceMinor <= 0) return null
  return {
    body: { description, quantity, unitPriceMinor },
    lineTotalMinor: quantity * unitPriceMinor,
  }
}

/**
 * New bill — pick a vendor, toggle tax, edit line items (add/remove rows), and see a
 * client-side live preview of subtotal/tax/total. The server recomputes and is authoritative;
 * this preview is a convenience only. Currency is always the company's base currency (rule: no
 * currency toggle in the dashboard).
 */
export function NewBill() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const navigate = useNavigate()
  const locale = localeOf(i18n.language)

  const [vendorId, setVendorId] = useState('')
  const [taxable, setTaxable] = useState(false)
  const [lines, setLines] = useState<DraftLine[]>([newLine()])

  const vendorsQuery = useVendors({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company,
  })
  const mutation = useCreateBill({ companyId: company?.companyId ?? '', actor: company?.actor ?? '' })

  if (!company) {
    return <EmptyState title={t('ap.bills.noCompany')} hint={t('ap.bills.noCompanyHint')} />
  }

  const currency = company.baseCurrency
  const exponent = isoMinorExponent(currency)
  // Zero-decimal currencies (e.g. IDR) only accept whole units; others step by their minor unit.
  const unitPriceStep = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()
  const vendors = vendorsQuery.data ?? []

  const parsedLines = lines.map((l) => ({ draft: l, parsed: parseLine(l, exponent) }))
  const validLineBodies = parsedLines
    .map((p) => p.parsed?.body)
    .filter((b): b is CreateBillLineBody => !!b)
  const subtotalMinor = parsedLines.reduce((sum, p) => sum + (p.parsed?.lineTotalMinor ?? 0), 0)
  const taxMinor = taxable ? Math.round(subtotalMinor * TAX_RATE) : 0
  const totalMinor = subtotalMinor + taxMinor

  const canSubmit = !!vendorId && validLineBodies.length > 0 && !mutation.isPending

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

          <div className="flex flex-col gap-3">
            {lines.map((line, idx) => {
              const parsed = parseLine(line, exponent)
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
