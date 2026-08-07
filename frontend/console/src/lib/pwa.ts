/**
 * Service-worker bootstrap (ADR 0028 offline shell; ADR 0043 native-shell exception).
 *
 * Browser: register the workbox service worker (autoUpdate) — the app-shell precache that lets the
 * POS load offline.
 *
 * Inside the Native Till Android shell the service worker must NOT run. Capacitor injects
 * `window.Capacitor` (and with it the NativePrint bridge) by intercepting the DOCUMENT request at
 * the WebView network layer — a service-worker-served document never reaches that interceptor, so
 * once the SW controls the app every subsequent launch silently loses the bridge (field-found on
 * the first install: printer settings showed only dead browser tiles). In-app the shell always
 * loads from the network anyway (you're online to log in), and the IndexedDB offline QUEUE
 * (ADR 0028) is untouched — only offline cold-start of the shell is traded away; the
 * bundled-assets fallback (ADR 0043 D3) is the path if that ever matters.
 */
import { registerSW } from 'virtual:pwa-register'
import { isNativeShell } from '@/lib/escpos/transport'

export function bootstrapPwa(): void {
  if (!('serviceWorker' in navigator)) return

  if (!isNativeShell()) {
    registerSW({ immediate: true })
    return
  }

  // Native shell: tear down whatever an earlier version installed. If THIS page was served by
  // that SW (bridge missing), reload once so the network-served document gets the bridge —
  // after the unregister the SW is gone entirely, so the reloaded page cannot loop.
  void navigator.serviceWorker.getRegistrations().then(async (registrations) => {
    if (registrations.length === 0) return
    const results = await Promise.all(registrations.map((r) => r.unregister()))
    if (!results.some(Boolean)) return
    const w = window as unknown as { Capacitor?: unknown }
    if (navigator.serviceWorker.controller && !w.Capacitor) window.location.reload()
  })
}
