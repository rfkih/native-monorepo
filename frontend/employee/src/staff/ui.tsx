/**
 * Shared building blocks for the Native Karyawan staff-app screens (ADR 0049 P5 redesign).
 * Small, presentational, and reused across Beranda / Cuti / Klaim / Slip / Profil so the six
 * screen files stay consistent and can be built independently.
 */
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/Badge'
import { cn } from '@/lib/cn'
import { useAuth } from '@/lib/authContext'
import { canHr } from '@/lib/rolePreset'
import type { TimeoffStatus } from '@/features/me/api'

/** Tenant params for the /me hooks. In oidc mode identity comes from the bearer; companyId/actor are
 *  only the cache key + the dev-header fallback — mirrors MeHomePhone. */
export function useTenant(): { companyId: string; actor: string } {
  const auth = useAuth()
  return { companyId: auth.companyId ?? 'me', actor: auth.actor }
}

/**
 * True when the signed-in person can decide their team's requests (ADR 0049 P5 "one app, two roles").
 * The HR bundle (owner / manager / hr) is what the gateway gates the leave/overtime/claim decision
 * endpoints on — this is UI gating only; the gateway is the real boundary (see rolePreset.ts). No
 * elevation concept in the employee app (a plain personal login), so the base roles are the merged set.
 */
export function useIsSupervisor(): boolean {
  const auth = useAuth()
  return canHr(auth.roles)
}

/** Time-of-day greeting bucket for the home header (staff.greeting.*). */
export function greetingKey(): 'morning' | 'afternoon' | 'evening' {
  const h = new Date().getHours()
  if (h < 11) return 'morning'
  if (h < 18) return 'afternoon'
  return 'evening'
}

/** The mono, uppercase micro-label that heads each section (mirrors the mockup + MeHomePhone). */
export function SectionLabel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'font-mono text-[11px] font-semibold uppercase tracking-[0.06em] text-ink-3',
        className,
      )}
    >
      {children}
    </div>
  )
}

/** A leave/overtime request status pill (claims reuse ClaimStatusBadge from features/expenses/parts). */
export function TimeoffStatusBadge({ status }: { status: TimeoffStatus }) {
  const { t } = useTranslation()
  const tone =
    status === 'APPROVED'
      ? 'profit'
      : status === 'SUBMITTED'
        ? 'amber'
        : status === 'REJECTED'
          ? 'loss'
          : 'neutral'
  return <Badge tone={tone}>{t(`staff.status.${status}`)}</Badge>
}

/** The white, sticky screen title bar used by the tab screens (Cuti / Klaim / Slip). */
export function StaffHeader({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-line bg-surface/90 px-4 backdrop-blur">
      <h1 className="text-[18px] font-bold leading-tight text-ink">{title}</h1>
      {action ? <div className="ml-auto">{action}</div> : null}
    </header>
  )
}
