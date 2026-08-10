/**
 * Shared building blocks for the Native Karyawan staff-app screens (ADR 0049 P5 redesign).
 * Small, presentational, and reused across Beranda / Cuti / Klaim / Slip / Profil so the six
 * screen files stay consistent and can be built independently.
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
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

/**
 * A small "ⓘ" affordance that explains a jargon label (e.g. PTKP) to an employee who won't know the
 * term. Desktop: the native `title` tooltip on hover. Phone (no hover): tap toggles a styled popover,
 * dismissed by tapping the info button again or anywhere outside (a transparent full-screen catcher).
 */
export function InfoTip({ label, text }: { label: string; text: string }) {
  const [open, setOpen] = useState(false)
  return (
    <span className="relative inline-flex align-middle">
      <button
        type="button"
        title={text}
        aria-label={label}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="grid size-5 place-items-center rounded-full text-ink-3 transition-colors hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-brand-500"
      >
        <Info className="size-[15px]" aria-hidden />
      </button>
      {open ? (
        <>
          <button
            type="button"
            aria-hidden
            tabIndex={-1}
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-40 cursor-default"
          />
          <span
            role="tooltip"
            className="absolute left-0 top-7 z-50 w-64 max-w-[72vw] rounded-xl border border-line bg-surface p-3 text-left text-[12.5px] font-normal leading-snug text-ink-2 shadow-lg"
          >
            {text}
          </span>
        </>
      ) : null}
    </span>
  )
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
