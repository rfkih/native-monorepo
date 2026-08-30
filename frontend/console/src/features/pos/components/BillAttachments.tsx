/**
 * BillAttachments — attach/view photos & PDFs (the "real receipt" or a supporting document) on a
 * bill/tagihan (ADR 0063). Self-contained: the attach button (camera/gallery/file), a lazy
 * thumbnail strip, an image lightbox, and per-item delete. The bytes are private — each thumbnail
 * and view fetches the AUTHENTICATED per-attachment endpoint as a Blob (never the anonymous
 * /api/media path) and turns it into an object URL it revokes on unmount.
 *
 * Perf: images are compressed client-side before upload (attachmentImage.ts); the server's private
 * ETag + cache means a re-viewed attachment revalidates to 304 instead of re-downloading.
 * Strings rule (rule 9): i18n keys only.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText, Loader2, Paperclip, Trash2, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { apiFetchBlob } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import { prepareAttachment } from '../lib/attachmentImage'
import {
  useBillAttachments,
  useDeleteBillAttachment,
  useUploadBillAttachment,
  type BillAttachmentMeta,
} from '../billsApi'

export function BillAttachments({
  session,
  billId,
}: {
  session: CompanySession
  billId: string
}) {
  const { t } = useTranslation()
  const list = useBillAttachments(session, billId)
  const upload = useUploadBillAttachment(session, billId)
  const remove = useDeleteBillAttachment(session, billId)
  const [error, setError] = useState<string | null>(null)
  const [lightboxUrl, setLightboxUrl] = useState<string | null>(null)
  // The lightbox is inline conditional JSX inside this always-mounted component.
  useBackDismiss(() => setLightboxUrl(null), lightboxUrl != null)

  async function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = '' // let the same file be re-picked later
    if (!file) return
    setError(null)
    try {
      const prepared = await prepareAttachment(file)
      await upload.mutateAsync(prepared)
    } catch (err) {
      const key =
        err instanceof Error && err.message.startsWith('bills.attach.')
          ? err.message
          : 'bills.attach.uploadError'
      setError(t(key))
    }
  }

  const attachments = list.data ?? []

  return (
    <div className="border-t border-line px-5 py-3">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-[13px] font-semibold text-ink-2">
          <Paperclip className="size-4 text-ink-3" aria-hidden="true" />
          {t('bills.attach.title')}
          {attachments.length > 0 ? (
            <span className="tnum font-mono text-ink-3">({attachments.length})</span>
          ) : null}
        </span>
        <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-line bg-surface px-3 py-1.5 text-[12px] font-semibold text-ink-2 transition-colors hover:border-emerald-line hover:bg-emerald-tint focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-emerald">
          {upload.isPending ? (
            <Loader2 className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
          ) : (
            <Paperclip className="size-4" aria-hidden="true" />
          )}
          {t('bills.attach.action')}
          <input
            type="file"
            accept="image/*,application/pdf"
            capture="environment"
            className="hidden"
            disabled={upload.isPending}
            onChange={onPick}
          />
        </label>
      </div>

      {error ? (
        <p className="mt-2 text-xs text-loss" role="alert">
          {error}
        </p>
      ) : null}

      {attachments.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-2">
          {attachments.map((a) => (
            <AttachmentThumb
              key={a.id}
              session={session}
              billId={billId}
              meta={a}
              onViewImage={setLightboxUrl}
              onDelete={() => remove.mutate(a.id)}
              deleting={remove.isPending}
            />
          ))}
        </div>
      ) : null}

      {lightboxUrl ? (
        <div
          className="fixed inset-0 z-[80] grid place-items-center bg-black/85 p-4 print:hidden"
          role="dialog"
          aria-modal="true"
          aria-label={t('bills.attach.title')}
          onClick={() => setLightboxUrl(null)}
        >
          <img
            src={lightboxUrl}
            alt={t('bills.attach.title')}
            className="max-h-full max-w-full rounded-lg"
            onClick={(e) => e.stopPropagation()}
          />
          <button
            type="button"
            onClick={() => setLightboxUrl(null)}
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

/**
 * One attachment tile. Images fetch their blob eagerly for the thumbnail; a PDF shows an icon and
 * fetches its blob only when tapped (opened in a new tab). Object URLs are revoked on unmount.
 */
function AttachmentThumb({
  session,
  billId,
  meta,
  onViewImage,
  onDelete,
  deleting,
}: {
  session: CompanySession
  billId: string
  meta: BillAttachmentMeta
  onViewImage: (url: string) => void
  onDelete: () => void
  deleting: boolean
}) {
  const { t } = useTranslation()
  const isImage = meta.contentType.startsWith('image/')
  const path = `/api/v1/bills/${billId}/attachments/${meta.id}`
  const [thumbUrl, setThumbUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!isImage) return undefined
    let objectUrl: string | null = null
    let cancelled = false
    void (async () => {
      try {
        const blob = await apiFetchBlob(path, {
          tenant: { companyId: session.companyId, actor: session.actor },
        })
        if (cancelled || !blob) return
        objectUrl = URL.createObjectURL(blob)
        setThumbUrl(objectUrl)
      } catch {
        /* the thumbnail just won't render — the row still lists + can be deleted */
      }
    })()
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [path, isImage, session.companyId, session.actor])

  async function openPdf() {
    const blob = await apiFetchBlob(path, {
      tenant: { companyId: session.companyId, actor: session.actor },
    })
    // The object URL is intentionally not revoked: revoking it immediately would race the new tab's
    // fetch, and it is freed anyway when this document unloads. One short-lived URL per manual open
    // (a rare action) is a negligible, bounded leak — not worth a load-listener on a foreign tab.
    if (blob) window.open(URL.createObjectURL(blob), '_blank', 'noopener')
  }

  return (
    <div className="relative">
      {isImage ? (
        <button
          type="button"
          onClick={() => thumbUrl && onViewImage(thumbUrl)}
          className="grid size-16 place-items-center overflow-hidden rounded-lg border border-line bg-ink-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          aria-label={t('bills.attach.title')}
        >
          {thumbUrl ? (
            <img src={thumbUrl} alt="" className="size-full object-cover" />
          ) : (
            <Loader2 className="size-4 animate-spin text-ink-3 motion-reduce:animate-none" aria-hidden="true" />
          )}
        </button>
      ) : (
        <button
          type="button"
          onClick={() => void openPdf()}
          className="flex size-16 flex-col items-center justify-center gap-0.5 rounded-lg border border-line bg-ink-50 text-ink-2 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          aria-label={t('bills.attach.title')}
        >
          <FileText className="size-6" aria-hidden="true" />
          <span className="text-[9px] font-bold tracking-wide">PDF</span>
        </button>
      )}
      <button
        type="button"
        onClick={onDelete}
        disabled={deleting}
        aria-label={t('bills.attach.remove')}
        className="absolute -right-1.5 -top-1.5 grid size-5 place-items-center rounded-full bg-loss text-white shadow-sm transition-transform hover:scale-110 disabled:opacity-50"
      >
        <Trash2 className="size-3" aria-hidden="true" />
      </button>
    </div>
  )
}
