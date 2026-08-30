/**
 * Shared Channels components: the modal overlay — mirrors features/bank/parts.tsx and
 * features/ar|ap/parts.tsx (each feature keeps its own copy).
 */
import type { ReactNode } from 'react'
import { Card } from '@/components/ui/Card'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'

/** Simple modal overlay — closes on backdrop click, Escape, or the phone/browser Back button. */
export function DialogOverlay({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  useBackDismiss(onClose)
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <Card className="w-full max-w-md p-6">{children}</Card>
    </div>
  )
}
