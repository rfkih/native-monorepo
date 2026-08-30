/**
 * FileSaveToast — confirms (or explains) a NativeShell file save. The download helpers
 * (lib/csv.ts deliverDownload) are plain functions with no React state, so they announce the
 * outcome via FILE_SAVE_EVENT and this app-global listener renders it. Mounted once beside the
 * other global surfaces in App.tsx; renders nothing outside the shells (the event only fires
 * when the bridge handled a save).
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, TriangleAlert } from 'lucide-react'
import { FILE_SAVE_EVENT } from '@/lib/nativeShell'

const SHOW_MS = 3500

export function FileSaveToast() {
  const { t } = useTranslation()
  const [toast, setToast] = useState<{ filename: string; ok: boolean } | null>(null)
  const timerRef = useRef<number | undefined>(undefined)

  useEffect(() => {
    function onSave(e: Event) {
      const detail = (e as CustomEvent<{ filename: string; ok: boolean }>).detail
      if (!detail) return
      setToast(detail)
      window.clearTimeout(timerRef.current)
      timerRef.current = window.setTimeout(() => setToast(null), SHOW_MS)
    }
    window.addEventListener(FILE_SAVE_EVENT, onSave)
    return () => {
      window.removeEventListener(FILE_SAVE_EVENT, onSave)
      window.clearTimeout(timerRef.current)
    }
  }, [])

  if (!toast) return null
  return (
    <div
      className="pointer-events-none fixed inset-x-0 bottom-[calc(1.5rem+var(--safe-area-inset-bottom,0px))] z-[100] flex justify-center px-4 print:hidden"
      role="status"
    >
      <span className="flex max-w-full items-center gap-2 rounded-full bg-ink px-4 py-2 text-sm font-medium text-surface shadow-lg">
        {toast.ok ? (
          <CheckCircle2 className="size-4 shrink-0 text-profit" aria-hidden="true" />
        ) : (
          <TriangleAlert className="size-4 shrink-0 text-amber" aria-hidden="true" />
        )}
        <span className="truncate">
          {toast.ok
            ? t('common.fileSaved', { filename: toast.filename })
            : t('common.fileSaveFailed', { filename: toast.filename })}
        </span>
      </span>
    </div>
  )
}
