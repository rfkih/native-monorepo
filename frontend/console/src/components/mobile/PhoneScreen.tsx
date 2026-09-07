/**
 * PhoneScreen — layout wrapper for a full-page phone screen: paper background,
 * ScreenHeader, and a padded main column whose bottom padding clears the fixed
 * MobileTabBar (80px + breathing room).
 */
import { ScreenHeader } from './ScreenHeader'

export function PhoneScreen({
  title,
  backFallback,
  trailing,
  children,
}: {
  /** Already-translated title. */
  title: string
  /** Where the back arrow lands when history has nothing to pop — see ScreenHeader / BackButton. */
  backFallback?: string
  trailing?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="min-h-[100dvh] bg-paper">
      <ScreenHeader title={title} backFallback={backFallback} trailing={trailing} />
      {/* Bottom clearance for the fixed tab bar comes from MobileTabBarGate's in-flow spacer. */}
      <main className="mx-auto max-w-[640px] px-4 pt-4 pb-6">{children}</main>
    </div>
  )
}
