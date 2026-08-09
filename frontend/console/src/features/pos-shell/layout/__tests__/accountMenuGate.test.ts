import { describe, expect, it } from 'vitest'
import { accountMenuVisibility } from '../accountMenuGate'

describe('accountMenuVisibility — ADR 0049 P3b the till-menu account actions', () => {
  it('a normal `user` login only ever sees the plain logout (byte-identical legacy behavior)', () => {
    expect(
      accountMenuVisibility({ isDeviceTerminal: false, operatorSignedIn: false, elevatedRoles: [] }),
    ).toEqual({
      showElevateEntry: false,
      showOperatorSignOut: false,
      showEndElevation: false,
      showLogoutOutlet: false,
      showPlainLogout: true,
    })
  })

  it('a normal `user` login sees only the plain logout even if elevatedRoles were somehow non-empty', () => {
    // Defensive: elevatedRoles should never be non-empty for a non-device login in practice, but the
    // gate must not accidentally light up device-only actions if it ever were.
    expect(
      accountMenuVisibility({
        isDeviceTerminal: false,
        operatorSignedIn: false,
        elevatedRoles: ['owner'],
      }),
    ).toEqual({
      showElevateEntry: false,
      showOperatorSignOut: false,
      showEndElevation: false,
      showLogoutOutlet: false,
      showPlainLogout: true,
    })
  })

  it('a bare device terminal (no operator, not elevated) offers only the elevate entry', () => {
    expect(
      accountMenuVisibility({ isDeviceTerminal: true, operatorSignedIn: false, elevatedRoles: [] }),
    ).toEqual({
      showElevateEntry: true,
      showOperatorSignOut: false,
      showEndElevation: false,
      showLogoutOutlet: false,
      showPlainLogout: false,
    })
  })

  it('a device terminal with a signed-in operator (not elevated) can sign out the operator but NOT log out the outlet', () => {
    const v = accountMenuVisibility({
      isDeviceTerminal: true,
      operatorSignedIn: true,
      elevatedRoles: [],
    })
    expect(v.showOperatorSignOut).toBe(true)
    expect(v.showLogoutOutlet).toBe(false)
    expect(v.showEndElevation).toBe(false)
    expect(v.showElevateEntry).toBe(true)
    expect(v.showPlainLogout).toBe(false)
  })

  it('a device terminal elevated as owner offers end-elevation AND log-out-outlet, but not the elevate entry', () => {
    const v = accountMenuVisibility({
      isDeviceTerminal: true,
      operatorSignedIn: false,
      elevatedRoles: ['owner'],
    })
    expect(v).toEqual({
      showElevateEntry: false,
      showOperatorSignOut: false,
      showEndElevation: true,
      showLogoutOutlet: true,
      showPlainLogout: false,
    })
  })

  it('a device terminal elevated as manager also unlocks log-out-outlet (owner OR manager guard)', () => {
    const v = accountMenuVisibility({
      isDeviceTerminal: true,
      operatorSignedIn: false,
      elevatedRoles: ['manager'],
    })
    expect(v.showLogoutOutlet).toBe(true)
    expect(v.showEndElevation).toBe(true)
  })

  it('elevated as a non owner/manager role (edge case) ends elevation but stays guarded off log-out-outlet', () => {
    const v = accountMenuVisibility({
      isDeviceTerminal: true,
      operatorSignedIn: false,
      elevatedRoles: ['cashier'],
    })
    expect(v.showEndElevation).toBe(true)
    expect(v.showLogoutOutlet).toBe(false)
  })

  it('a device terminal can be BOTH elevated and mid-operator-shift at once (independent flags)', () => {
    const v = accountMenuVisibility({
      isDeviceTerminal: true,
      operatorSignedIn: true,
      elevatedRoles: ['owner'],
    })
    expect(v.showOperatorSignOut).toBe(true)
    expect(v.showEndElevation).toBe(true)
    expect(v.showLogoutOutlet).toBe(true)
    expect(v.showElevateEntry).toBe(false)
  })
})
