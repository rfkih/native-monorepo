/**
 * useScrollLock — freeze background (body) scrolling while an overlay is open. Without it a touch
 * flick on a modal scroll-chains into the page behind the scrim (there is no global scroll-lock
 * anywhere else, and only a handful of modal scroll areas set `overscroll-contain`).
 *
 * Counter-based module state so stacked overlays compose: the FIRST lock hides body overflow, the
 * LAST unlock restores it. Desktop keeps its layout stable via a padding-right compensation equal
 * to the vanished scrollbar. Same-signature convention as useBackDismiss: pass `active=false` for
 * an overlay that is mounted but not visible (inline-conditional pattern).
 */
import { useEffect } from 'react'

let locks = 0
let savedOverflow = ''
let savedPaddingRight = ''

export function useScrollLock(active = true) {
  useEffect(() => {
    if (!active) return
    if (typeof document === 'undefined') return
    if (locks === 0) {
      const body = document.body
      savedOverflow = body.style.overflow
      savedPaddingRight = body.style.paddingRight
      const scrollbar = window.innerWidth - document.documentElement.clientWidth
      if (scrollbar > 0) body.style.paddingRight = `${scrollbar}px`
      body.style.overflow = 'hidden'
    }
    locks += 1
    return () => {
      locks -= 1
      if (locks === 0) {
        document.body.style.overflow = savedOverflow
        document.body.style.paddingRight = savedPaddingRight
      }
    }
  }, [active])
}
