/**
 * Native Till shell OTA readiness signal (ADR 0051 P3, Capgo self-hosted).
 *
 * Capgo rolls a freshly-downloaded web bundle BACK to the previous known-good one unless the app
 * calls {@code notifyAppReady()} shortly after boot — the guard against shipping a bundle that
 * white-screens. We call it once the app has rendered its first frame (see main.tsx).
 *
 * A no-op everywhere the plugin is absent: a normal browser, the thin-client shell, or a bundled
 * shell built before the OTA plugin existed. Never throws.
 */
interface CapacitorUpdaterPlugin {
  notifyAppReady(): Promise<unknown>
}

interface UpdaterWindow {
  Capacitor?: { Plugins?: { CapacitorUpdater?: CapacitorUpdaterPlugin } }
}

export function notifyNativeAppReady(): void {
  try {
    const updater = (window as unknown as UpdaterWindow).Capacitor?.Plugins?.CapacitorUpdater
    if (updater) void updater.notifyAppReady().catch(() => {})
  } catch {
    /* plugin absent (browser / thin shell / pre-OTA bundle) — nothing to signal */
  }
}
