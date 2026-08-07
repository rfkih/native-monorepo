/**
 * PhoneScreen — layout wrapper for a full-page phone screen: paper background,
 * ScreenHeader, and a padded main column whose bottom padding clears the fixed
 * MobileTabBar (80px + breathing room).
 */
import { ScreenHeader } from './ScreenHeader'

export function PhoneScreen({
  title,
  backTo,
  trailing,
  children,
}: {
  /** Already-translated title. */
  title: string
  backTo?: string
  trailing?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="min-h-screen bg-paper">
      <ScreenHeader title={title} backTo={backTo} trailing={trailing} />
      <main className="mx-auto max-w-[640px] px-4 pt-4 pb-[100px]">{children}</main>
    </div>
  )
}
