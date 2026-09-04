/**
 * Pure guard for the QRIS gateway "active environment" switch (ADR 0045 per-environment amendment).
 * Deliberately fetch-free (like `effectiveMode.ts`) so it is unit-testable in isolation.
 *
 * An environment can be made ACTIVE only when its own slot holds a server key — either one already
 * stored (`connected`) or one the owner has just typed into that environment's field. This is the
 * client mirror of the server's structural guard (activating an empty slot is a 422): it lets the
 * form disable Save + warn BEFORE the round-trip, so an owner can never activate SANDBOX against a
 * PRODUCTION key (or vice-versa), which was the silent till-time failure this feature removes.
 */
import type { GatewayEnvironment, GatewaySettings } from './api'

/** The server keys the owner has typed into the form this session (blank = untouched). */
export interface TypedGatewayKeys {
  sandboxServerKey: string
  productionServerKey: string
}

export function canActivateEnvironment(
  env: GatewayEnvironment,
  gateway: GatewaySettings | null,
  typed: TypedGatewayKeys,
): boolean {
  if (env === 'SANDBOX') {
    return Boolean(gateway?.sandbox.connected) || typed.sandboxServerKey.trim().length > 0
  }
  return Boolean(gateway?.production.connected) || typed.productionServerKey.trim().length > 0
}

/** Whether the gateway's ACTIVE environment slot holds a key — the till's GATEWAY precondition. */
export function gatewayActiveConnected(gateway: GatewaySettings | null): boolean {
  if (!gateway) return false
  return gateway.activeEnvironment === 'SANDBOX'
    ? gateway.sandbox.connected
    : gateway.production.connected
}
