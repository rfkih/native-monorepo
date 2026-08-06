/**
 * Tier gating (P1 — `~/.claude/plans/umkm-tier-mode.md`, precedes ADR 0044): the single source of
 * truth for which console features a company's PLAN TIER unlocks. `FREE` is a lean, UMKM-friendly
 * surface (POS, products, kitchen, printer, dashboard, expenses, team); `FULL` is the historical
 * full back-office. Flipping a judgment call in the plan's Feature matrix = moving one line in
 * {@link FEATURE_MIN_TIER}, nowhere else.
 *
 * Composes with per-login page grants (ADR 0013) as an INDEPENDENT, ANDed gate — never a
 * replacement for them: `visible = role ∧ grant ∧ tier` (see Shell.tsx's nav filter). An owner
 * bypasses page grants but NOT tier (plan Risk 1) — the `/settings/features` toggle is the
 * always-visible escape hatch.
 *
 * UI-only (plan D7): this NEVER replaces the gateway's role check — a FREE company's owner token
 * can still reach a FULL-only endpoint directly; the tier only curates what the console SHOWS.
 */
import { useSession } from '@/lib/session'

export type PlanTier = 'FREE' | 'FULL'

/** FREE < FULL — PRO/ENTERPRISE (real billing, P3) would append with a higher rank; no other
 *  code changes, per the plan's "reader stays put" property. */
const TIER_RANK: Record<PlanTier, number> = { FREE: 0, FULL: 1 }

export function tierRank(tier: PlanTier): number {
  return TIER_RANK[tier]
}

/**
 * Every gateable console surface — a SUPERSET of the page-grant `PageKey` (lib/pageAccess.ts),
 * which only covers a handful of pages. Tier must also cover the many `canDashboard`-only routes
 * (AR/AP/bank/tax/budgets/assets/org/…) that have no `PageKey` at all — see the plan's D4.
 */
export type FeatureKey =
  | 'pos'
  | 'products'
  | 'kitchen'
  | 'printer'
  | 'dashboard'
  | 'expenses'
  | 'team'
  | 'statements'
  | 'accounting'
  | 'promotions'
  | 'channels'
  | 'orgStructure'
  | 'customerDisplay'
  | 'hr'

/**
 * The feature → minimum-tier map — the machine-readable form of the plan's Feature matrix (D5).
 *
 * `dashboard` is FREE and MUST stay FREE — never move it to FULL. Dashboard is never tier-HIDDEN
 * (FREE just renders a simplified variant, see `features/dashboard/Dashboard.tsx`); moving it to
 * FULL would break `home` route resolution for a FREE-tier owner/manager (redirect-loop risk, plan
 * Risk 2).
 */
export const FEATURE_MIN_TIER: Record<FeatureKey, PlanTier> = {
  pos: 'FREE',
  products: 'FREE',
  kitchen: 'FREE',
  printer: 'FREE',
  dashboard: 'FREE',
  expenses: 'FREE',
  team: 'FREE',
  statements: 'FULL',
  accounting: 'FULL',
  promotions: 'FULL',
  channels: 'FULL',
  orgStructure: 'FULL',
  customerDisplay: 'FULL',
  hr: 'FULL',
}

/** `tierRank(tier) >= tierRank(FEATURE_MIN_TIER[feature])` — the one predicate every gate uses. */
export function tierAllowsFeature(tier: PlanTier, feature: FeatureKey): boolean {
  return tierRank(tier) >= tierRank(FEATURE_MIN_TIER[feature])
}

/**
 * Fail-OPEN mapping from the server's (possibly absent/unrecognized) `planTier` string to the
 * console's {@link PlanTier} domain. A read gap (older server, dev mode, a transient hiccup) must
 * never HIDE a feature, so anything other than the literal `'FREE'` resolves to `'FULL'` (plan
 * Risk 5 — mirrors the page-grant fail-open stance in `lib/pageAccess.ts`).
 */
export function toPlanTier(value: string | null | undefined): PlanTier {
  return value === 'FREE' ? 'FREE' : 'FULL'
}

/**
 * The company's tier gate — reads the ACTIVE company's `planTier` (session.ts; already
 * fail-opened to `FULL` by {@link toPlanTier} if the server omitted it, and fail-opened again here
 * to `FULL` when there is no active company at all) and exposes the predicate every gated nav item
 * / route uses. `allows` composes with page grants and role checks as an independent AND — never
 * an OR (see Shell.tsx).
 */
export function useTierAccess(): {
  tier: PlanTier
  isExtended: boolean
  allows: (feature: FeatureKey) => boolean
} {
  const { company } = useSession()
  const tier: PlanTier = company?.planTier ?? 'FULL'
  return {
    tier,
    isExtended: tierRank(tier) >= tierRank('FULL'),
    allows: (feature: FeatureKey) => tierAllowsFeature(tier, feature),
  }
}
