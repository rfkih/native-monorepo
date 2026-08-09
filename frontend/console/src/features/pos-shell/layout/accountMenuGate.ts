/**
 * accountMenuGate.ts — pure decision core for the till-menu's account/session actions (ADR 0049
 * P3b personal elevation), mirroring `features/operator/operatorGate.ts`'s shape. Both Pos.tsx and
 * ServicePos.tsx build their `TillMenuSheet` items from this single source of truth so the two
 * surfaces can never drift on WHICH of the elevate / three logout actions a login sees.
 *
 * The three logouts (ADR 0049 P3b plan):
 *  - "Sign out operator" — shift change; clears the PIN-identified operator. Device terminal only,
 *    and only once an operator has actually signed in (unchanged from slice 1).
 *  - "Sign out of back office" — ends a personal elevation LOCALLY (no Keycloak round-trip); drops
 *    back to the bare outlet terminal. Device terminal, currently elevated.
 *  - "Log out outlet" — the real (KC-revoking) sign-out of the OUTLET credential itself. Device
 *    terminal, and OWNER/MANAGER-GUARDED: only offered while elevated AS owner or manager — a bare
 *    cashier operator (no elevation) must never be able to log the till itself out.
 *
 * A normal `user` login (byte-identical, ADR 0049's hard constraint) never sees any of the above —
 * it keeps the single plain "Sign out" item it always had.
 */
import type { BusinessRole } from '@/lib/authContext'

export interface AccountMenuVisibility {
  /** "Sign in to manage" — opens the personal-elevation login. Device terminal, not yet elevated. */
  showElevateEntry: boolean
  /** "Sign out operator" — device terminal with a signed-in operator (unchanged from slice 1). */
  showOperatorSignOut: boolean
  /** "Sign out of back office" — device terminal, currently elevated (any elevated role). */
  showEndElevation: boolean
  /** "Log out outlet" — device terminal, elevated AS owner or manager. */
  showLogoutOutlet: boolean
  /** The plain "Sign out" item — a normal `user` login only. */
  showPlainLogout: boolean
}

export function accountMenuVisibility(params: {
  isDeviceTerminal: boolean
  operatorSignedIn: boolean
  elevatedRoles: readonly BusinessRole[]
}): AccountMenuVisibility {
  const { isDeviceTerminal, operatorSignedIn, elevatedRoles } = params

  if (!isDeviceTerminal) {
    // A normal person login: none of the device-terminal actions exist, only the one it always had.
    return {
      showElevateEntry: false,
      showOperatorSignOut: false,
      showEndElevation: false,
      showLogoutOutlet: false,
      showPlainLogout: true,
    }
  }

  const elevated = elevatedRoles.length > 0
  const elevatedAsOwnerOrManager = elevatedRoles.includes('owner') || elevatedRoles.includes('manager')

  return {
    showElevateEntry: !elevated,
    showOperatorSignOut: operatorSignedIn,
    showEndElevation: elevated,
    showLogoutOutlet: elevated && elevatedAsOwnerOrManager,
    showPlainLogout: false,
  }
}
