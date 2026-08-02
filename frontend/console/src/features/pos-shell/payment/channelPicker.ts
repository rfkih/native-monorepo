/**
 * channelPicker.ts — pure ONLINE-tender channel-selection logic (ADR 0036 Phase B3), shared by
 * ChannelListPanelView and its adapters. Deliberately NOT the vertical's `SalesChannelResponse`
 * (review-precedent from PaymentBreakdownLike: pos-shell is vertical-agnostic; any response shape
 * that satisfies this structural shape works, and either side can evolve independently).
 *
 * pos-shell rule: stateless/pure — no hooks, no fetching, no mutations.
 */

export interface SalesChannelLike {
  id: string
  code: string
  name: string
  active: boolean
}

/**
 * Active channels only, preserving server order. The ONLINE tender must never offer a channel the
 * server would reject (inactive) — mirrors the backend's own "channel missing/inactive" 422 rule.
 */
export function activeChannels(channels: SalesChannelLike[]): SalesChannelLike[] {
  return channels.filter((c) => c.active)
}

/**
 * True once `code` names a channel that is BOTH present and active in `channels` — the ONLINE
 * Pay button's gate. A previously-picked code whose channel has since gone inactive (the list
 * refreshed under the cashier) stops counting immediately, so a stale selection can never sneak an
 * inactive channel through to checkout.
 */
export function isSelectableChannel(channels: SalesChannelLike[], code: string | null): boolean {
  if (!code) return false
  return channels.some((c) => c.code === code && c.active)
}
