import type { ComponentPropsWithRef } from 'react'
import { cn } from '@/lib/cn'

/** `ComponentPropsWithRef` rather than `HTMLAttributes` so a caller can hold the node — Dialog
 *  focuses the card as its panel. React 19 passes `ref` as an ordinary prop, so the spread below
 *  is all the forwarding needed. */
export function Card({ className, ...props }: ComponentPropsWithRef<'div'>) {
  return (
    <div
      className={cn('rounded-card border border-line bg-surface shadow-sm print:shadow-none', className)}
      {...props}
    />
  )
}
