/**
 * NewBillPhone — the phone "Tagihan baru" work form (ADR 0084), rendered by NewBill.tsx below the
 * 640px cutoff. It records an incoming vendor invoice and refuses to save one the books cannot
 * defend: the checklist above the save button names every remaining reason, one by one — a
 * duplicate invoice number for the same vendor, a line without a name or a price, a computed
 * total that differs from the figure printed on the paper, no evidence attached.
 *
 * Mostly white, on purpose: boxes only around what can be typed. Colour is used twice — green when
 * the sum matches the invoice, red when something blocks saving. Everything decidable lives in
 * `lib/newBillForm.ts` (pure, tested); this file is the form and the save sequence:
 * create DRAFT (all fields) → upload the staged attachment → POST with the term → open the bill.
 * A failed upload or post leaves the DRAFT with a retry — nothing is ever saved twice.
 *
 * Inside the Shell (its topbar is the one sticky header — ADR 0075 N2): the title row is in-flow
 * with a back arrow; the footer is sticky because the tab bar is not mounted on this route.
 */
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import {
  Calendar,
  Check,
  ChevronDown,
  CircleAlert,
  Dot,
  FileText,
  Plus,
  TriangleAlert,
  X,
} from 'lucide-react'
import { BackButton } from '@/components/mobile/BackButton'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { useIngredients, type Ingredient } from '@/features/inventory/ingredientApi'
import { IngredientPickerSheet } from '@/features/inventory/IngredientPickerSheet'
import { shownUnit } from '@/features/inventory/lib/units'
import { prepareAttachment } from '@/features/pos/lib/attachmentImage'
import { parseMajorPrice } from '@/features/menu/lib/menuView'
import {
  useApAging,
  useBills,
  useCreateBill,
  usePostBillById,
  useUploadBillAttachment,
  useVendors,
  type CreateBillBody,
  type Vendor,
} from './api'
import {
  TAX_BP_OPTIONS,
  TERM_OPTIONS,
  checklist,
  dueDateOf,
  effectiveTerms,
  isDuplicateInvoice,
  parseLine,
  reconcile,
  totals,
  vendorInitials,
  type DraftLine,
  type IngredientRef,
  type TaxBp,
} from './lib/newBillForm'

const SECTION = 'pl-0.5 text-2xs font-semibold uppercase tracking-eyebrow text-ink-3'
const FIELD_LABEL = 'block text-2xs font-semibold uppercase tracking-eyebrow text-ink-3'
const CARD = 'rounded-2xl border border-line bg-surface'
const CHIP = 'h-[30px] rounded-full border px-3 text-xs font-semibold transition-colors'
const CHIP_ON = 'border-emerald bg-emerald text-on-emerald'
const CHIP_OFF = 'border-line bg-surface text-ink-3 hover:bg-hover'
const CELL =
  'h-[34px] rounded-xl bg-hover px-2 text-right font-mono text-xs font-semibold text-ink tnum focus:outline-none'

/** Fixed bottom surfaces bypass the body's safe-area padding (index.css) — each pads itself. */
const SAFE_BOTTOM = (px: number) => ({
  paddingBottom: `calc(${px}px + var(--safe-area-inset-bottom, 0px))`,
})

/** Today as the LOCAL calendar day — `toISOString()` would be yesterday before 07:00 WIB. */
function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

function newLine(key: string): DraftLine {
  return { key, kind: 'expense', description: '', qty: '1', price: '' }
}

function fileSize(bytes: number, locale: string): string {
  const mb = bytes / (1024 * 1024)
  return mb >= 1
    ? `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(mb)} MB`
    : `${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(bytes / 1024)} KB`
}

type SaveError = { kind: 'create' | 'upload' | 'post'; detail: string | null }

export function NewBillPhone({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const navigate = useNavigate()
  const currency = company.baseCurrency
  const tenant = { companyId: company.companyId, actor: company.actor }

  const vendorsQuery = useVendors({ ...tenant, enabled: true })
  const agingQuery = useApAging({ ...tenant, enabled: true })
  const ingredientsQuery = useIngredients(company)
  const createBill = useCreateBill(tenant)
  const uploadAttachment = useUploadBillAttachment(tenant)
  const postBill = usePostBillById(tenant)

  // ---- form state ------------------------------------------------------------------------
  const [vendorId, setVendorId] = useState<string | null>(null)
  const [vendorOpen, setVendorOpen] = useState(false)
  const [invoiceNumber, setInvoiceNumber] = useState('')
  const [billDate, setBillDate] = useState(todayIso)
  const [termOverride, setTermOverride] = useState<number | null>(null)
  const [lines, setLines] = useState<DraftLine[]>([newLine('l1')])
  const [seq, setSeq] = useState(1)
  const [discount, setDiscount] = useState('')
  const [taxBp, setTaxBp] = useState<TaxBp>(1100)
  const [printed, setPrinted] = useState('')
  const [note, setNote] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [openMenu, setOpenMenu] = useState<{ line: string; field: 'account' } | null>(null)
  const [pickerFor, setPickerFor] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  // ---- the save sequence ------------------------------------------------------------------
  const [savedId, setSavedId] = useState<string | null>(null)
  const [uploaded, setUploaded] = useState(false)
  const [saveError, setSaveError] = useState<SaveError | null>(null)
  const [serverDuplicateOf, setServerDuplicateOf] = useState<string | null>(null)
  const saving = createBill.isPending || uploadAttachment.isPending || postBill.isPending

  // ---- derived ------------------------------------------------------------------------------
  const vendors = vendorsQuery.data ?? []
  const vendor = vendors.find((v) => v.id === vendorId) ?? null
  const outstandingByVendor = useMemo(
    () => new Map((agingQuery.data?.rows ?? []).map((r) => [r.vendorId, r.outstandingMinor])),
    [agingQuery.data],
  )
  const vendorBills = useBills({
    ...tenant,
    vendorId: vendorId ?? undefined,
    enabled: vendorId != null,
  })
  const duplicate =
    isDuplicateInvoice(vendorBills.data ?? [], vendorId, invoiceNumber) ||
    (serverDuplicateOf != null && serverDuplicateOf === invoiceNumber.trim().toLocaleLowerCase())
  const terms = effectiveTerms(vendor?.paymentTermDays, termOverride)
  const dueDate = dueDateOf(billDate, terms)
  const parsed = lines.map((l) => parseLine(l, currency))
  const validTotals = parsed.filter((p) => p.ok).map((p) => (p.ok ? p.totalMinor : 0))
  const discountMinor = parseMajorPrice(discount, currency) ?? 0
  const sum = totals(validTotals, discountMinor, taxBp)
  const printedMinor = parseMajorPrice(printed, currency)
  const recon = reconcile(sum.totalMinor, printedMinor)
  const issues = checklist({
    vendorId,
    invoiceNumber,
    duplicate,
    lines: parsed,
    reconciliation: recon,
    attached: file != null || uploaded,
  })
  // Once the draft exists the figures are the server's: the form locks and only the retry runs.
  const locked = savedId != null
  // The attachment control stays live after a FAILED upload so a refused file can be swapped.
  const attachLocked = locked && saveError?.kind !== 'upload'

  const fmtDate = (iso: string | null) => {
    if (!iso) return '—'
    const at = new Date(`${iso}T00:00:00Z`)
    if (Number.isNaN(at.getTime())) return '—'
    return new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(at)
  }
  const money = (minor: number) => formatMoney(minor, currency, locale)

  // ---- line edits -------------------------------------------------------------------------
  const patchLine = (key: string, patch: Partial<DraftLine>) =>
    setLines((ls) => ls.map((l) => (l.key === key ? ({ ...l, ...patch } as DraftLine) : l)))
  const setKind = (key: string, kind: DraftLine['kind']) =>
    setLines((ls) =>
      ls.map((l) => {
        if (l.key !== key || l.kind === kind) return l
        return kind === 'inventory'
          ? { key, kind, ingredient: null, description: '', qty: l.qty, price: l.price }
          : {
              key,
              kind,
              description: l.kind === 'inventory' ? (l.ingredient?.name ?? '') : '',
              qty: l.qty,
              price: l.price,
            }
      }),
    )
  const removeLine = (key: string) => setLines((ls) => ls.filter((l) => l.key !== key))
  const addLine = () => {
    setLines((ls) => [...ls, newLine(`l${seq + 1}`)])
    setSeq((n) => n + 1)
  }
  const pickIngredient = (key: string, ing: Ingredient) => {
    const ref: IngredientRef = {
      id: ing.id,
      name: ing.name,
      unit: ing.unit,
      displayUnit: ing.displayUnit,
    }
    setLines((ls) =>
      ls.map((l) => (l.key === key && l.kind === 'inventory' ? { ...l, ingredient: ref } : l)),
    )
    setPickerFor(null)
  }

  // ---- attachment ---------------------------------------------------------------------------
  const onPickFile = async (picked: File | null) => {
    setFileError(null)
    if (!picked) return
    try {
      setFile(await prepareAttachment(picked))
      // A new file after a refused one: not uploaded yet, the old failure cleared.
      setUploaded(false)
      if (saveError?.kind === 'upload') setSaveError(null)
    } catch {
      setFileError(t('ap.newBill.phone.attachError'))
    }
  }

  // ---- save -----------------------------------------------------------------------------------
  const save = async () => {
    if (saving || (savedId == null && issues.length > 0)) return
    setSaveError(null)
    let id = savedId
    try {
      if (id == null) {
        const body: CreateBillBody = {
          vendorId: vendorId!,
          currency,
          taxable: taxBp > 0,
          taxBp,
          lines: parsed.flatMap((p) => (p.ok ? [p.body] : [])),
          vendorInvoiceNumber: invoiceNumber.trim(),
          billDate,
          termDays: terms,
          discountMinor: sum.discountMinor,
          note: note.trim() === '' ? undefined : note.trim(),
        }
        const created = await createBill.mutateAsync(body)
        if (!created) throw new Error('empty create response')
        id = created.id
        setSavedId(id)
      }
      if (file && !uploaded) {
        try {
          await uploadAttachment.mutateAsync({ id, file })
          setUploaded(true)
        } catch (err) {
          setSaveError({ kind: 'upload', detail: problemDetail(err) })
          return
        }
      }
      try {
        await postBill.mutateAsync({ id, termDays: terms })
      } catch (err) {
        setSaveError({ kind: 'post', detail: problemDetail(err) })
        return
      }
      navigate(`/bills/${id}`, { replace: true })
    } catch (err) {
      if (
        err instanceof ApiError &&
        err.status === 409 &&
        err.problem?.type?.includes('bill-duplicate-invoice')
      ) {
        setServerDuplicateOf(invoiceNumber.trim().toLocaleLowerCase())
      }
      setSaveError({ kind: 'create', detail: problemDetail(err) })
    }
  }

  const vendorMeta = (v: Vendor) => {
    const out = outstandingByVendor.get(v.id)
    return [
      v.taxId ? t('ap.newBill.phone.npwp', { id: v.taxId }) : t('ap.newBill.phone.noNpwp'),
      v.paymentTermDays != null
        ? v.paymentTermDays === 0
          ? t('ap.newBill.phone.termsCash')
          : t('ap.newBill.phone.termsDays', { days: v.paymentTermDays })
        : null,
      out != null && out > 0
        ? t('ap.newBill.phone.outstanding', { amount: money(out) })
        : t('ap.newBill.phone.noOutstanding'),
    ]
      .filter(Boolean)
      .join(' · ')
  }

  const issueText = (issue: (typeof issues)[number]) => {
    switch (issue.key) {
      case 'unnamed':
        return t('ap.newBill.phone.issue.unnamed', { count: issue.count })
      case 'unpriced':
        return t('ap.newBill.phone.issue.unpriced', { count: issue.count })
      case 'mismatch':
        return t('ap.newBill.phone.issue.mismatch', { amount: money(issue.diffMinor) })
      default:
        return t(`ap.newBill.phone.issue.${issue.key}`)
    }
  }

  return (
    <div className="-mx-5 -my-7 flex min-h-[100dvh] flex-col bg-surface">
      {/* Title row — in-flow; the Shell's topbar is the one sticky header (ADR 0075 N2). */}
      <div className="flex h-14 items-center gap-2 border-b border-line px-2">
        <BackButton
          fallback="/bills"
          className="grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 hover:bg-hover"
        />
        <span className="min-w-0 flex-1 truncate font-display text-xl font-bold tracking-display text-ink">
          {t('ap.newBill.title')}
        </span>
        <span className="mr-2 grid h-6 shrink-0 place-items-center rounded-full border border-line px-2.5 text-2xs font-semibold text-ink-3">
          {t('ap.newBill.phone.draft')}
        </span>
      </div>

      <fieldset disabled={locked} className="m-0 min-w-0 border-0 p-0 px-4">
        {/* VENDOR */}
        <section className="pt-5">
          <div className={SECTION}>{t('ap.newBill.phone.vendorSection')}</div>
          <button
            type="button"
            onClick={() => setVendorOpen((o) => !o)}
            aria-expanded={vendorOpen}
            className={cn(
              CARD,
              'mt-2 flex min-h-[70px] w-full items-center gap-3 px-3.5 py-[13px] text-left hover:bg-paper',
            )}
          >
            <span className="grid size-[42px] shrink-0 place-items-center rounded-xl bg-hover text-sm font-bold text-ink-2">
              {vendor ? vendorInitials(vendor.name) : '—'}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-base font-semibold leading-snug tracking-display text-ink">
                {vendor ? vendor.name : t('ap.newBill.phone.pickVendor')}
              </span>
              <span className="mt-1 block truncate text-xs text-ink-3">
                {vendor ? vendorMeta(vendor) : t('ap.newBill.phone.vendorNotChosen')}
              </span>
            </span>
            <ChevronDown
              className={cn(
                'size-4 shrink-0 text-ink-300 transition-transform',
                vendorOpen && 'rotate-180',
              )}
              aria-hidden="true"
            />
          </button>
          {vendorOpen ? (
            <div className={cn(CARD, 'panel-in mt-2 overflow-hidden')}>
              {vendors
                .filter((v) => v.active)
                .map((v) => (
                  <button
                    key={v.id}
                    type="button"
                    onClick={() => {
                      setVendorId(v.id)
                      setTermOverride(null)
                      setVendorOpen(false)
                      setServerDuplicateOf(null)
                    }}
                    className={cn(
                      'flex min-h-[58px] w-full items-center gap-3 border-b border-line/60 px-3.5 py-[11px] text-left last:border-b-0 hover:bg-paper',
                      v.id === vendorId && 'bg-paper',
                    )}
                  >
                    <span className="min-w-0 flex-1">
                      <span
                        className={cn(
                          'block truncate text-sm text-ink',
                          v.id === vendorId ? 'font-bold' : 'font-semibold',
                        )}
                      >
                        {v.name}
                      </span>
                      <span className="mt-0.5 block truncate text-xs text-ink-3">
                        {v.taxId
                          ? t('ap.newBill.phone.npwp', { id: v.taxId })
                          : t('ap.newBill.phone.noNpwp')}
                      </span>
                    </span>
                    <span className="tnum shrink-0 font-mono text-xs text-ink-3">
                      {(outstandingByVendor.get(v.id) ?? 0) > 0
                        ? money(outstandingByVendor.get(v.id)!)
                        : '—'}
                    </span>
                  </button>
                ))}
              <Link
                to="/vendors"
                viewTransition
                className="flex min-h-[46px] items-center gap-2 px-3.5 text-xs font-semibold text-ink hover:bg-paper"
              >
                <Plus className="size-[15px]" strokeWidth={2.2} aria-hidden="true" />
                {t('ap.newBill.phone.addVendor')}
              </Link>
            </div>
          ) : null}
        </section>

        {/* DETAIL FAKTUR */}
        <section className="pt-[22px]">
          <div className={SECTION}>{t('ap.newBill.phone.invoiceSection')}</div>
          <label
            className={cn(
              'mt-2 block rounded-2xl border bg-surface px-3 py-2.5 transition-colors',
              duplicate ? 'border-loss-line' : 'border-line',
            )}
          >
            <span className={FIELD_LABEL}>{t('ap.newBill.phone.invoiceNumber')}</span>
            <input
              type="text"
              value={invoiceNumber}
              onChange={(e) => setInvoiceNumber(e.target.value)}
              placeholder={t('ap.newBill.phone.invoiceNumberPlaceholder')}
              maxLength={64}
              autoCapitalize="characters"
              className="mt-1.5 w-full bg-transparent font-mono text-base font-semibold leading-tight text-ink placeholder:text-ink-400 focus:outline-none"
            />
          </label>
          {duplicate && vendor ? (
            <div className="panel-in mt-[7px] flex items-start gap-[7px] px-[3px]" role="alert">
              <CircleAlert
                className="mt-px size-[14px] shrink-0 text-loss-ink"
                strokeWidth={2.2}
                aria-hidden="true"
              />
              <span className="text-xs font-medium leading-[1.4] text-loss-ink">
                {t('ap.newBill.phone.duplicate', { vendor: vendor.name })}
              </span>
            </div>
          ) : null}

          <div className="mt-[9px] flex gap-[9px]">
            <label className="relative block min-w-0 flex-1 cursor-pointer rounded-2xl border border-line bg-surface px-3 py-2.5">
              <span className={FIELD_LABEL}>{t('ap.newBill.phone.invoiceDate')}</span>
              <span className="mt-1.5 flex items-center gap-1.5">
                <span className="min-w-0 flex-1 truncate font-mono text-sm font-semibold text-ink">
                  {fmtDate(billDate)}
                </span>
                <Calendar
                  className="size-[14px] shrink-0 text-ink-400"
                  strokeWidth={1.9}
                  aria-hidden="true"
                />
              </span>
              <input
                type="date"
                value={billDate}
                max={todayIso()}
                onChange={(e) => e.target.value && setBillDate(e.target.value)}
                aria-label={t('ap.newBill.phone.invoiceDate')}
                className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
              />
            </label>
            <div className="min-w-0 flex-1 rounded-2xl bg-hover px-3 py-2.5">
              <span className={FIELD_LABEL}>{t('ap.newBill.phone.dueDate')}</span>
              <span className="mt-1.5 block truncate font-mono text-sm font-semibold text-ink">
                {fmtDate(dueDate)}
              </span>
            </div>
          </div>

          <div className="mt-[11px] flex flex-wrap items-center gap-2 px-[3px]">
            <span className="text-xs font-medium text-ink-3">
              {t('ap.newBill.phone.termsLabel')}
            </span>
            {TERM_OPTIONS.map((d) => (
              <button
                key={d}
                type="button"
                aria-pressed={d === terms}
                onClick={() => setTermOverride(d)}
                className={cn(CHIP, d === terms ? CHIP_ON : CHIP_OFF)}
              >
                {d === 0
                  ? t('ap.newBill.phone.termCash')
                  : t('ap.newBill.phone.termDays', { days: d })}
              </button>
            ))}
          </div>
          <p className="mt-2 px-[3px] text-xs leading-[1.4] text-ink-3">
            {terms === 0
              ? t('ap.newBill.phone.dueHintCash')
              : t('ap.newBill.phone.dueHint', { days: terms, date: fmtDate(dueDate) })}
          </p>
        </section>

        {/* BARIS ITEM */}
        <section className="pt-[22px]">
          <div className="flex items-baseline justify-between gap-2.5 px-0.5">
            <span className={SECTION}>{t('ap.newBill.phone.linesSection')}</span>
            <span className="tnum font-mono text-2xs font-medium text-ink-3">
              {t('ap.newBill.phone.lineCount', {
                count: lines.length,
                subtotal: money(sum.subtotalMinor),
              })}
            </span>
          </div>
          <div className="mt-2 flex flex-col gap-[9px]">
            {lines.map((line, i) => {
              const p = parsed[i]
              const ing = line.kind === 'inventory' ? line.ingredient : null
              const unit = ing ? shownUnit(ing) : null
              return (
                <div
                  key={line.key}
                  className={cn(
                    CARD,
                    'relative px-3 pb-[11px] pt-3',
                    openMenu?.line === line.key && 'z-20',
                  )}
                >
                  <div className="flex items-center gap-1.5">
                    {line.kind === 'inventory' ? (
                      <button
                        type="button"
                        onClick={() => setPickerFor(line.key)}
                        className={cn(
                          'min-w-0 flex-1 truncate text-left text-sm font-semibold leading-snug tracking-display',
                          ing ? 'text-ink' : 'text-ink-400',
                        )}
                      >
                        {ing ? ing.name : t('ap.newBill.phone.pickIngredient')}
                      </button>
                    ) : (
                      <input
                        type="text"
                        value={line.description}
                        onChange={(e) => patchLine(line.key, { description: e.target.value })}
                        placeholder={t('ap.newBill.phone.linePlaceholder')}
                        className="min-w-0 flex-1 bg-transparent text-sm font-semibold leading-snug tracking-display text-ink placeholder:text-ink-400 focus:outline-none"
                      />
                    )}
                    <button
                      type="button"
                      onClick={() => removeLine(line.key)}
                      aria-label={t('ap.newBill.phone.removeLine')}
                      className="-mr-1.5 grid size-[30px] shrink-0 place-items-center rounded-full text-ink-400 hover:bg-hover hover:text-ink"
                    >
                      <X className="size-[14px]" strokeWidth={2.2} aria-hidden="true" />
                    </button>
                  </div>
                  {line.kind === 'inventory' && ing ? (
                    <input
                      type="text"
                      value={line.description}
                      onChange={(e) => patchLine(line.key, { description: e.target.value })}
                      placeholder={t('ap.newBill.phone.receiptNamePlaceholder')}
                      className="mt-1 w-full bg-transparent text-xs text-ink-2 placeholder:text-ink-400 focus:outline-none"
                    />
                  ) : null}
                  <div className="mt-2.5 flex items-center gap-[7px]">
                    <input
                      type="text"
                      inputMode="decimal"
                      value={line.qty}
                      onChange={(e) => patchLine(line.key, { qty: e.target.value })}
                      placeholder={t('ap.newBill.phone.qtyPlaceholder')}
                      aria-label={t('ap.newBill.quantityLabel')}
                      className={cn(CELL, 'w-[52px] shrink-0')}
                    />
                    {unit ? (
                      <span className="grid h-[34px] shrink-0 place-items-center rounded-xl bg-hover px-2.5 text-xs font-medium text-ink-2">
                        {unit}
                      </span>
                    ) : null}
                    <span className="shrink-0 text-xs font-medium text-ink-400">×</span>
                    <span className="flex h-[34px] min-w-0 flex-1 items-center gap-1 rounded-xl bg-hover px-2.5">
                      <span className="shrink-0 font-mono text-2xs font-medium text-ink-3">
                        {currencySymbol(currency, locale)}
                      </span>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={line.price}
                        onChange={(e) => patchLine(line.key, { price: e.target.value })}
                        placeholder="0"
                        aria-label={t('ap.newBill.unitPriceLabel')}
                        className="tnum min-w-0 flex-1 bg-transparent text-right font-mono text-xs font-semibold text-ink placeholder:text-ink-400 focus:outline-none"
                      />
                    </span>
                  </div>
                  <div className="mt-2.5 flex items-center gap-2.5 border-t border-line/60 pt-[9px]">
                    <span className="relative min-w-0 flex-1">
                      <button
                        type="button"
                        aria-label={t('ap.newBill.phone.accountLabel')}
                        aria-expanded={openMenu?.line === line.key}
                        onClick={() =>
                          setOpenMenu((m) =>
                            m?.line === line.key ? null : { line: line.key, field: 'account' },
                          )
                        }
                        className={cn(
                          '-ml-[7px] flex h-7 max-w-full items-center gap-1.5 rounded-lg px-[7px] text-xs font-medium text-ink-3 hover:bg-hover hover:text-ink',
                          openMenu?.line === line.key && 'bg-hover',
                        )}
                      >
                        <span className="min-w-0 truncate">
                          {line.kind === 'inventory'
                            ? t('ap.newBill.phone.accountInventory')
                            : t('ap.newBill.phone.accountExpense')}
                        </span>
                        <ChevronDown
                          className={cn(
                            'size-3 shrink-0 opacity-70 transition-transform',
                            openMenu?.line === line.key && 'rotate-180',
                          )}
                          strokeWidth={2.4}
                          aria-hidden="true"
                        />
                      </button>
                      {openMenu?.line === line.key ? (
                        <>
                          <span
                            className="fixed inset-0 z-10 block"
                            onClick={() => setOpenMenu(null)}
                            aria-hidden="true"
                          />
                          <span
                            className="absolute bottom-[calc(100%+6px)] -left-[7px] z-20 block w-[246px] overflow-hidden rounded-xl border border-line bg-surface shadow-lg"
                            style={{ transformOrigin: 'bottom left' }}
                          >
                            <span className="block px-3 pb-[7px] pt-[11px] text-2xs font-semibold uppercase tracking-eyebrow text-ink-3">
                              {t('ap.newBill.phone.accountLabel')}
                            </span>
                            {(['expense', 'inventory'] as const).map((kind) => {
                              const on = line.kind === kind
                              const [code, name] = (
                                kind === 'expense'
                                  ? t('ap.newBill.phone.accountExpense')
                                  : t('ap.newBill.phone.accountInventory')
                              ).split(' · ')
                              return (
                                <button
                                  key={kind}
                                  type="button"
                                  onClick={() => {
                                    setKind(line.key, kind)
                                    setOpenMenu(null)
                                    if (kind === 'inventory') setPickerFor(line.key)
                                  }}
                                  className={cn(
                                    'flex min-h-[46px] w-full items-center gap-[9px] border-t border-line/60 px-3 text-left hover:bg-hover',
                                    on && 'bg-paper',
                                  )}
                                >
                                  <span className="w-[42px] shrink-0 font-mono text-2xs font-semibold text-ink-3">
                                    {code}
                                  </span>
                                  <span
                                    className={cn(
                                      'min-w-0 flex-1 truncate text-xs text-ink',
                                      on ? 'font-bold' : 'font-medium',
                                    )}
                                  >
                                    {name}
                                  </span>
                                  {on ? (
                                    <Check
                                      className="size-[14px] shrink-0"
                                      strokeWidth={2.4}
                                      aria-hidden="true"
                                    />
                                  ) : null}
                                </button>
                              )
                            })}
                          </span>
                        </>
                      ) : null}
                    </span>
                    <span
                      className={cn(
                        'tnum shrink-0 font-mono text-sm font-bold',
                        p.ok ? 'text-ink' : 'text-ink-400',
                      )}
                    >
                      {p.ok ? money(p.totalMinor) : money(0)}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
          <button
            type="button"
            onClick={addLine}
            className="mt-[9px] flex min-h-[46px] w-full items-center justify-center gap-2 rounded-2xl border border-dashed border-line-strong bg-surface text-xs font-semibold text-ink hover:bg-paper"
          >
            <Plus className="size-[15px]" strokeWidth={2.2} aria-hidden="true" />
            {t('ap.newBill.phone.addLine')}
          </button>
        </section>

        {/* RINGKASAN */}
        <section className="pt-[22px]">
          <div className={SECTION}>{t('ap.newBill.phone.summary')}</div>
          <div className={cn(CARD, 'mt-2 px-[15px] pb-[13px] pt-[15px]')}>
            <Row label={t('ap.newBill.subtotal')} value={money(sum.subtotalMinor)} />
            <div className="mt-1 flex min-h-[30px] items-center gap-2.5">
              <span className="flex-1 text-xs text-ink-2">
                {t('ap.newBill.phone.discount')}
              </span>
              <span className="flex h-[30px] shrink-0 items-center gap-1 rounded-lg bg-hover px-2.5">
                <span className="font-mono text-2xs font-medium text-ink-3">
                  {currencySymbol(currency, locale)}
                </span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={discount}
                  onChange={(e) => setDiscount(e.target.value)}
                  placeholder="0"
                  aria-label={t('ap.newBill.phone.discount')}
                  className="tnum w-[76px] bg-transparent text-right font-mono text-xs font-medium text-ink placeholder:text-ink-400 focus:outline-none"
                />
              </span>
            </div>
            <div className="mt-1 border-t border-line/60 pt-[9px]">
              <Row label={t('ap.newBill.phone.dpp')} value={money(sum.dppMinor)} />
            </div>
            <div className="mt-1 flex min-h-[32px] items-center gap-2.5">
              <span className="flex min-w-0 flex-1 items-center gap-[7px]">
                <span className="text-xs text-ink-2">{t('ap.newBill.phone.vat')}</span>
                {TAX_BP_OPTIONS.map((bp) => (
                  <button
                    key={bp}
                    type="button"
                    aria-pressed={bp === taxBp}
                    onClick={() => setTaxBp(bp)}
                    className={cn(
                      'h-6 rounded-full border px-2.5 font-mono text-2xs font-semibold transition-colors',
                      bp === taxBp ? CHIP_ON : CHIP_OFF,
                    )}
                  >
                    {new Intl.NumberFormat(locale, {
                      style: 'percent',
                      maximumFractionDigits: 0,
                    }).format(bp / 10_000)}
                  </button>
                ))}
              </span>
              <span className="tnum shrink-0 font-mono text-xs font-medium text-ink">
                {money(sum.taxMinor)}
              </span>
            </div>
            <div className="mt-[9px] flex items-baseline gap-2.5 border-t border-line pt-[11px]">
              <span className="flex-1 text-sm font-bold tracking-display text-ink">
                {t('ap.newBill.phone.total')}
              </span>
              <span className="tnum shrink-0 font-mono text-xl font-extrabold tracking-display text-ink">
                {money(sum.totalMinor)}
              </span>
            </div>
          </div>
        </section>

        {/* REKONSILIASI */}
        <section className="pt-3.5">
          <div
            className={cn(
              'rounded-2xl border px-[15px] pb-[13px] pt-3.5 transition-colors',
              recon.state === 'match'
                ? 'border-profit-line bg-tint-profit'
                : recon.state === 'empty'
                  ? 'border-line bg-paper'
                  : 'border-loss-line bg-tint-loss',
            )}
          >
            <div className="flex min-h-[32px] items-center gap-2.5">
              <span className="min-w-0 flex-1 text-xs font-semibold leading-snug text-ink">
                {t('ap.newBill.phone.printedTitle')}
              </span>
              <span className="flex h-8 shrink-0 items-center gap-1 rounded-xl border border-line bg-surface px-2.5">
                <span className="font-mono text-2xs font-medium text-ink-3">
                  {currencySymbol(currency, locale)}
                </span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={printed}
                  onChange={(e) => setPrinted(e.target.value)}
                  placeholder="0"
                  aria-label={t('ap.newBill.phone.printedTitle')}
                  className="tnum w-[86px] bg-transparent text-right font-mono text-sm font-semibold text-ink placeholder:text-ink-400 focus:outline-none"
                />
              </span>
            </div>
            <div
              className={cn(
                'mt-2.5 flex items-center gap-2 border-t pt-2.5 text-xs font-medium leading-[1.4]',
                recon.state === 'match'
                  ? 'border-profit-line/60 text-profit-ink'
                  : recon.state === 'empty'
                    ? 'border-line text-ink-3'
                    : 'border-loss-line/60 text-loss-ink',
              )}
              aria-live="polite"
            >
              {recon.state === 'match' ? (
                <Check className="size-[15px] shrink-0" strokeWidth={2.4} aria-hidden="true" />
              ) : recon.state === 'empty' ? (
                <Dot className="size-[15px] shrink-0" strokeWidth={2.4} aria-hidden="true" />
              ) : (
                <TriangleAlert
                  className="size-[15px] shrink-0"
                  strokeWidth={2.2}
                  aria-hidden="true"
                />
              )}
              <span className="min-w-0 flex-1">
                {recon.state === 'match'
                  ? t('ap.newBill.phone.reconMatch')
                  : recon.state === 'empty'
                    ? t('ap.newBill.phone.reconEmpty')
                    : recon.state === 'higher'
                      ? t('ap.newBill.phone.reconHigher', { amount: money(recon.diffMinor) })
                      : t('ap.newBill.phone.reconLower', { amount: money(recon.diffMinor) })}
              </span>
            </div>
          </div>
        </section>
      </fieldset>

      {/* BUKTI DAN CATATAN — outside the lock: a refused attachment must be replaceable. */}
      <div className="px-4 pb-[132px]">
        <section className="pt-[22px]">
          <div className={SECTION}>{t('ap.newBill.phone.evidenceSection')}</div>
          <input
            ref={fileInput}
            type="file"
            accept="image/*,application/pdf"
            className="hidden"
            onChange={(e) => {
              void onPickFile(e.target.files?.[0] ?? null)
              e.target.value = ''
            }}
          />
          {/* No `capture` on the input: with it Android opens the camera directly and a PDF can
              never be picked; the OS chooser still offers the camera. */}
          <button
            type="button"
            disabled={attachLocked}
            onClick={() => (file ? setFile(null) : fileInput.current?.click())}
            className={cn(
              CARD,
              'mt-2 flex min-h-[60px] w-full items-center gap-[11px] px-3.5 py-3 text-left hover:bg-paper disabled:opacity-60',
            )}
          >
            <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-hover text-ink-2">
              <FileText className="size-[17px]" strokeWidth={1.9} aria-hidden="true" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-semibold leading-snug text-ink">
                {file ? file.name : t('ap.newBill.phone.attach')}
              </span>
              <span className="mt-0.5 block truncate text-xs text-ink-3">
                {file
                  ? t('ap.newBill.phone.attachRemove', { size: fileSize(file.size, locale) })
                  : t('ap.newBill.phone.attachHint')}
              </span>
            </span>
          </button>
          {fileError ? (
            <p className="mt-1.5 px-[3px] text-xs text-loss-ink" role="alert">
              {fileError}
            </p>
          ) : null}
          <label className={cn(CARD, 'mt-[9px] block px-3 py-[11px]')}>
            <span className={FIELD_LABEL}>{t('ap.newBill.phone.noteLabel')}</span>
            <textarea
              value={note}
              disabled={locked}
              onChange={(e) => setNote(e.target.value)}
              rows={2}
              maxLength={1000}
              placeholder={t('ap.newBill.phone.notePlaceholder')}
              className="mt-[7px] w-full resize-none bg-transparent text-xs leading-[1.5] text-ink placeholder:text-ink-400 focus:outline-none"
            />
          </label>
        </section>

        {/* DAFTAR PERIKSA */}
        <section className="pt-[22px]">
          <div className={SECTION}>
            {issues.length > 0
              ? t('ap.newBill.phone.checklistTitle', { count: issues.length })
              : t('ap.newBill.phone.ready')}
          </div>
          <div className={cn(CARD, 'mt-2 overflow-hidden')}>
            {issues.length === 0 ? (
              <div className="flex min-h-[44px] items-center gap-2.5 px-3.5 py-[9px]">
                <Check
                  className="size-[15px] shrink-0 text-profit-ink"
                  strokeWidth={2.4}
                  aria-hidden="true"
                />
                <span className="min-w-0 flex-1 text-xs font-semibold leading-[1.4] text-profit-ink">
                  {t('ap.newBill.phone.allDone')}
                </span>
              </div>
            ) : (
              issues.map((issue) => (
                <div
                  key={issue.key}
                  className="flex min-h-[44px] items-center gap-2.5 border-b border-line/60 px-3.5 py-[9px] last:border-b-0"
                >
                  <Dot
                    className="size-[15px] shrink-0 text-ink-3"
                    strokeWidth={2.4}
                    aria-hidden="true"
                  />
                  <span className="min-w-0 flex-1 text-xs font-medium leading-[1.4] text-ink-3">
                    {issueText(issue)}
                  </span>
                </div>
              ))
            )}
          </div>
          {saveError ? (
            <div
              className="mt-2.5 flex items-start gap-2 rounded-2xl border border-loss-line bg-tint-loss px-3.5 py-3 text-xs leading-[1.45] text-loss-ink"
              role="alert"
            >
              <CircleAlert className="mt-px size-4 shrink-0" strokeWidth={2} aria-hidden="true" />
              <span className="min-w-0 flex-1">
                {saveError.kind === 'upload'
                  ? t('ap.newBill.phone.uploadFailed')
                  : saveError.kind === 'post'
                    ? t('ap.newBill.phone.postFailed')
                    : (saveError.detail ?? t('ap.newBill.phone.failed'))}
                {(saveError.kind === 'post' || saveError.kind === 'upload') && savedId ? (
                  <>
                    {' '}
                    <Link
                      to={`/bills/${savedId}`}
                      className="font-bold underline underline-offset-2"
                    >
                      {t('ap.newBill.phone.openDraft')}
                    </Link>
                  </>
                ) : null}
              </span>
            </div>
          ) : null}
        </section>
      </div>

      {/* FOOTER — sticky; the tab bar does not mount on this route. */}
      <div
        className="sticky bottom-0 z-10 flex items-center gap-3 border-t border-line bg-surface px-4 pt-3"
        style={SAFE_BOTTOM(30)}
      >
        <div className="min-w-0 flex-1">
          <div className="text-2xs font-medium uppercase tracking-eyebrow text-ink-3">
            {t('ap.newBill.phone.footerTotal')}
          </div>
          <div className="tnum mt-1 truncate font-mono text-lg font-extrabold tracking-display text-ink">
            {money(sum.totalMinor)}
          </div>
        </div>
        <button
          type="button"
          disabled={issues.length > 0 || saving}
          onClick={() => void save()}
          className={cn(
            'h-[50px] shrink-0 rounded-2xl px-[22px] text-sm font-bold tracking-display transition-[transform,background-color] duration-150 active:scale-[0.97] motion-reduce:active:scale-100',
            issues.length > 0 || saving
              ? 'bg-ink-100 text-ink-400'
              : 'bg-emerald text-on-emerald shadow-lift',
          )}
        >
          {saving ? (
            <Spinner />
          ) : saveError?.kind === 'upload' ? (
            t('ap.newBill.phone.retryUpload')
          ) : (
            t('ap.newBill.phone.save')
          )}
        </button>
      </div>

      {pickerFor ? (
        <IngredientPickerSheet
          ingredients={ingredientsQuery.data ?? []}
          locale={locale}
          onPick={(ing) => pickIngredient(pickerFor, ing)}
          onClose={() => setPickerFor(null)}
        />
      ) : null}
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-h-[26px] items-center gap-2.5">
      <span className="flex-1 text-xs text-ink-2">{label}</span>
      <span className="tnum shrink-0 font-mono text-xs font-medium text-ink">{value}</span>
    </div>
  )
}

function currencySymbol(currency: string, locale: string): string {
  try {
    return (
      new Intl.NumberFormat(locale, { style: 'currency', currency })
        .formatToParts(0)
        .find((p) => p.type === 'currency')?.value ?? currency
    )
  } catch {
    return currency
  }
}

function problemDetail(err: unknown): string | null {
  return err instanceof ApiError ? (err.problem?.detail ?? null) : null
}
