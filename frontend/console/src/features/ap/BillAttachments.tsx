/**
 * BillAttachments — the vendor invoice as evidence on a bill (ADR 0084): the list of photos/PDFs
 * with a tap-to-open (fetched with the personal bearer as a private blob — never a public URL)
 * and a remove. Rendered on the bill detail; uploads happen from the phone form at save time.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText, Image as ImageIcon, X } from 'lucide-react'
import { apiFetchBlob } from '@/lib/api'
import { cn } from '@/lib/cn'
import { billAttachmentPath, useBillAttachments, useDeleteBillAttachment } from './api'

function sizeLabel(bytes: number, locale: string): string {
  const mb = bytes / (1024 * 1024)
  return mb >= 1
    ? `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(mb)} MB`
    : `${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(bytes / 1024)} KB`
}

export function BillAttachments({
  companyId,
  actor,
  billId,
  locale,
  canRemove,
}: {
  companyId: string
  actor: string
  billId: string
  locale: string
  canRemove: boolean
}) {
  const { t } = useTranslation()
  const query = useBillAttachments({ companyId, actor, id: billId })
  const remove = useDeleteBillAttachment({ companyId, actor, id: billId })
  const [opening, setOpening] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  const open = async (attachmentId: string) => {
    setOpening(attachmentId)
    setFailed(false)
    try {
      const blob = await apiFetchBlob(billAttachmentPath(billId, attachmentId), {
        tenant: { companyId, actor },
        auth: 'personal',
      })
      if (!blob) throw new Error('empty')
      const url = URL.createObjectURL(blob)
      window.open(url, '_blank', 'noopener')
      // The tab has the bytes; the object URL can go once it has surely loaded.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch {
      setFailed(true)
    } finally {
      setOpening(null)
    }
  }

  const items = query.data ?? []
  if (query.isLoading) return null

  return (
    <div>
      {items.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t('ap.detail.noAttachments')}</p>
      ) : (
        <div className="overflow-hidden rounded-[14px] border border-line bg-surface">
          {items.map((a) => (
            <div
              key={a.id}
              className="flex min-h-[52px] items-center gap-3 border-b border-line/60 px-3 py-2 last:border-b-0"
            >
              <button
                type="button"
                onClick={() => void open(a.id)}
                disabled={opening != null}
                className="flex min-w-0 flex-1 items-center gap-3 text-left hover:underline"
              >
                <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-hover text-ink-2">
                  {a.contentType === 'application/pdf' ? (
                    <FileText className="size-4" strokeWidth={1.9} aria-hidden="true" />
                  ) : (
                    <ImageIcon className="size-4" strokeWidth={1.9} aria-hidden="true" />
                  )}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-semibold text-ink">
                    {a.originalFilename ?? a.contentType}
                  </span>
                  <span className="block text-[11.5px] text-ink-3">
                    {sizeLabel(a.byteSize, locale)}
                  </span>
                </span>
              </button>
              {canRemove ? (
                <button
                  type="button"
                  onClick={() => remove.mutate(a.id)}
                  disabled={remove.isPending}
                  aria-label={t('ap.detail.removeAttachment')}
                  className={cn(
                    'grid size-8 shrink-0 place-items-center rounded-full text-ink-400 hover:bg-hover hover:text-ink',
                    remove.isPending && 'opacity-50',
                  )}
                >
                  <X className="size-4" strokeWidth={2.2} aria-hidden="true" />
                </button>
              ) : null}
            </div>
          ))}
        </div>
      )}
      {failed ? (
        <p className="mt-2 text-[12px] text-loss-ink" role="alert">
          {t('ap.detail.errors.generic')}
        </p>
      ) : null}
    </div>
  )
}
