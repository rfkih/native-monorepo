/**
 * NativeShell plugin bridge — the tiny Capacitor plugin both Android shells register (till +
 * employee, `NativeShellPlugin.kt`) for app-level actions the web layer cannot perform itself:
 * backgrounding the app (back-guard "Exit"), saving files to Downloads (the WebView silently
 * ignores <a download>/blob clicks), and printing the current page (window.print() is a no-op in
 * a WebView).
 *
 * `window.NativeShell` is checked first so tests (and any future non-Capacitor shell) can provide
 * the same surface directly — the convention set by lib/escpos/transport.ts for NativePrint.
 * Every method is optional: absent in every browser and in shell builds older than the method;
 * callers must treat the "not available" results as "do it another way", never as an error.
 *
 * CONTRACT: capability detection is method-presence on the bridge, so every NativeShell method of
 * a given API_VERSION ships together in one APK — never expose a partial plugin surface (the
 * Kotlin getInfo/apiVersion probe exists as an escape hatch if that ever has to change).
 */
interface NativeShellBridge {
  /** Sends the app to the background (moveTaskToBack) — the "Exit" of the back-guard dialog. */
  minimize(): Promise<void>
  /** APK ≥ v2: writes base64 bytes into the device's Downloads via MediaStore (API 29+). */
  saveFile?(options: { base64: string; filename: string; mimeType: string }): Promise<{ uri: string }>
  /** APK ≥ v2: hands the current page to the Android print framework (honours @media print). */
  printPage?(options: { jobName?: string }): Promise<void>
}

interface ShellWindow {
  NativeShell?: NativeShellBridge
  Capacitor?: { Plugins?: { NativeShell?: NativeShellBridge } }
}

function getBridge(): NativeShellBridge | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as ShellWindow
  return w.NativeShell ?? w.Capacitor?.Plugins?.NativeShell ?? null
}

/** True when the shell actually minimized; false when the bridge is absent or the call failed. */
export async function minimizeNativeShell(): Promise<boolean> {
  try {
    const plugin = getBridge()
    if (!plugin?.minimize) return false
    await plugin.minimize()
    return true
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// File saving — the shell path of downloadCsv()/apiDownload()
// ---------------------------------------------------------------------------

export type NativeSaveResult = 'saved' | 'failed' | 'no-bridge'

/** Fired on window after a native save attempt so a mounted toast can confirm/explain — download
 *  helpers are plain functions with no access to React state. detail: {filename, ok}. */
export const FILE_SAVE_EVENT = 'nativeshell:file-save'

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error)
    reader.onload = () => {
      // result = "data:<mime>;base64,<payload>" — strip the prefix.
      const s = String(reader.result)
      resolve(s.slice(s.indexOf(',') + 1))
    }
    reader.readAsDataURL(blob)
  })
}

/**
 * Saves a Blob through the shell into the device's Downloads. 'no-bridge' = not running in a
 * shell (or an old APK) — the caller should fall back to the browser anchor-download path.
 * 'saved'/'failed' both announce themselves via FILE_SAVE_EVENT (FileSaveToast shows the result);
 * on 'failed' there is nothing better to fall back to (the anchor path is what the shell breaks).
 */
export async function saveBlobViaShell(blob: Blob, filename: string): Promise<NativeSaveResult> {
  const plugin = getBridge()
  if (!plugin?.saveFile) return 'no-bridge'
  let ok: boolean
  try {
    const base64 = await blobToBase64(blob)
    await plugin.saveFile({ base64, filename, mimeType: blob.type || 'application/octet-stream' })
    ok = true
  } catch {
    ok = false
  }
  window.dispatchEvent(new CustomEvent(FILE_SAVE_EVENT, { detail: { filename, ok } }))
  return ok ? 'saved' : 'failed'
}

// ---------------------------------------------------------------------------
// Printing — the shell path of window.print()
// ---------------------------------------------------------------------------

/**
 * Prints the current page: through the shell's print framework when available (where
 * window.print() is a dead no-op), else the browser's own print dialog. The @media print CSS
 * applies on both paths, so the output is identical. Fire-and-forget by design — print dialogs
 * report nothing useful back.
 */
export function printCurrentPage(jobName?: string): void {
  const plugin = getBridge()
  if (plugin?.printPage) {
    void plugin.printPage({ jobName }).catch(() => {
      /* the shell showed nothing — window.print() would do even less here */
    })
    return
  }
  window.print()
}
