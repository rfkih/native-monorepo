/**
 * Shared Platform-settlements components: the modal overlay — mirrors features/channels/parts.tsx
 * and features/bank|ar|ap/parts.tsx (each feature keeps its own copy).
 */
import type { ReactNode } from 'react'
import { Card } from '@/components/ui/Card'

/** Simple modal overlay — closes on backdrop click or Escape. */
export function DialogOverlay({ children, onClose }: { children: ReactNode; onClose: () => void }) {
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
