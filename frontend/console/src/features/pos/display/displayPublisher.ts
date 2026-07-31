/**
 * displayPublisher.ts — lazy, zero-overhead publisher for the pos-display BroadcastChannel
 * (Phase 6, ADR 0029).
 *
 * The underlying BroadcastChannel is constructed ONLY once the cashier has opened the
 * customer-display window this session (`activate()` — wired to the "Customer display" button in
 * Pos.tsx), or it was already open in a PRIOR load of this same tab (a sessionStorage flag, so a
 * page refresh mid-shift doesn't silently stop feeding an already-open display). Every publishXxx()
 * called before that is a pure no-op — a terminal that never opens a display pays no
 * BroadcastChannel construction or postMessage cost at all.
 */
import { useEffect, useMemo } from 'react'
import { channelName } from './displayChannel'
import type { DisplayBreakdown, DisplayLine, DisplayMessage, DisplayMoney } from './displayChannel'

const OPENED_KEY_PREFIX = 'native.console.posDisplay.opened.'

function readOpenedFlag(outletId: string): boolean {
  try {
    return sessionStorage.getItem(OPENED_KEY_PREFIX + outletId) === '1'
  } catch {
    return false
  }
}

function writeOpenedFlag(outletId: string): void {
  try {
    sessionStorage.setItem(OPENED_KEY_PREFIX + outletId, '1')
  } catch {
    /* sessionStorage unavailable — in-memory only for this tab, activate() below still works */
  }
}

export class DisplayPublisher {
  private readonly outletId: string
  private channel: BroadcastChannel | null = null
  private opened = false

  constructor(outletId: string) {
    this.outletId = outletId
    if (readOpenedFlag(outletId)) this.activate()
  }

  /** True once a display has been opened this session — callers can use this to skip building the
   * (possibly non-trivial) message payload entirely, not just the postMessage call. */
  get isOpened(): boolean {
    return this.opened
  }

  /** Idempotent: marks the channel active and lazily constructs the BroadcastChannel. */
  activate(): void {
    this.opened = true
    writeOpenedFlag(this.outletId)
    if (!this.channel && typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(channelName(this.outletId))
    }
  }

  private post(message: DisplayMessage): void {
    if (!this.opened || !this.channel) return
    this.channel.postMessage(message)
  }

  publishCartUpdated(lines: DisplayLine[], breakdown: DisplayBreakdown | null): void {
    this.post({ type: 'CART_UPDATED', lines, breakdown })
  }

  publishPaymentStarted(due: DisplayMoney): void {
    this.post({ type: 'PAYMENT_STARTED', due })
  }

  publishPaymentCompleted(change: DisplayMoney): void {
    this.post({ type: 'PAYMENT_COMPLETED', change })
  }

  publishIdle(): void {
    this.post({ type: 'IDLE' })
  }

  dispose(): void {
    this.channel?.close()
    this.channel = null
  }
}

/**
 * One publisher instance per mounted POS session. Pos.tsx remounts entirely on an outlet switch
 * (its `key={session.businessId}` — see Pos.tsx's top-of-file comment), so a fresh publisher per
 * outlet id falls out naturally from the `useMemo` dependency.
 */
export function useDisplayPublisher(outletId: string): DisplayPublisher {
  const publisher = useMemo(() => new DisplayPublisher(outletId), [outletId])
  useEffect(() => () => publisher.dispose(), [publisher])
  return publisher
}
