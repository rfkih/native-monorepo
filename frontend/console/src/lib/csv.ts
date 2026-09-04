/**
 * Client-side CSV download for a loaded statement/report — the export path the statements never
 * had (audit finding 16). Quotes every cell; Excel/Sheets open it directly.
 *
 * Lives outside the statements feature because AR/AP aging, the tax report, and budgets export
 * through it too — and `parts.tsx` must only export components (react-refresh).
 *
 * In the Android shells the anchor-click path is a silent no-op (the WebView ignores
 * <a download>/blob), so the file is routed through NativeShell.saveFile into the device's
 * Downloads instead; FileSaveToast announces the outcome.
 */
import { saveBlobViaShell } from '@/lib/nativeShell'

export function downloadCsv(filename: string, rows: (string | number)[][]) {
  const csv = rows
    .map((row) => row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(','))
    .join('\r\n')
  // U+FEFF = UTF-8 BOM, so Excel detects the encoding instead of assuming ANSI.
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  void deliverDownload(blob, filename)
}

/** Shared blob delivery: shell save when bridged, browser anchor otherwise. */
export async function deliverDownload(blob: Blob, filename: string): Promise<void> {
  if ((await saveBlobViaShell(blob, filename)) !== 'no-bridge') return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Deferred revoke: a synchronous revoke races the navigation the click just started in some
  // engines; one macrotask later the download has grabbed its handle.
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
