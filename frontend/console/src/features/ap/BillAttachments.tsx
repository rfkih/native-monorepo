/**
 * BillAttachments — the vendor invoice as evidence on a bill (ADR 0084): the list of photos/PDFs
 * with a tap-to-open (fetched with the personal bearer as a private blob — never a public URL)
 * and a remove. Rendered on the bill detail; uploads happen from the phone form at save time.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText, Image as ImageIcon, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
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
  // Shown right here, never window.open: a blob: URL cannot leave the page in the single-WebView
  // app shell, and iOS blocks a window.open that follows an await (the POS attachments precedent).
  const [lightbox, setLightbox] = useState<{ url: string; kind: 'image' | 'pdf' } | null>(null)
  useBackDismiss(() => setLightbox(null), lightbox != null)
  useScrollLock(lightbox != null)
  useEffect(() => {
    if (!lightbox) return undefined
    const url = lightbox.url
    return () => URL.revokeObjectURL(url)
  }, [lightbox])

  const open = async (attachmentId: string, contentType: string) => {
    setOpening(attachmentId)
    setFailed(false)
    try {
      const blob = await apiFetchBlob(billAttachmentPath(billId, attachmentId), {
        tenant: { companyId, actor },
        auth: 'personal',
      })
      if (!blob) throw new Error('empty')
      setLightbox({
        url: URL.createObjectURL(blob),
        kind: contentType === 'application/pdf' ? 'pdf' : 'image',
      })
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
        <p className="text-sm text-ink-3">{t('ap.detail.noAttachments')}</p>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-line bg-surface">
          {items.map((a) => (
            <div
              key={a.id}
              className="flex min-h-[52px] items-center gap-3 border-b border-line/60 px-3 py-2 last:border-b-0"
            >
              <button
                type="button"
                onClick={() => void open(a.id, a.contentType)}
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
                  <span className="block truncate text-sm font-semibold text-ink">
                    {a.originalFilename ?? a.contentType}
                  </span>
                  <span className="block text-xs text-ink-3">
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
        <p className="mt-2 text-xs text-loss-ink" role="alert">
          {t('ap.detail.errors.generic')}
        </p>
      ) : null}

      {lightbox ? (
        <div
          // A photo viewer, not a scrim: a near-black ground in BOTH themes (`ink-fixed`), so the image
          // reads the same whichever theme the page is in.
          className="fixed inset-0 z-[80] grid place-items-center bg-ink-fixed/85 p-4 print:hidden"
          role="dialog"
          aria-modal="true"
          aria-label={t('ap.detail.attachments')}
          onClick={() => setLightbox(null)}
        >
          {lightbox.kind === 'image' ? (
            <img
              src={lightbox.url}
              alt={t('ap.detail.attachments')}
              className="max-h-full max-w-full rounded-lg"
              onClick={(e) => e.stopPropagation()}
            />
          ) : (
            <iframe
              src={lightbox.url}
              title={t('ap.detail.attachments')}
              className="h-[85vh] w-[90vw] max-w-2xl rounded-lg bg-white"
              onClick={(e) => e.stopPropagation()}
            />
          )}
          <button
            type="button"
            onClick={() => setLightbox(null)}
            aria-label={t('common.close')}
            className="absolute right-4 top-4 grid size-10 place-items-center rounded-full bg-white/15 text-white hover:bg-white/25"
          >
            <X className="size-5" aria-hidden="true" />
          </button>
        </div>
      ) : null}
    </div>
  )
}
