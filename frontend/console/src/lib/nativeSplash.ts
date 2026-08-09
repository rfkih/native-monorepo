/**
 * Native Till shell (ADR 0043) cold-start splash hand-off.
 *
 * The Capacitor shell shows a branded splash while the WebView fetches the live console origin (see
 * native-till/capacitor.config.ts — `launchAutoHide` with a backstop duration). This drops that
 * splash the instant the web app has painted its first frame, so the splash covers EXACTLY the
 * network+boot gap and hands straight off to real content instead of flashing a blank frame.
 *
 * A no-op everywhere else: `window.Capacitor` is absent in a normal browser, and the optional chain
 * also tolerates an older shell build whose SplashScreen plugin isn't installed. Never throws.
 */
interface SplashScreenPlugin {
  hide(): Promise<void>
}

interface CapacitorWindow {
  Capacitor?: { Plugins?: { SplashScreen?: SplashScreenPlugin } }
}

export function hideNativeSplash(): void {
  try {
    const plugin = (window as unknown as CapacitorWindow).Capacitor?.Plugins?.SplashScreen
    if (plugin) void plugin.hide().catch(() => {})
  } catch {
    /* bridge absent / plugin missing — nothing to hide */
  }
}
