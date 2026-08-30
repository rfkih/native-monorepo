/**
 * NativeShell plugin bridge — the tiny Capacitor plugin both Android shells register (till +
 * employee, `NativeShellPlugin.kt`) for app-level actions the web layer cannot perform itself.
 *
 * `window.NativeShell` is checked first so tests (and any future non-Capacitor shell) can provide
 * the same surface directly — the convention set by lib/escpos/transport.ts for NativePrint.
 * Absent in every browser and in shell builds older than the plugin; callers must treat `false`
 * as "do it another way", never as an error.
 */
interface NativeShellBridge {
  /** Sends the app to the background (moveTaskToBack) — the "Exit" of the back-guard dialog. */
  minimize(): Promise<void>
}

interface ShellWindow {
  NativeShell?: NativeShellBridge
  Capacitor?: { Plugins?: { NativeShell?: NativeShellBridge } }
}

/** True when the shell actually minimized; false when the bridge is absent or the call failed. */
export async function minimizeNativeShell(): Promise<boolean> {
  try {
    if (typeof window === 'undefined') return false
    const w = window as unknown as ShellWindow
    const plugin = w.NativeShell ?? w.Capacitor?.Plugins?.NativeShell
    if (!plugin?.minimize) return false
    await plugin.minimize()
    return true
  } catch {
    return false
  }
}
