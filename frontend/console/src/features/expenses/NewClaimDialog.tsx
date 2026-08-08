/**
 * New / edit-draft expense claim dialog (ADR 0030, Phase E6) — category, amount (major-unit input
 * converted to minor units via `./amount`, mirroring features/ar/NewInvoice's
 * `Math.round(major * 10 ** exponent)` idiom), expense date, merchant/note, a reimbursement-method
 * toggle (PAYROLL default), and a receipt photo picker with a client-side pre-flight check. The
 * photo uploads AFTER the claim is created/saved (while it is still DRAFT) — the server rejects a
 * receipt upload against anything else — so a picked photo is staged locally and sent as a second
 * request once the create/update succeeds.
 *
 * `claim === null` is create mode; a non-null `claim` (the FULL detail, not the list-row summary —
 * only the detail carries `categoryId`/`note`) is edit-draft mode, reusing the same form.
 */

import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { Spinner } from '@/components/ui/Spinner'
import { Skeleton } from '@/components/ui/Skeleton'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import { isoMinorExponent } from '@/lib/money'
import {
  minorToAmountInputValue,
  parseAmountInputToMinor,
  validateReceiptFile,
  type ReceiptFileRejection,
} from './amount'
import {
  useCreateClaim,
  useMyCategories,
  useMyReceiptUrl,
  useUpdateDraft,
  useUploadReceipt,
  type ClaimBody,
  type ExpenseClaimDetail,
  type ReimbursementMethod,
} from './api'

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

export function NewClaimDialog({
  companyId,
  actor,
  currency,
  claim,
  onClose,
}: {
  companyId: string
  actor: string
  currency: string
  /** The FULL detail of the DRAFT being edited, or `null` to create a new claim. */
  claim: ExpenseClaimDetail | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const isEditing = !!claim
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [categoryId, setCategoryId] = useState(claim?.categoryId ?? '')
  const [amountInput, setAmountInput] = useState(
    claim ? minorToAmountInputValue(claim.amountMinor, claim.currency) : '',
  )
  const [expenseDate, setExpenseDate] = useState(claim?.expenseDate ?? todayIso())
  const [merchant, setMerchant] = useState(claim?.merchant ?? '')
  const [note, setNote] = useState(claim?.note ?? '')
  const [method, setMethod] = useState<ReimbursementMethod>(claim?.reimbursementMethod ?? 'PAYROLL')
  const [pickedFile, setPickedFile] = useState<File | null>(null)
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(null)
  const [fileError, setFileError] = useState<ReceiptFileRejection | null>(null)
  // The claim id once THIS dialog session's create/update has succeeded — lets a failed receipt
  // upload be retried without re-creating/re-saving the claim itself.
  const [savedId, setSavedId] = useState<string | null>(null)

  const categories = useMyCategories({ companyId, actor, enabled: true })
  const createClaim = useCreateClaim({ companyId, actor })
  const updateDraft = useUpdateDraft({ companyId, actor, id: claim?.id ?? '' })
  const uploadReceipt = useUploadReceipt({ companyId, actor })
  // The existing receipt (edit mode only), shown until the user picks a replacement.
  const existingReceipt = useMyReceiptUrl({
    companyId,
    actor,
    id: claim?.id ?? null,
    enabled: isEditing && !pickedFile,
  })

  // Revoke the local preview object URL on replace/unmount — it is never cached, only ever shown
  // by this dialog instance.
  useEffect(() => {
    return () => {
      if (localPreviewUrl) URL.revokeObjectURL(localPreviewUrl)
    }
  }, [localPreviewUrl])

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null
    if (!file) return
    const result = validateReceiptFile(file)
    if (!result.ok) {
      setFileError(result.reason)
      if (fileInputRef.current) fileInputRef.current.value = ''
      return
    }
    setFileError(null)
    if (localPreviewUrl) URL.revokeObjectURL(localPreviewUrl)
    setPickedFile(file)
    setLocalPreviewUrl(URL.createObjectURL(file))
  }

  function clearPickedFile() {
    if (localPreviewUrl) URL.revokeObjectURL(localPreviewUrl)
    setPickedFile(null)
    setLocalPreviewUrl(null)
    setFileError(null)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const exponent = isoMinorExponent(currency)
  const amountStep = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()
  const amountMinor = parseAmountInputToMinor(amountInput, currency)

  const saving = createClaim.isPending || updateDraft.isPending
  const uploading = uploadReceipt.isPending
  const saveError = createClaim.isError || updateDraft.isError
  const uploadError = uploadReceipt.isError

  const canSubmit =
    !!categoryId && amountMinor != null && !!expenseDate && !fileError && !saving && !uploading

  function retryUpload(id: string) {
    if (!pickedFile) {
      onClose()
      return
    }
    uploadReceipt.mutate({ id, file: pickedFile }, { onSuccess: () => onClose() })
  }

  function afterSave(saved: ExpenseClaimDetail | null) {
    if (!saved) return
    setSavedId(saved.id)
    if (pickedFile) {
      retryUpload(saved.id)
    } else {
      onClose()
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!canSubmit || amountMinor == null) return

    // The claim already saved this dialog session — only the receipt upload failed; retry just
    // that instead of re-creating/re-saving the claim.
    if (savedId) {
      retryUpload(savedId)
      return
    }

    const body: ClaimBody = {
      categoryId,
      amountMinor,
      currency,
      expenseDate,
      merchant: merchant.trim() || undefined,
      note: note.trim() || undefined,
      reimbursementMethod: method,
    }

    if (isEditing) {
      updateDraft.mutate(body, { onSuccess: afterSave })
    } else {
      createClaim.mutate(body, { onSuccess: afterSave })
    }
  }

  const submitLabel = savedId
    ? t('me.expenses.form.retryUpload')
    : saving
      ? t('me.expenses.form.saving')
      : uploading
        ? t('me.expenses.form.saving')
        : isEditing
          ? t('me.expenses.form.submitEdit')
          : t('me.expenses.form.submitCreate')

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="max-h-[85vh] space-y-4 overflow-y-auto">
        <h2 className="font-display text-lg font-semibold text-ink">
          {isEditing ? t('me.expenses.form.editTitle') : t('me.expenses.form.newTitle')}
        </h2>

        <Field label={t('me.expenses.form.category')} htmlFor="claim-category">
          {categories.isLoading ? (
            <Skeleton className="h-[52px] rounded-xl" />
          ) : (categories.data ?? []).length === 0 ? (
            <p className="rounded-xl border border-line bg-paper px-3.5 py-2.5 text-sm text-ink-3">
              {t('me.expenses.form.categoriesEmpty')}
            </p>
          ) : (
            <Select
              id="claim-category"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              required
            >
              <option value="" disabled>
                {t('me.expenses.form.categoryPlaceholder')}
              </option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          )}
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field
            label={
              <>
                {t('me.expenses.form.amount')} <span className="text-ink-3">({currency})</span>
              </>
            }
            htmlFor="claim-amount"
          >
            <TextInput
              id="claim-amount"
              type="number"
              inputMode="decimal"
              min="0"
              step={amountStep}
              value={amountInput}
              onChange={(e) => setAmountInput(e.target.value)}
              required
            />
          </Field>
          <Field label={t('me.expenses.form.date')} htmlFor="claim-date">
            <TextInput
              id="claim-date"
              type="date"
              value={expenseDate}
              onChange={(e) => setExpenseDate(e.target.value)}
              max={todayIso()}
              required
            />
          </Field>
        </div>

        <Field label={t('me.expenses.form.merchant')} htmlFor="claim-merchant">
          <TextInput
            id="claim-merchant"
            value={merchant}
            onChange={(e) => setMerchant(e.target.value)}
            maxLength={160}
          />
        </Field>

        <Field label={t('me.expenses.form.note')} htmlFor="claim-note">
          <textarea
            id="claim-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            maxLength={4000}
            className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
        </Field>

        <Field label={t('me.expenses.form.method')} htmlFor="claim-method">
          <div className="flex gap-2">
            {(['PAYROLL', 'DIRECT'] as const).map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => setMethod(m)}
                aria-pressed={method === m}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                  method === m
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {t(`expenses.reimbursementMethod.${m}`)}
              </button>
            ))}
          </div>
          <p className="mt-1.5 text-xs leading-relaxed text-ink-3">
            {method === 'PAYROLL'
              ? t('me.expenses.form.methodPayrollHint')
              : t('me.expenses.form.methodDirectHint')}
          </p>
        </Field>

        <Field label={t('me.expenses.form.receipt')} htmlFor="claim-receipt">
          <div className="space-y-2">
            {localPreviewUrl ? (
              <div className="space-y-1.5">
                <img
                  src={localPreviewUrl}
                  alt=""
                  className="max-h-48 w-full rounded-xl border border-line object-contain"
                />
                <button
                  type="button"
                  onClick={clearPickedFile}
                  className="text-xs font-semibold text-ink-3 underline-offset-2 hover:text-ink hover:underline focus-visible:outline-2 focus-visible:outline-emerald"
                >
                  {t('me.expenses.form.receiptRemove')}
                </button>
              </div>
            ) : isEditing && existingReceipt.status === 'loading' ? (
              <div className="flex items-center gap-2 text-sm text-ink-3">
                <Spinner /> {t('me.expenses.detail.receiptLoading')}
              </div>
            ) : isEditing && existingReceipt.status === 'ready' && existingReceipt.url ? (
              <img
                src={existingReceipt.url}
                alt=""
                className="max-h-48 w-full rounded-xl border border-line object-contain"
              />
            ) : null}

            <input
              ref={fileInputRef}
              id="claim-receipt"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={handleFileChange}
              className={cn(
                'block w-full text-sm text-ink-2',
                'file:mr-3 file:rounded-lg file:border-0 file:bg-emerald-tint file:px-3 file:py-2',
                'file:text-sm file:font-semibold file:text-emerald-2 hover:file:bg-brand-100/60',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              )}
            />
            {fileError === 'size' ? (
              <p className="text-xs text-loss">{t('me.expenses.form.receiptTooLarge')}</p>
            ) : fileError === 'type' ? (
              <p className="text-xs text-loss">{t('me.expenses.form.receiptUnsupportedType')}</p>
            ) : (
              <p className="text-xs text-ink-3">{t('me.expenses.form.receiptHint')}</p>
            )}
          </div>
        </Field>

        {saveError ? <p className="text-sm text-loss">{t('me.expenses.form.error')}</p> : null}
        {uploadError ? <p className="text-sm text-loss">{t('me.expenses.form.receiptUploadError')}</p> : null}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSubmit}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
