import { useSyncExternalStore } from 'react'

/**
 * Light/dark theme controller — a plain module (mirrors src/i18n/index.ts), not a context provider.
 * The initial `data-theme` is written by an inline <head> script in index.html (before paint, no
 * FOUC); this module owns every subsequent change and lets components subscribe via useTheme().
 */

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'native.console.theme'
const EVENT = 'native:theme'

function systemPref(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** Current theme, read from the attribute the inline script already set on <html>. */
export function getTheme(): Theme {
  const attr = document.documentElement.dataset.theme
  return attr === 'dark' ? 'dark' : attr === 'light' ? 'light' : systemPref()
}

export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    /* ignore — storage may be unavailable */
  }
  syncThemeColor()
  window.dispatchEvent(new CustomEvent(EVENT))
}

/**
 * The browser / Android status bar (`<meta name="theme-color">`) follows the theme the user
 * chose, not the OS: index.html sets it before first paint, and this re-reads `--color-paper` —
 * the page ground — from the token itself once `data-theme` has flipped, so the two can't drift.
 */
function syncThemeColor(): void {
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (!meta) return
  const paper = getComputedStyle(document.documentElement).getPropertyValue('--color-paper').trim()
  if (paper) meta.content = paper
}

export function toggleTheme(): void {
  setTheme(getTheme() === 'dark' ? 'light' : 'dark')
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener(EVENT, onChange)
  return () => window.removeEventListener(EVENT, onChange)
}

/** Subscribe a component to the active theme. Returns the theme plus a toggle helper. */
export function useTheme(): { theme: Theme; toggle: () => void; setTheme: (t: Theme) => void } {
  const theme = useSyncExternalStore(subscribe, getTheme, () => 'light' as Theme)
  return { theme, toggle: toggleTheme, setTheme }
}
