import { cn } from '@/lib/cn'

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <span className="grid size-7 place-items-center rounded-md bg-emerald text-surface shadow-[0_2px_8px_-2px_rgba(13,106,74,0.5)]">
        <span className="font-display text-base font-semibold leading-none">N</span>
      </span>
      <span className="font-display text-lg font-semibold tracking-tight text-ink">Native</span>
    </span>
  )
}
