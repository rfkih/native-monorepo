/**
 * The Android system bars, in one place.
 *
 * Both Android shells run edge-to-edge (targetSdk 36; Capacitor 8's SystemBars plugin, mode
 * `css`): the WebView draws UNDER the status bar and the navigation bar, and Capacitor writes the
 * real insets onto `<html style="--safe-area-inset-*">` on every layout change (index.css declares
 * the same variables as `env()` fallbacks for browsers/PWA). `body` pads itself with them, so
 * in-flow content is safe — but anything `fixed` (a sheet, a dock, a rail, a full-height drawer)
 * bypasses the body and MUST pad itself, or its bottom row sits under the home/back buttons and
 * its top row under the clock (owner report, 2026-09-11: "many screens overlap the phone's
 * default buttons").
 *
 * Use the var, never bare `env()`: the shell's injected values are what is real on the device.
 */
export const SAFE_AREA_TOP = 'var(--safe-area-inset-top, 0px)'
export const SAFE_AREA_BOTTOM = 'var(--safe-area-inset-bottom, 0px)'

/** Inline style for a fixed BOTTOM surface: the surface's own padding plus the nav-bar inset. */
export function safeBottom(px: number): { paddingBottom: string } {
  return { paddingBottom: `calc(${px}px + ${SAFE_AREA_BOTTOM})` }
}

/** Inline style for a fixed TOP surface: the surface's own padding plus the status-bar inset. */
export function safeTop(px: number): { paddingTop: string } {
  return { paddingTop: `calc(${px}px + ${SAFE_AREA_TOP})` }
}

/**
 * Height of a full-screen box that must not run under either bar. `100dvh` is the WHOLE screen in
 * an edge-to-edge WebView; inside the inset-padded body that overflows by the two insets and the
 * page scrolls by exactly that much.
 */
export const FULL_HEIGHT_BETWEEN_BARS = `calc(100dvh - ${SAFE_AREA_TOP} - ${SAFE_AREA_BOTTOM})`
